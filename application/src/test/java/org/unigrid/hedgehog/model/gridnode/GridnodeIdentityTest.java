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

package org.unigrid.hedgehog.model.gridnode;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.model.crypto.Signature;

public class GridnodeIdentityTest {
	private static final String OWNER_ONLY = "rw-------";

	/* The plain unix configuration has no permission attributes, which the key file check reads */
	private static FileSystem posixFileSystem() {
		return Jimfs.newFileSystem(Configuration.unix().toBuilder()
			.setAttributeViews("basic", "owner", "posix", "unix").build());
	}

	@SneakyThrows
	private static Path write(FileSystem fs, String content, String permissions) {
		final Path file = fs.getPath("/keys/gridnode.key");

		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
		return file;
	}

	private static String printed(Signature key) {
		return "Private Key: " + key.getPrivateKey() + "\nPublic Key: " + key.getPublicKey() + "\n";
	}

	private static String messageOfLoading(Path file) {
		try {
			GridnodeIdentity.load(file);
			return "";
		} catch (IllegalArgumentException ex) {
			return ex.getMessage();
		}
	}

	private static String messageOfSigning(GridnodeIdentity identity) {
		try {
			identity.sign(Gridnode.Status.ACTIVE, "h:1", 1, 0);
			return "";
		} catch (IllegalStateException ex) {
			return ex.getMessage();
		}
	}

	@SneakyThrows
	private static Signature newKey() {
		return new Signature();
	}

	@Example
	@SneakyThrows
	public void shouldLoadWhatKeyGeneratePrints() {
		final Signature key = new Signature();

		try (FileSystem fs = posixFileSystem()) {
			final GridnodeIdentity identity = GridnodeIdentity.load(write(fs, printed(key), OWNER_ONLY));

			assertThat(identity.id(), is(Optional.of(key.getPublicKey())));
		}
	}

	@Example
	@SneakyThrows
	public void shouldLoadAFileWithWindowsLineEndingsAndNoise() {
		final Signature key = new Signature();
		final String content = "# my gridnode\r\n\r\nPrivate Key:   " + key.getPrivateKey() + "  \r\n\r\n"
			+ "Public Key: " + key.getPublicKey() + "\r\n\r\n";

		try (FileSystem fs = posixFileSystem()) {
			assertThat(GridnodeIdentity.load(write(fs, content, OWNER_ONLY)).id(),
				is(Optional.of(key.getPublicKey())));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAFileOthersCanRead() {
		try (FileSystem fs = posixFileSystem()) {
			assertThat(messageOfLoading(write(fs, printed(new Signature()), "rw-r-----")),
				containsString("owner only"));
			assertThat(messageOfLoading(write(fs, printed(new Signature()), "rw----r--")),
				containsString("owner only"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAMissingFile() {
		try (FileSystem fs = posixFileSystem()) {
			assertThat(messageOfLoading(fs.getPath("/nowhere.key")), containsString("/nowhere.key"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAFileWithoutBothKeys() {
		final Signature key = new Signature();

		try (FileSystem fs = posixFileSystem()) {
			assertThat(messageOfLoading(write(fs, "Public Key: " + key.getPublicKey(), OWNER_ONLY)),
				containsString("Private Key:"));
			assertThat(messageOfLoading(write(fs, "Private Key: " + key.getPrivateKey(), OWNER_ONLY)),
				containsString("Public Key:"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseKeysThatAreNotAPairWithoutRevealingThem() {
		final Signature key = new Signature();
		final Signature other = new Signature();
		final String content = "Private Key: " + key.getPrivateKey() + "\nPublic Key: " + other.getPublicKey();

		try (FileSystem fs = posixFileSystem()) {
			final String message = messageOfLoading(write(fs, content, OWNER_ONLY));

			assertThat(message, containsString("do not match"));
			assertThat(message, not(containsString(key.getPrivateKey())));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseMalformedKeys() {
		try (FileSystem fs = posixFileSystem()) {
			assertThat(messageOfLoading(write(fs, "Private Key: nothex\nPublic Key: nothex", OWNER_ONLY)),
				containsString("gridnode.key"));
		}
	}

	@Example
	public void shouldHaveNoIdAndNoSignatureWithoutAKey() {
		final GridnodeIdentity none = GridnodeIdentity.none();

		assertThat(none.id(), is(Optional.empty()));
		assertThat(messageOfSigning(none), containsString("no gridnode key"));
	}

	@Example
	public void shouldSignEntriesThatVerify() {
		final GridnodeIdentity identity = GridnodeIdentity.of(newKey());
		final Gridnode entry = identity.sign(Gridnode.Status.ACTIVE, "127.0.0.1:5000", 1_000, 0);

		assertThat(entry.getId(), is(identity.id().get()));
		assertThat(entry.getTimestamp(), is(1_000L));
		assertThat(GridnodeSignature.verifies(entry), is(true));
	}

	@Example
	public void shouldNeverSignAnEarlierTimeThanBefore() {
		final GridnodeIdentity identity = GridnodeIdentity.of(newKey());

		final long first = identity.sign(Gridnode.Status.ACTIVE, "h:1", 5_000, 0).getTimestamp();
		final long afterClockWentBack = identity.sign(Gridnode.Status.ACTIVE, "h:1", 1_000, 0).getTimestamp();

		assertThat(afterClockWentBack, greaterThan(first));
	}

	@Example
	public void shouldBeatTheTimeOthersStillHold() {
		final GridnodeIdentity identity = GridnodeIdentity.of(newKey());

		assertThat(identity.sign(Gridnode.Status.ACTIVE, "h:1", 1_000, 9_000).getTimestamp(), is(9_001L));
	}
}
