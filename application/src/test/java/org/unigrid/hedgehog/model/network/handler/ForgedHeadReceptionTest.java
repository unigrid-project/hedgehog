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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;

/* A node that a board member reads from may hand out a forged stored spork in place of the true head: signed once by
   a board key, in the form of a build from before co-signatures, and carrying the log of the true head. The command
   line no longer takes such a head for one the network took, and a version made over it all the same carries that log
   and adds an entry of the forgery, which a current key signed, so every peer that holds the true head would take it
   but for the head itself: the entry that follows the stored log has to be the stored spork. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class ForgedHeadReceptionTest {
	private static final String PEER = "198.51.100.7";
	private static final Instant FIRST_MADE_AT = Instant.now().truncatedTo(ChronoUnit.MILLIS)
		.minus(PendingSporks.LIFETIME.multipliedBy(3));

	private static final Signature FIRST = keyPair();
	private static final Signature SECOND = keyPair();

	private final PublishSporkChannelHandler handler = new PublishSporkChannelHandler();

	@SneakyThrows
	private static Signature keyPair() {
		return new Signature();
	}

	@BeforeTry
	public void fakeTheBoard() {
		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { FIRST.getPublicKey(), SECOND.getPublicKey() };
			}
		};
	}

	/* A node with what it holds, as a peer reaches it. Each spork comes with a lag of its own, so that one refused
	   costs no turn of the next. */
	private final class Node {
		private final SporkDatabase database = SporkDatabase.builder().build();
		private final PendingSporks pending = new PendingSporks();

		private Node(GridSpork... stored) {
			for (GridSpork spork : stored) {
				database.set(SerializationUtils.clone(spork));
			}
		}

		private boolean receive(GridSpork spork) {
			return handler.receive(SporkWire.decode(SporkWire.encode(spork)), database, pending, new SporkLag(),
				PEER);
		}

		private GridSpork stored() {
			return database.get(Type.MINT_SUPPLY);
		}
	}

	private static MintSupply.SporkData supplyOf(long maxSupply) {
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.valueOf(maxSupply));
		return data;
	}

	/* What a build from before co-signatures stored: signed once, over its Java serialization */
	@SneakyThrows
	private static GridSpork ofAnEarlierBuild(GridSpork spork, Signature signer) {
		spork.setFlags((short) 0);
		spork.setCosignature(null);
		spork.setSignature(signer.sign(spork.getSignable()));
		return spork;
	}

	private static GridSpork firstVersion() {
		return cosigned(proposalOver(null, 10, FIRST_MADE_AT));
	}

	/* The next version as the command line makes it, at the time given or else now */
	@SneakyThrows
	private static GridSpork proposalOver(GridSpork stored, long maxSupply, Instant at) {
		final GridSpork spork = GridSpork.nextVersion(Type.MINT_SUPPLY, stored, Optional.empty());

		spork.setData(supplyOf(maxSupply));
		Optional.ofNullable(at).ifPresent(spork::setTimeStamp);
		spork.sign(FIRST.getPrivateKey());
		return spork;
	}

	private static GridSpork proposalOver(GridSpork stored, long maxSupply) {
		return proposalOver(stored, maxSupply, null);
	}

	@SneakyThrows
	private static GridSpork cosigned(GridSpork proposal) {
		final GridSpork spork = SerializationUtils.clone(proposal);

		spork.cosign(SECOND.getPrivateKey());
		return spork;
	}

	/* The versions the network took, one over the other */
	private static List<GridSpork> versions(int count) {
		final List<GridSpork> versions = new ArrayList<>(List.of(firstVersion()));

		while (versions.size() < count) {
			versions.add(cosigned(proposalOver(versions.getLast(), 10L * versions.size())));
		}

		return versions;
	}

	/* The version after the head as a node that is not honest hands it out */
	private static GridSpork forgedOver(GridSpork head) {
		final GridSpork forged = SerializationUtils.clone(head);

		forged.setData(supplyOf(1_000_000_000));
		forged.setTimeStamp(head.getTimeStamp().plusMillis(1));
		return ofAnEarlierBuild(forged, FIRST);
	}

	@Example
	public void shouldRefuseAProposalAndASporkWhoseLogGoesOnWithAForgedHead() {
		final GridSpork head = versions(3).getLast();
		final GridSpork forged = forgedOver(head);
		final GridSpork proposal = proposalOver(forged, 20);
		final Node node = new Node(head);

		assertThat(forged.isTakenByTheNetwork(), is(false));
		assertThat(node.receive(proposal), is(false));
		assertThat(node.pending.list(), empty());
		assertThat(node.receive(cosigned(proposal)), is(false));
		assertThat(node.stored().getSignable(), equalTo(head.getSignable()));
	}

	@Example
	public void shouldRefuseAForgedHeadThatSignsOverTheVersionBeforeTheStoredOne() {
		final List<GridSpork> versions = versions(3);
		final GridSpork proposal = proposalOver(forgedOver(versions.get(1)), 20);
		final Node node = new Node(versions.getLast());

		assertThat(node.receive(proposal), is(false));
		assertThat(node.receive(cosigned(proposal)), is(false));
		assertThat(node.stored().getSignable(), equalTo(versions.getLast().getSignable()));
	}

	@Example
	public void shouldTakeTheNextVersionOverTheStoredOne() {
		for (GridSpork head : versions(3)) {
			final GridSpork proposal = proposalOver(head, 99);
			final Node node = new Node(head);

			assertThat(node.receive(proposal), is(true));
			assertThat(node.pending.list(), contains(proposal));
			assertThat(node.receive(cosigned(proposal)), is(true));
			assertThat(node.stored().getSignable(), equalTo(proposal.getSignable()));
		}
	}

	@Example
	public void shouldCatchUpOverSeveralVersionsAtOnce() {
		final List<GridSpork> versions = versions(5);
		final GridSpork proposal = proposalOver(versions.getLast(), 99);

		for (GridSpork behind : versions.subList(0, versions.size() - 1)) {
			final Node node = new Node(behind);

			assertThat(node.receive(proposal), is(true));
			assertThat(node.receive(versions.getLast()), is(true));
			assertThat(node.stored().getSignable(), equalTo(versions.getLast().getSignable()));
		}
	}

	/* A node can as well hand out the version before the head, which the network did take. A version over it is a
	   sibling of the head that undoes its change, and once a proposal made along with the head could no longer be
	   waiting, no race between two co-signers explains it. */
	@Example
	public void shouldRefuseASiblingThatChangesTheStoredSporkOnceAProposalOfItsTimeIsGone() {
		final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		final GridSpork before = cosigned(proposalOver(firstVersion(), 20,
			now.minus(PendingSporks.LIFETIME.multipliedBy(2))));
		final GridSpork head = cosigned(proposalOver(before, 30, now.minus(PendingSporks.LIFETIME).minusSeconds(1)));
		final GridSpork rollback = proposalOver(before, 99, now);
		final Node node = new Node(head);

		assertThat(node.receive(rollback), is(false));
		assertThat(node.receive(cosigned(rollback)), is(false));
		assertThat(node.stored().getSignable(), equalTo(head.getSignable()));
	}

	/* Two board members can co-sign different proposals over one version on two nodes before either node hears of the
	   other. Every node takes the newer of them, as long as it was made within the lifetime of a proposal after the
	   one it replaces. */
	@Example
	public void shouldConvergeOnTheNewerOfTwoVersionsCosignedOverTheSameVersion() {
		final GridSpork base = versions(2).getLast();
		final GridSpork older = cosigned(proposalOver(base, 30, base.getTimeStamp().plusSeconds(1)));
		final GridSpork newer = cosigned(proposalOver(base, 40, older.getTimeStamp().plus(PendingSporks.LIFETIME)));
		final GridSpork tooLate = cosigned(proposalOver(base, 50, older.getTimeStamp().plus(PendingSporks.LIFETIME)
			.plusMillis(1)));

		for (List<GridSpork> arrivals : List.of(List.of(older, newer), List.of(newer, older))) {
			final Node node = new Node(base);

			arrivals.forEach(node::receive);
			assertThat(node.stored().getSignable(), equalTo(newer.getSignable()));
		}

		assertThat(new Node(older).receive(newer), is(true));
		assertThat(new Node(newer).receive(older), is(false));
		assertThat(new Node(older).receive(tooLate), is(false));
	}

	@SneakyThrows
	private static GridSpork renewalOf(GridSpork stored, Instant at) {
		final GridSpork renewal = SerializationUtils.clone(stored);

		renewal.renew();
		renewal.setTimeStamp(at);
		renewal.sign(FIRST.getPrivateKey());
		return cosigned(renewal);
	}

	/* Two versions that make the same change over the same version, as two renewals of it do, settle by time, however
	   far apart they were made */
	@Example
	public void shouldSettleSiblingsThatMakeTheSameChangeByTheirTimeStamps() {
		final GridSpork head = versions(2).getLast();
		final GridSpork older = renewalOf(head, head.getTimeStamp().plusMillis(1));
		final GridSpork newer = renewalOf(head, head.getTimeStamp().plusMillis(2));
		final GridSpork muchLater = renewalOf(head, older.getTimeStamp().plus(PendingSporks.LIFETIME.multipliedBy(2)));

		assertThat(new Node(older).receive(newer), is(true));
		assertThat(new Node(newer).receive(older), is(false));
		assertThat(new Node(older).receive(muchLater), is(true));
	}

	/* The board signs the data of a database again as first versions, which two board members can make on two
	   machines hours apart. There is no version before them that the later one could undo, so the newer wins. */
	@Example
	public void shouldSettleFirstVersionsMadeFarApartByTheirTimeStamps() {
		final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		final GridSpork older = cosigned(proposalOver(null, 10, now.minus(PendingSporks.LIFETIME.multipliedBy(3))));
		final GridSpork newer = cosigned(proposalOver(null, 20, now));

		assertThat(new Node(older).receive(newer), is(true));
		assertThat(new Node(newer).receive(older), is(false));
		assertThat(new Node(older).stored().getSignable(), equalTo(older.getSignable()));
	}

	@Example
	public void shouldTakeTheFirstVersionWhenNothingIsStored() {
		final Node node = new Node();
		final GridSpork proposal = proposalOver(null, 99);

		assertThat(node.receive(proposal), is(true));
		assertThat(node.receive(cosigned(proposal)), is(true));
	}
}
