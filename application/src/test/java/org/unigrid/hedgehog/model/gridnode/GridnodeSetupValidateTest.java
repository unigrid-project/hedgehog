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
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.command.option.GridnodeOptions;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.InMemoryDataDirectoryMockUp;
import org.unigrid.hedgehog.model.crypto.Signature;

/* What the daemon checks before it starts, with the data directory and the gridnode key on in-memory file systems */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class GridnodeSetupValidateTest {
	private static final String OWNER_ONLY = "rw-------";
	private static final String ANNOUNCED = "127.0.0.1:5000";
	private static final String SEALING_FILE = "/data/" + SealingKeyFile.FILE;
	private static final String OLD_SEALING_KEY = "ab".repeat(SealingKeyFile.KEY_SIZE);

	/* Generating a key pair takes many tries, so one is shared; validation does not care whose it is */
	private static final Signature KEY = key();

	@SneakyThrows
	private static Signature key() {
		return new Signature();
	}

	private static FileSystem posixFileSystem() {
		return Jimfs.newFileSystem(Configuration.unix().toBuilder()
			.setAttributeViews("basic", "owner", "posix", "unix").build());
	}

	@SneakyThrows
	private static Path write(FileSystem fs, String name, String content, String permissions) {
		final Path file = fs.getPath(name);

		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
		return file;
	}

	private static Path gridnodeKeyIn(FileSystem fs) {
		return write(fs, "/keys/gridnode.key",
			"Private Key: " + KEY.getPrivateKey() + "\nPublic Key: " + KEY.getPublicKey() + "\n", OWNER_ONLY);
	}

	/* The gridnode is started with -G and a data directory that is in memory */
	private static void startGridnode(FileSystem fs, Path gridnodeKey) {
		new InMemoryDataDirectoryMockUp(fs);
		new MockUp<GridnodeOptions>() {
			@Mock public /* static */ Path getGridnodeKeyFile() {
				return gridnodeKey;
			}

			@Mock public /* static */ String getAnnounceAddress() {
				return ANNOUNCED;
			}
		};
	}

	@Example
	@SneakyThrows
	public void makesTheSealingKeyWhenThereIsNone() {
		try (FileSystem fs = posixFileSystem()) {
			startGridnode(fs, gridnodeKeyIn(fs));

			GridnodeSetup.validate();

			final Path file = fs.getPath(SEALING_FILE);

			assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), equalTo(OWNER_ONLY));
			assertThat(SealingKeyFile.loadOrCreate(file).length, is(SealingKeyFile.KEY_SIZE));
		}
	}

	@Example
	@SneakyThrows
	public void keepsTheSealingKeyThatIsThere() {
		try (FileSystem fs = posixFileSystem()) {
			final Path file = write(fs, SEALING_FILE, OLD_SEALING_KEY + "\n", OWNER_ONLY);

			startGridnode(fs, gridnodeKeyIn(fs));
			GridnodeSetup.validate();

			assertThat(Files.readString(file), equalTo(OLD_SEALING_KEY + "\n"));
		}
	}

	@Example
	@SneakyThrows
	public void refusesASealingKeyThatOthersCanRead() {
		try (FileSystem fs = posixFileSystem()) {
			write(fs, SEALING_FILE, OLD_SEALING_KEY + "\n", "rw-r--r--");
			startGridnode(fs, gridnodeKeyIn(fs));

			final String message = assertThrows(IllegalArgumentException.class, GridnodeSetup::validate)
				.getMessage();

			assertThat(message, containsString(SEALING_FILE));
			assertThat(message, containsString("chmod 600"));
			assertThat(message, not(containsString(OLD_SEALING_KEY)));
		}
	}

	@Example
	@SneakyThrows
	public void refusesASealingKeyThatIsNoKey() {
		try (FileSystem fs = posixFileSystem()) {
			write(fs, SEALING_FILE, "not a key\n", OWNER_ONLY);
			startGridnode(fs, gridnodeKeyIn(fs));

			assertThat(assertThrows(IllegalArgumentException.class, GridnodeSetup::validate).getMessage(),
				containsString(SEALING_FILE));
		}
	}

	@Example
	@SneakyThrows
	public void makesNoSealingKeyWithoutAGridnodeKey() {
		try (FileSystem fs = posixFileSystem()) {
			startGridnode(fs, null);

			GridnodeSetup.validate();

			assertThat(Files.exists(fs.getPath(SEALING_FILE)), is(false));
		}
	}
}
