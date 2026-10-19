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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import org.unigrid.hedgehog.command.option.OptionsCli;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.model.InMemoryDataDirectoryMockUp;
import org.unigrid.hedgehog.s3.cache.CachingBlobStore;
import org.unigrid.hedgehog.s3.index.Bucket;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.lifecycle.LifecycleSweeper;
import org.unigrid.hedgehog.s3.server.S3ServerConfig;
import org.unigrid.hedgehog.s3.spi.Blob;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobNotFoundException;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.service.storage.StorageBlobStore;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class S3GatewayTest {
	private static class MapBlobStore implements BlobStore {
		private final Map<BlobId, byte[]> blobs = new ConcurrentHashMap<>();

		@Override
		public BlobId store(InputStream input) throws IOException {
			final BlobId id = new BlobId(UUID.randomUUID().toString());

			blobs.put(id, input.readAllBytes());
			return id;
		}

		@Override
		public Blob open(BlobId id) throws BlobNotFoundException {
			final byte[] content = Optional.ofNullable(blobs.get(id)).orElseThrow(BlobNotFoundException::new);

			return new Blob() {
				@Override
				public long size() {
					return content.length;
				}

				@Override
				public void writeTo(OutputStream output) throws IOException {
					output.write(content);
				}
			};
		}

		@Override
		public void delete(BlobId id) {
			blobs.remove(id);
		}
	}

	@Provide
	public Arbitrary<String> provideHost() {
		return OptionsCli.hosts();
	}

	@Property
	public void shouldServeOverTlsWhereTheOptionsSay(@ForAll("provideHost") String host,
		@ForAll @IntRange(min = 1, max = OptionsCli.MAX_PORT) int port, @ForAll("provideHost") String domain) {

		OptionsCli.parse(List.of("daemon"), "--s3host", host, "--s3port", String.valueOf(port), "--s3domain", domain);

		assertThat(S3Gateway.config(), equalTo(S3ServerConfig.builder().host(host).port(port).domain(domain).build()));
	}

	@Example
	public void shouldServeTheStorageThroughACache() {
		OptionsCli.parse(List.of("daemon"));

		assertThat(S3Gateway.cached(new StorageBlobStore(null)), instanceOf(CachingBlobStore.class));
	}

	@Example
	public void shouldStartNothingWhenS3IsDisabled() throws Exception {
		final Path data = new InMemoryDataDirectoryMockUp().getUserDataDir();
		final S3Gateway gateway = new S3Gateway();

		OptionsCli.parse(List.of("daemon"), "--no-s3");
		gateway.init();
		gateway.destroy();

		assertThat(Files.exists(data.resolve("s3")), equalTo(false));
	}

	@Property
	public void shouldSizeTheCacheAsTheOptionsSay(@ForAll @LongRange(min = Long.MIN_VALUE, max = -1) long size) {
		OptionsCli.parse(List.of("daemon"), "--s3cachesize=" + size);

		assertThrows(IllegalArgumentException.class, () -> S3Gateway.cached(new StorageBlobStore(null)));
	}

	@Example
	public void shouldMountTheNamespaceItsRootNames() throws Exception {
		final BlobStore blobs = new MapBlobStore();
		final BlobId root;

		try (FileSystem files = Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("basic",
			"owner", "posix").build())) {

			OptionsCli.parse(List.of("daemon"));

			try (Namespace namespace = S3Gateway.namespace(files.getPath("/lost"), blobs)) {
				namespace.createBucket(Bucket.builder().name("photos").created(Instant.EPOCH).build());
				namespace.flush();
				root = namespace.root().orElseThrow();
			}

			OptionsCli.parse(List.of("daemon"), "--s3root", root.value());

			try (Namespace namespace = S3Gateway.namespace(files.getPath("/recovered"), blobs)) {
				assertThat(namespace.buckets().stream().map(Bucket::name).toList(), equalTo(List.of("photos")));
			}
		}
	}

	@Example
	public void shouldApplyLifecycleRulesAtTheIntervalTheOptionsSay() throws Exception {
		try (FileSystem files = Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("basic",
			"owner", "posix").build())) {

			OptionsCli.parse(List.of("daemon"), "--s3lifecycleinterval", "PT5M");

			try (Namespace namespace = S3Gateway.namespace(files.getPath("/index"), new MapBlobStore());
				LifecycleSweeper sweeper = S3Gateway.sweeper(namespace)) {

				assertThat(sweeper.getInterval(), equalTo(Duration.ofMinutes(5)));
				OptionsCli.parse(List.of("daemon"), "--s3lifecycleinterval", "PT-5M");
				assertThrows(IllegalArgumentException.class, () -> S3Gateway.sweeper(namespace));
			}
		}
	}

	@Property
	public void shouldServePlainHttpWhenAsked(@ForAll @IntRange(min = 1, max = OptionsCli.MAX_PORT) int port) {
		OptionsCli.parse(List.of("daemon"), "--s3port", String.valueOf(port), "--s3plain");

		assertThat(S3Gateway.config(), equalTo(S3ServerConfig.builder().host("localhost").port(port).tls(false)
			.build()
		));
	}
}
