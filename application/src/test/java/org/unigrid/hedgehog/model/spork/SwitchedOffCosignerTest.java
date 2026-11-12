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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.transport.handler.PublishSporkChannelHandler;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signedAnyway;

/* The release builds in keys 0, 1 and 2. Key 0 proposes a version of the mint supply, and keys 1 and 2 both co-sign
   it: one version in two copies, of which a node keeps the one that it took first. Then the board switches key 2 off,
   and the copy that it co-signed counts for nothing on the nodes that hold it, while the copy that every other node
   sends them is no newer than theirs. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class SwitchedOffCosignerTest {
	private static final String PEER = "198.51.100.7";
	private static final String OTHER_PEER = "203.0.113.9";

	/* What the bucket of an inbound peer holds */
	private static final int SHARE = VerifySlots.shareOf(SporkLag.Pool.OTHER);

	private static final byte[] ROOT = rootOf(keys(0, 1, 2));
	private static final KeyChange SWITCHING_OFF = change(ROOT, List.of(), keys(0, 1), keys(0, 1));
	private static final KeyChange SWITCHING_BOTH_OFF = change(ROOT, List.of(), keys(1, 3), keys(0, 1, 3));
	private static final Instant MADE_AT = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(3600);

	private final PublishSporkChannelHandler handler = new PublishSporkChannelHandler();
	private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
	private GridSpork base;
	private GridSpork proposal;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1, 2));
		base = signedBy(proposalOver(null, 10, MADE_AT.minusSeconds(1)), 0, 1);
		proposal = proposalOver(base, 20, MADE_AT);
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	/* A node with what it holds, as a peer reaches it. The board keys are those of the process, so the nodes of a test
	   go by one board, and what a node takes before it hears of a change is sent to it before any node does. The
	   clock of its lag stands still, so that what the bucket of a peer holds tells what the peer paid. */
	private final class Node {
		private final SporkDatabase database = SporkDatabase.builder().build();
		private final PendingSporks pending = new PendingSporks();
		private final SporkLag lag = new SporkLag(() -> now);

		private Node(GridSpork... stored) {
			for (GridSpork spork : stored) {
				database.set(SerializationUtils.clone(spork));
			}
		}

		/* With a lag of its own, so that a spork refused costs no turn of the next */
		private boolean receive(GridSpork spork) {
			return handler.receive(SporkWire.decode(SporkWire.encode(spork)), database, pending, new SporkLag(),
				PEER);
		}

		/* What the peer paid for the checks of a version of the mint supply that it sent, of what its bucket in the
		   lag of this node holds */
		private double paidBy(String peer, GridSpork spork) {
			final double before = lag.left(Type.MINT_SUPPLY, peer);

			handler.receive(SporkWire.decode(SporkWire.encode(spork)), database, pending, lag, peer);
			return before - lag.left(Type.MINT_SUPPLY, peer);
		}

		private GridSpork stored() {
			return database.get(Type.MINT_SUPPLY);
		}

		/* The bytes of both signatures, which no version is told by */
		private boolean holds(GridSpork copy) {
			return Arrays.equals(stored().getSignature(), copy.getSignature())
				&& Arrays.equals(stored().getCosignature(), copy.getCosignature());
		}
	}

	private static MintSupply.SporkData supplyOf(long maxSupply) {
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.valueOf(maxSupply));
		return data;
	}

	/* The next version as the command line of the holder of key 0 makes it, at the time given */
	@SneakyThrows
	private static GridSpork proposalOver(GridSpork stored, long maxSupply, Instant at) {
		final GridSpork spork = GridSpork.nextVersion(Type.MINT_SUPPLY, stored, Optional.empty());

		spork.setData(supplyOf(maxSupply));
		spork.setTimeStamp(at);
		spork.sign(key(0).getPrivateKey());
		return spork;
	}

	/* A copy of the version with the signatures of the two keys given, as whoever holds them writes them over what
	   the version signs, whether or not the board has them active, in the one form that every type takes. Making the
	   copy checks no signature, so a node checks each of them as one that it has not seen. */
	@SneakyThrows
	private static <S extends GridSpork> S signedBy(S version, int signer, int cosigner) {
		final S copy = SerializationUtils.clone(version);

		copy.setSignature(key(signer).signInOneForm(copy.getSignable()));
		copy.setCosignature(key(cosigner).signInOneForm(copy.getSignable()));
		return copy;
	}

	/* A copy of the version that its proposer signed, with the co-signature of the key given, each one of other bytes */
	@SneakyThrows
	private static GridSpork cosigned(GridSpork version, int cosigner) {
		final GridSpork copy = SerializationUtils.clone(version);

		copy.setCosignature(key(cosigner).sign(copy.getSignable()));
		return copy;
	}

	/* The change of the board keys as each node takes it from a peer, signed by two keys that it leaves active */
	private static void take(KeyChange change, int signer, int cosigner, Node... nodes) {
		final BoardKeys boardKeys = signed(chainOf(change), key(signer), key(cosigner));

		for (Node node : nodes) {
			assertThat(node.receive(boardKeys), is(true));
		}
	}

	/* Each node sends the head that it holds again and again, which counts on both until the change. After it the node
	   in the east sends nothing of the mint supply, as what it holds counts for nothing, and the node in the west
	   sends on the copy that keys 0 and 1 signed. The node in the east takes that once, so it sends it on once. */
	@Example
	public void shouldTakeTheCopyThatCountsInPlaceOfOneWhoseCosignerTheBoardSwitchedOff() {
		final GridSpork byKey1 = cosigned(proposal, 1);
		final GridSpork byKey2 = cosigned(proposal, 2);
		final Node west = new Node(base);
		final Node east = new Node(base);

		assertThat(byKey2.getSignable(), equalTo(byKey1.getSignable()));
		assertThat(List.of(west.receive(byKey1), east.receive(byKey2)), equalTo(List.of(true, true)));
		assertThat(List.of(west.receive(byKey2), east.receive(byKey1)), equalTo(List.of(false, false)));
		assertThat(List.of(west.holds(byKey1), east.holds(byKey2)), equalTo(List.of(true, true)));

		take(SWITCHING_OFF, 0, 1, west, east);
		assertThat(List.of(west.stored().isCounting(), east.stored().isCounting()), equalTo(List.of(true, false)));

		assertThat(west.receive(byKey2), is(false));
		assertThat(east.receive(byKey1), is(true));
		assertThat(List.of(east.stored().isCounting(), east.holds(byKey1)), equalTo(List.of(true, true)));
		assertThat(List.of(east.receive(byKey1), east.receive(byKey2)), equalTo(List.of(false, false)));
		assertThat(List.of(west.holds(byKey1), east.holds(byKey1)), equalTo(List.of(true, true)));
	}

	/* A node that is new to the network goes by the board keys of its release, where key 2 is active still. Whoever
	   holds that key writes its signature of the head that the network holds in the place of the co-signature, and
	   hands the node that copy before the node hears of the change. */
	@Example
	public void shouldTakeTheHeadOfTheNetworkInPlaceOfACopyThatAKeySwitchedOffSinceCosigned() {
		final GridSpork head = cosigned(proposal, 1);
		final GridSpork copy = cosigned(head, 2);
		final Node node = new Node();

		assertThat(node.receive(copy), is(true));
		assertThat(node.receive(head), is(false));

		take(SWITCHING_OFF, 0, 1, node);
		assertThat(node.stored().isCounting(), is(false));

		assertThat(node.receive(head), is(true));
		assertThat(List.of(node.stored().isCounting(), node.holds(head)), equalTo(List.of(true, true)));
		assertThat(node.receive(copy), is(false));
		assertThat(node.holds(head), is(true));
	}

	/* The board can switch off both keys that signed a copy at once, here keys 0 and 2 for key 3, and that copy then
	   holds no version back either. Two keys that are active sign what the version signs. */
	@Example
	public void shouldTakeTheCopyThatCountsInPlaceOfOneThatNoActiveKeySigned() {
		final GridSpork byKeys1And3 = signedBy(proposal, 1, 3);
		final Node node = new Node(base);

		assertThat(node.receive(cosigned(proposal, 2)), is(true));
		take(SWITCHING_BOTH_OFF, 1, 3, node);
		assertThat(List.of(node.stored().isAnchoring(), node.stored().isCounting()), equalTo(List.of(false, false)));

		assertThat(node.receive(byKeys1And3), is(true));
		assertThat(List.of(node.stored().isCounting(), node.holds(byKeys1And3)), equalTo(List.of(true, true)));
	}

	/* Only the version that the node holds, with the signatures of two different keys that are active, takes the
	   place of the copy that counts for nothing: not a copy that a key which never was of the board co-signed, nor
	   one that key 0 signed twice, nor the proposal, nor another version made at the same time, which two active
	   keys signed */
	@Example
	public void shouldKeepTheCopyThatDoesNotCountUntilACopyOfItThatCountsComes() {
		final GridSpork byKey2 = cosigned(proposal, 2);
		final GridSpork otherData = cosigned(proposalOver(base, 30, MADE_AT), 1);
		final Node node = new Node(base);

		assertThat(node.receive(byKey2), is(true));
		take(SWITCHING_OFF, 0, 1, node);
		assertThat(node.stored().isCounting(), is(false));

		for (GridSpork refused : List.of(cosigned(proposal, 5), cosigned(proposal, 0), proposal, otherData, byKey2)) {
			assertThat(node.receive(refused), is(false));
		}

		assertThat(node.pending.list(), empty());
		assertThat(List.of(node.stored().isCounting(), node.holds(byKey2)), equalTo(List.of(false, true)));
	}

	/* The board keys follow their chain alone, so a copy of the version of them that a node holds takes no place. Key
	   2 co-signed that version, which its own chain switches off, as a file alone can hold it. */
	@Example
	public void shouldTakeNoCopyOfTheBoardKeysInPlaceOfTheStoredOnes() {
		final BoardKeys held = signedAnyway(chainOf(SWITCHING_OFF), key(0), key(2));
		final BoardKeys copy = signedBy(held, 0, 1);
		final Node node = new Node(held);

		assertThat(copy.getSignable(), equalTo(held.getSignable()));
		assertThat(List.of(held.isCounting(), copy.isCounting()), equalTo(List.of(false, true)));
		assertThat(copy.canReplace(held), is(false));
		assertThat(node.receive(copy), is(false));
		assertThat(node.database.getBoardKeys().getCosignature(), equalTo(held.getCosignature()));
	}

	/* Every peer sends the head that it holds again every few minutes, so a copy of the version that a node holds
	   costs its sender nothing where the stored copy counts, whoever signed it, and neither does a version of another
	   time where it does not. There a copy of the stored version costs its sender the check of the co-signature, as
	   the signature of key 0 is one that the node checked before. A peer that sends copies which do not count spends
	   its own checks, so that the copy which counts is not checked for it once they are spent. The next peer is given
	   back what the check of that copy cost it. */
	@Example
	public void shouldChargeTheSenderOfACopyOnlyWhereTheStoredCopyDoesNotCount() {
		final GridSpork byKey2 = cosigned(proposal, 2);
		final GridSpork byAStranger = cosigned(proposal, 5);
		final Node west = new Node(base);
		final Node east = new Node(base);

		assertThat(List.of(west.receive(cosigned(proposal, 1)), east.receive(byKey2)), equalTo(List.of(true, true)));
		take(SWITCHING_OFF, 0, 1, west, east);
		assertThat(List.of(west.stored().isCounting(), east.stored().isCounting()), equalTo(List.of(true, false)));

		assertThat(List.of(west.paidBy(PEER, byKey2), west.paidBy(PEER, byAStranger)), equalTo(List.of(0.0, 0.0)));
		assertThat(east.paidBy(PEER, base), is(0.0));
		assertThat(List.of(east.paidBy(PEER, byKey2), east.paidBy(PEER, byAStranger)), equalTo(List.of(1.0, 1.0)));

		IntStream.range(0, SHARE).forEach(n -> east.paidBy(PEER, byAStranger));
		assertThat(east.lag.left(Type.MINT_SUPPLY, PEER), is(0.0));
		assertThat(east.paidBy(PEER, cosigned(proposal, 1)), is(0.0));
		assertThat(east.stored().isCounting(), is(false));

		assertThat(east.paidBy(OTHER_PEER, cosigned(proposal, 1)), is(0.0));
		assertThat(east.stored().isCounting(), is(true));
	}
}
