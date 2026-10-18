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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;

public class S3CredentialsTest {
	/* On an in-memory file system nothing written here can reach the disk, since Path.toFile() refuses its paths */
	private static Path file() {
		return Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("basic", "owner", "posix", "unix")
			.build()).getPath("/home/node/.local/share/hedgehog/s3/credentials");
	}

	@Property(tries = 50)
	public void createsKeysOfTheShapeClientsExpect() throws IOException {
		final S3Credentials.Key key = new S3Credentials(file()).create();

		assertThat(key.accessKeyId(), matchesPattern("HK[A-Z0-9]{18}"));
		assertThat(key.secretKey(), matchesPattern("[A-Za-z0-9+/]{40}"));
	}

	@Example
	public void createsDistinctKeys() throws IOException {
		final S3Credentials credentials = new S3Credentials(file());
		final List<S3Credentials.Key> keys = IntStream.range(0, 100).mapToObj(i -> create(credentials)).toList();

		assertThat(keys.stream().map(S3Credentials.Key::accessKeyId).collect(Collectors.toSet()), hasSize(100));
		assertThat(keys.stream().map(S3Credentials.Key::secretKey).collect(Collectors.toSet()), hasSize(100));
		assertThat(credentials.list(), hasSize(100));
	}

	private static S3Credentials.Key create(final S3Credentials credentials) {
		try {
			return credentials.create();
		} catch (IOException ex) {
			throw new AssertionError(ex);
		}
	}

	@Property(tries = 20)
	public void findsTheSecretOfEveryKeyItKeeps(@ForAll @IntRange(min = 1, max = 10) int count) throws IOException {
		final Path file = file();
		final List<S3Credentials.Key> keys = IntStream.range(0, count).mapToObj(i -> create(new S3Credentials(file)))
			.toList();
		final S3Credentials credentials = new S3Credentials(file);

		for (S3Credentials.Key key : keys) {
			assertThat(credentials.secretKey(key.accessKeyId()), equalTo(Optional.of(key.secretKey())));
		}

		assertThat(credentials.list(), equalTo(keys.stream().map(S3Credentials.Key::accessKeyId).sorted().toList()));
	}

	@Example
	public void forgetsADeletedKey() throws IOException {
		final S3Credentials credentials = new S3Credentials(file());
		final S3Credentials.Key deleted = credentials.create();
		final S3Credentials.Key kept = credentials.create();

		assertThat(credentials.delete(deleted.accessKeyId()), equalTo(true));
		assertThat(credentials.delete(deleted.accessKeyId()), equalTo(false));
		assertThat(credentials.secretKey(deleted.accessKeyId()), equalTo(Optional.empty()));
		assertThat(credentials.secretKey(kept.accessKeyId()), equalTo(Optional.of(kept.secretKey())));
		assertThat(credentials.list(), equalTo(List.of(kept.accessKeyId())));
	}

	@Example
	public void knowsNoKeyBeforeTheFirstIsCreated() throws IOException {
		final S3Credentials credentials = new S3Credentials(file());

		assertThat(credentials.secretKey("HKANYTHING0000000000"), equalTo(Optional.empty()));
		assertThat(credentials.list(), empty());
		assertThat(credentials.delete("HKANYTHING0000000000"), equalTo(false));
	}

	@Example
	public void keepsTheKeysReadableByTheOwnerOnly() throws IOException {
		final Path file = file();

		new S3Credentials(file).create();

		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), equalTo("rw-------"));
		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())),
			equalTo("rwx------")
		);
		assertThat(Files.list(file.getParent()).toList(), equalTo(List.of(file)));
	}
}
