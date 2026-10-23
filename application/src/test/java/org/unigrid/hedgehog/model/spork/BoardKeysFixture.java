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

package org.unigrid.hedgehog.model.spork;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;

/* Board keys for the tests: a pool of keys in the order of their public keys, of which the release builds in those a
   test names, and changes to them approved by the keys a test names */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BoardKeysFixture {
	public static final List<Signature> KEYS = keys(8);

	@SneakyThrows
	private static List<Signature> keys(int count) {
		final List<Signature> keys = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			keys.add(new Signature());
		}

		keys.sort(Comparator.comparing(Signature::getPublicKey));
		return List.copyOf(keys);
	}

	public static Signature key(int index) {
		return KEYS.get(index);
	}

	public static List<Signature> keys(int... indices) {
		return Arrays.stream(indices).mapToObj(KEYS::get).toList();
	}

	public static String[] publicKeysOf(Collection<Signature> keys) {
		return keys.stream().map(Signature::getPublicKey).sorted().toArray(String[]::new);
	}

	/* The keys that NetworkKey answers as built into the release, for as long as the test runs */
	public static void buildIn(List<Signature> keys) {
		final String[] builtIn = publicKeysOf(keys);

		new MockUp<NetworkKey>() {
			@Mock public String[] getBuiltInKeys() {
				return builtIn;
			}
		};
	}

	public static byte[] rootOf(List<Signature> builtIn) {
		return BoardKeyChain.root(Network.BOARD_KEYS_EPOCH, List.of(publicKeysOf(builtIn)));
	}

	/* The next change of the chain, to the keys given and approved by the approvers given, made now */
	public static KeyChange change(byte[] root, List<KeyChange> before, Collection<Signature> active,
		Collection<Signature> approvers) {

		final KeyChange change = new KeyChange(before.size(), Instant.now().truncatedTo(ChronoUnit.MILLIS),
			BoardKeyChain.headOf(root, before), new ArrayList<>(List.of(publicKeysOf(active))), new ArrayList<>());

		approve(change, approvers);
		return change;
	}

	@SneakyThrows
	public static void approve(KeyChange change, Collection<Signature> approvers) {
		for (Signature approver : approvers.stream().sorted(Comparator.comparing(Signature::getPublicKey)).toList()) {
			change.getApprovals().add(new KeyChange.Approval(approver.getPublicKey(),
				approver.sign(change.getSignable())));
		}

		change.getApprovals().sort(Comparator.comparing(KeyChange.Approval::getKey));
	}

	public static BoardKeys.SporkData chainOf(KeyChange... changes) {
		final BoardKeys.SporkData data = new BoardKeys.SporkData();

		data.getChanges().addAll(List.of(changes));
		return data;
	}

	/* A first version of the spork with this chain, signed by the two keys given */
	@SneakyThrows
	public static BoardKeys signed(BoardKeys.SporkData chain, Signature signer, Signature cosigner) {
		final BoardKeys spork = (BoardKeys) GridSpork.firstVersionOf(withChain(chain));

		spork.sign(signer.getPrivateKey());
		spork.cosign(cosigner.getPrivateKey());
		return spork;
	}

	private static BoardKeys withChain(BoardKeys.SporkData chain) {
		final BoardKeys spork = new BoardKeys();

		spork.setTimeStamp(Instant.now().minusSeconds(1));
		spork.setData(chain);
		return spork;
	}
}
