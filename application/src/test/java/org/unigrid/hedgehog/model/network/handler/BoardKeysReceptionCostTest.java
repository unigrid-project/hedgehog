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

package org.unigrid.hedgehog.model.network.handler;

import java.security.GeneralSecurityException;
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
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.crypto.VerifySignatureException;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signedAnyway;

/* Anyone can send a frame, and a spork of the board keys can carry a chain of many approvals, so what one forged spork
   costs a node to turn down is bounded. The release builds in keys 6 and 7, which sort after the keys that a change
   adds, so a forged approval of the keys before it is the last one in its change. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysReceptionCostTest {
	private static final byte[] ROOT = rootOf(keys(6, 7));

	private static int checks;

	private final PublishSporkChannelHandler handler = new PublishSporkChannelHandler();
	private final SporkDatabase database = SporkDatabase.builder().build();
	private final PendingSporks pending = new PendingSporks();

	@BeforeTry
	public void before() {
		buildIn(keys(6, 7));
		checks = 0;

		/* A head and a log are checked by their digests, a change of the board keys by what its approvals sign. Each
		   check is made here, by a key of its own, as a fake that handed it on would run into itself. */
		new MockUp<Signature>() {
			@Mock @SneakyThrows boolean verifyDigest(byte[] digest, byte[] signature, String key) {
				checks++;
				return new Signature(Optional.empty(), Optional.of(key)).verifyDigest(digest, signature);
			}

			@Mock boolean isSignedBy(String key, byte[] data, byte[] signature) {
				checks++;

				try {
					return new Signature(Optional.empty(), Optional.of(key)).verify(data, signature);
				} catch (GeneralSecurityException | VerifySignatureException | RuntimeException ex) {
					return false;
				}
			}
		};
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	/* A peer of its own for each spork, so that every one is given its turn */
	private int checksToReceive(GridSpork spork, String peer) {
		final int before = checks;

		assertThat(handler.receive(SerializationUtils.clone(spork), database, pending, new SporkLag(), peer), is(false));
		return checks - before;
	}

	/* A change that adds keys 0 to 5, which approve it, while the approvals of keys 6 and 7 are forged */
	@SneakyThrows
	private static KeyChange forgedChange() {
		final KeyChange change = change(ROOT, List.of(), keys(0, 1, 2, 3, 4, 5, 6, 7), keys(0, 1, 2, 3, 4, 5));
		final byte[] signable = change.getSignable();

		for (int forged : List.of(6, 7)) {
			change.getApprovals().add(new KeyChange.Approval(key(forged).getPublicKey(),
				key(0).signInOneForm(signable)));
		}

		return change;
	}

	@Example
	public void shouldTurnDownAForgedChainAtItsFirstForgedApprovalAndOnlyOnce() {
		final BoardKeys forged = signedAnyway(chainOf(forgedChange()), key(0), key(1));

		assertThat(checksToReceive(forged, "first"), is(1));
		assertThat(checksToReceive(forged, "second"), is(0));
	}

	/* The chain holds, and two keys that it does not make active signed the head */
	@Example
	public void shouldCheckTheHeadOfAForgedSporkOnceForEachKey() {
		final KeyChange adding = change(ROOT, List.of(), keys(0, 6, 7), keys(0, 6, 7));
		final BoardKeys forged = signedAnyway(chainOf(adding), key(4), key(5));
		final int approvals = adding.getApprovals().size();

		assertThat(checksToReceive(forged, "first"), lessThanOrEqualTo(approvals + 2 * adding.getActive().size()));
		assertThat(checksToReceive(forged, "second"), lessThanOrEqualTo(2 * adding.getActive().size()));
	}
}
