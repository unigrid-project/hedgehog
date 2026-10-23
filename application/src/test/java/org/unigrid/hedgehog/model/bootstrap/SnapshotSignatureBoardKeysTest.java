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

package org.unigrid.hedgehog.model.bootstrap;

import java.nio.file.Path;
import java.util.List;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;

/* A snapshot is signed history, as an entry of a signature log is: a key that signed it and that the board switched off
   since still vouches for it, and the ledger pins it by its genesis anyway. Opening a snapshot refuses it only when
   this status is INVALID; the snapshot here holds a header alone, as a file in memory cannot be mapped. The release
   builds in keys 0 and 1, and the board switches key 0 off. */
public class SnapshotSignatureBoardKeysTest {
	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	@SneakyThrows
	private static Path signedBy(Signature key) {
		final Path snapshot = SyntheticSnapshots.inMemory(SyntheticSnapshots.builder()
			.transactionTableOffset(SnapshotFormat.HEADER_SIZE).build());

		SnapshotSignature.signAndAppend(snapshot, key.getPrivateKey());
		return snapshot;
	}

	private static void switchKey0Off() {
		NetworkKey.install(NetworkKey.getActiveKeys().after(List.of(publicKeysOf(keys(1, 2)))));
	}

	@Example
	@SneakyThrows
	public void shouldStillTakeASnapshotThatABuiltInKeySignedOnceTheBoardSwitchesItOff() {
		final Path snapshot = signedBy(key(0));

		assertThat(SnapshotSignature.read(snapshot).getStatus(), equalTo(SignatureStatus.SIGNED));
		switchKey0Off();
		assertThat(SnapshotSignature.read(snapshot).getStatus(), equalTo(SignatureStatus.SIGNED));
	}

	@Example
	@SneakyThrows
	public void shouldTakeASnapshotThatAKeyTheBoardAddedSigned() {
		final Path snapshot = signedBy(key(2));

		switchKey0Off();
		assertThat(SnapshotSignature.read(snapshot).getStatus(), equalTo(SignatureStatus.SIGNED));
	}

	@Example
	@SneakyThrows
	public void shouldStillRefuseASnapshotThatNoBoardKeyEverSigned() {
		final Path snapshot = signedBy(key(5));

		switchKey0Off();
		assertThat(SnapshotSignature.read(snapshot).getStatus(), equalTo(SignatureStatus.INVALID));
	}
}
