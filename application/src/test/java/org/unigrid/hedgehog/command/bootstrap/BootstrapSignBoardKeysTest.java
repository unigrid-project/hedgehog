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

package org.unigrid.hedgehog.command.bootstrap;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.bootstrap.SignatureStatus;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDigest;
import org.unigrid.hedgehog.model.bootstrap.SnapshotInspector;
import org.unigrid.hedgehog.model.bootstrap.SnapshotSignature;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.BoardKeysRecord;
import org.unigrid.hedgehog.model.spork.BoardKeysRecordFixture;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import picocli.CommandLine;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.asReleased;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.shipping;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;

/* A snapshot is signed by a board key, and the board keys of a release are those of the chain that it ships, not the
   keys built into it: a key that the chain adds signs, and one that it switches off does not. The release builds in
   keys 0 and 1 and ships the change to keys 1 and 2. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BootstrapSignBoardKeysTest {
	private static final byte[] CONTENT = { 1, 2, 3, 4 };
	private static final byte[] ROOT = rootOf(keys(0, 1));

	private final List<String> signedWith = new ArrayList<>();
	private KeyChange shipped;

	@BeforeTry
	@SneakyThrows
	public void before() {
		final Path snapshot = BootstrapCli.snapshotInMemory();
		final SnapshotSignature unsigned = BootstrapCli.withoutConstructor(SnapshotSignature.class);

		buildIn(keys(0, 1));
		shipped = change(ROOT, List.of(), keys(1, 2), keys(0, 1, 2));
		shipping(List.of(shipped), true);
		BoardKeysRecordFixture.inMemory();
		Files.createDirectories(snapshot.getParent());
		Files.write(snapshot, CONTENT);

		new MockUp<SnapshotInspector>() {
			@Mock public /* static */ SignatureStatus validate(Path path) {
				return SignatureStatus.UNSIGNED;
			}
		};

		new MockUp<SnapshotDigest>() {
			@Mock public /* static */ long contentLengthOf(Path path) {
				return CONTENT.length;
			}
		};

		new MockUp<SnapshotSignature>() {
			@Mock public /* static */ SnapshotSignature read(Path path) {
				return unsigned;
			}

			@Mock public boolean isPresent() {
				return false;
			}

			@Mock public /* static */ void signAndAppend(Path path, String privateKeyHex) {
				signedWith.add(privateKeyHex);
			}
		};
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
		asReleased();
	}

	private static HedgehogCli.Result signWith(Signature signer) {
		return BootstrapCli.run("sign", "-k", signer.getPrivateKey());
	}

	@Example
	public void shouldSignWithAKeyThatTheChainOfTheReleaseAdds() {
		final HedgehogCli.Result result = signWith(key(2));

		assertThat(result.err(), result.exitCode(), equalTo(0));
		assertThat(signedWith, hasSize(1));
	}

	@Example
	public void shouldSignWithNoKeyThatTheChainOfTheReleaseSwitchesOff() {
		final HedgehogCli.Result result = signWith(key(0));

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("not one the network trusts"));
		assertThat(signedWith, empty());
	}

	/* The chain that this machine verified before, which goes on from the one the release ships */
	@SneakyThrows
	private static void recorded(List<KeyChange> chain) {
		BoardKeysRecord.ofThisMachine().keep(Optional.of(chain), "a test");
	}

	/* The board changes its keys after the release, which this machine verified: a key that it added signs, and one
	   that it switched off does not */
	@Example
	public void shouldSignWithAKeyThatTheBoardAddedSinceTheReleaseAsThisMachineVerified() {
		recorded(List.of(shipped, change(ROOT, List.of(shipped), keys(1, 2, 3), keys(1, 2, 3))));

		final HedgehogCli.Result result = signWith(key(3));

		assertThat(result.err(), result.exitCode(), equalTo(0));
		assertThat(signedWith, hasSize(1));
	}

	@Example
	public void shouldSignWithNoKeyThatTheBoardSwitchedOffSinceTheReleaseAsThisMachineVerified() {
		recorded(List.of(shipped, change(ROOT, List.of(shipped), keys(2, 3), keys(1, 2, 3))));

		final HedgehogCli.Result result = signWith(key(1));

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("not one the network trusts"));
		assertThat(signedWith, empty());
	}

	/* A spork database given is gone by as the util commands go by one: its chain of board keys */
	@Example
	@SneakyThrows
	public void shouldSignWithAKeyThatTheBoardKeysOfASporkDatabaseGivenAdd() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final KeyChange adding = change(ROOT, List.of(shipped), keys(1, 2, 3), keys(1, 2, 3));
			final SporkDatabase database = SporkDatabase.builder().build();

			database.set(signed(chainOf(shipped, adding), key(1), key(2)));
			SporkDatabase.persist(fs.getPath("/spork.db"), database);

			final HedgehogCli.Result result = HedgehogCli.run(() -> new CommandLine(Hedgehog.class)
				.registerConverter(Path.class, fs::getPath), "bootstrap", "sign", "-k", key(3).getPrivateKey(),
				"--spork-db", "/spork.db");

			assertThat(result.err(), result.exitCode(), equalTo(0));
			assertThat(signedWith, hasSize(1));
		}
	}
}
