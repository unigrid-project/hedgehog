/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.server.s3;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.command.option.S3Options;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.s3.cache.CachingBlobStore;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.NamespaceConfig;
import org.unigrid.hedgehog.s3.lifecycle.LifecycleSweeper;
import org.unigrid.hedgehog.s3.server.S3Server;
import org.unigrid.hedgehog.s3.server.S3ServerConfig;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;
import org.unigrid.hedgehog.service.storage.StorageBlobStore;
import org.unigrid.hedgehog.service.storage.StorageService;

@Slf4j
@Eager @ApplicationScoped
public class S3Gateway {
	@Inject
	private StorageService storage;

	private Namespace namespace;
	private S3Server server;
	private LifecycleSweeper sweeper;

	static S3ServerConfig config() {
		return S3ServerConfig.builder().host(S3Options.getHost()).port(S3Options.getPort())
			.domain(S3Options.getDomain()).tls(!S3Options.isPlain()).build();
	}

	/* The runs and manifests of the index are blobs like any object, read through the same cache */
	@SneakyThrows
	@PostConstruct
	public void init() {
		final BlobStore blobs = cached(new StorageBlobStore(storage));

		namespace = namespace(S3Options.getIndexDirectory(), blobs);

		try {
			sweeper = sweeper(namespace);
			server = S3Server.start(config(), blobs, namespace, new S3Credentials(S3Credentials.getFile()));
		} catch (Exception ex) {
			Optional.ofNullable(sweeper).ifPresent(LifecycleSweeper::close);
			namespace.close();
			throw ex;
		}
	}

	static LifecycleSweeper sweeper(final Namespace namespace) {
		return LifecycleSweeper.start(namespace, Clock.systemUTC(), S3Options.getLifecycleInterval());
	}

	static BlobStore cached(final BlobStore store) {
		return new CachingBlobStore(store, S3Options.getCacheDirectory(), S3Options.getCacheSize(),
			S3Options.getCacheHalfLife());
	}

	static Namespace namespace(final Path directory, final BlobStore blobs) throws IOException, BlobStoreException {
		final NamespaceConfig config = NamespaceConfig.builder().build();
		final Optional<String> root = S3Options.getRoot();

		return root.isPresent() ? Namespace.mount(directory, blobs, new BlobId(root.get()), config)
			: Namespace.open(directory, blobs, config);
	}

	/* Writes a failed flush leaves behind stay in the journal and reach the network on the next start */
	@PreDestroy
	public void destroy() throws IOException {
		sweeper.close();
		server.close();

		try {
			namespace.flush();
		} catch (IOException | BlobStoreException ex) {
			log.warn("Failed to flush the S3 namespace index, whose latest writes stay in its journal", ex);
		} finally {
			namespace.close();
		}
	}
}
