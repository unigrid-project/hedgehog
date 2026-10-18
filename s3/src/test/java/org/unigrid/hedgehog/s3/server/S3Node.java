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

package org.unigrid.hedgehog.s3.server;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.time.Clock;
import java.time.Duration;
import lombok.Getter;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.NamespaceConfig;
import org.unigrid.hedgehog.s3.index.PosixFiles;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;
import org.unigrid.hedgehog.s3.spi.MemoryBlobStore;
import software.amazon.awssdk.services.s3.S3Client;

/* A node serving every S3 operation, with its blobs in memory and its index on a file system in memory, and a
   client of it */
@Getter
public final class S3Node implements AutoCloseable {
	private final MemoryBlobStore blobs = new MemoryBlobStore();
	private final FileSystem files = PosixFiles.create();
	private final Namespace namespace;
	private final int port;
	private final S3Server server;
	private final S3Client client;

	private S3Node(final Clock clock) throws Exception {
		namespace = namespace(files, blobs);
		port = S3Fixture.freePort();
		server = S3Server.start(S3Fixture.config(port), S3Server.resources(S3Fixture.config(port), blobs, namespace,
			S3Fixture.credentials(), clock)
		);
		client = S3Fixture.client(port);
	}

	public static S3Node start() throws Exception {
		return new S3Node(Clock.systemUTC());
	}

	public static S3Node start(final Clock clock) throws Exception {
		return new S3Node(clock);
	}

	/* Maintained by hand only, so no background thread outlives a test */
	static Namespace namespace(final FileSystem files, final BlobStore blobs) throws IOException, BlobStoreException {
		return Namespace.open(files.getPath("/index"), blobs, NamespaceConfig.builder().maintenanceInterval(
			Duration.ZERO).build()
		);
	}

	@Override
	public void close() throws IOException {
		client.close();
		server.close();
		namespace.close();
		files.close();
	}
}
