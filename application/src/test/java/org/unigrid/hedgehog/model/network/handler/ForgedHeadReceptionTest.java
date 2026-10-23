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

/* A node that a board member reads from may hand out a forged stored spork in place of the true head: signed once, in
   the form of a build from before co-signatures, by a key that the board has since retired, and carrying the log of
   the true head. A version made over it carries that log and adds an entry of the forgery, which a known key signed,
   so every peer that holds the true head would take it but for the head itself: the entry that follows the stored
   log has to be the stored spork. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class ForgedHeadReceptionTest {
	private static final String PEER = "198.51.100.7";
	private static final Instant EARLIER_BUILD = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60);

	private static final Signature FIRST = keyPair();
	private static final Signature SECOND = keyPair();
	private static final Signature RETIRED = keyPair();

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

			@Mock public String[] getRetiredPublicKeys() {
				return new String[] { RETIRED.getPublicKey() };
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

	/* What a build from before co-signatures stored: signed once by the board of its time, over its Java
	   serialization */
	@SneakyThrows
	private static GridSpork ofAnEarlierBuild(GridSpork spork, Signature signer) {
		spork.setFlags((short) 0);
		spork.setCosignature(null);
		spork.setSignature(signer.sign(spork.getSignable()));
		return spork;
	}

	private static GridSpork firstVersion() {
		final GridSpork spork = GridSpork.create(Type.MINT_SUPPLY);

		spork.setData(supplyOf(10));
		spork.setPreviousData(supplyOf(0));
		spork.setTimeStamp(EARLIER_BUILD);
		spork.setPreviousTimeStamp(Instant.EPOCH);
		return ofAnEarlierBuild(spork, RETIRED);
	}

	@SneakyThrows
	private static GridSpork proposalOver(GridSpork stored, long maxSupply) {
		final GridSpork spork = GridSpork.nextVersion(Type.MINT_SUPPLY, stored, Optional.empty());

		spork.setData(supplyOf(maxSupply));
		spork.sign(FIRST.getPrivateKey());
		return spork;
	}

	@SneakyThrows
	private static GridSpork cosigned(GridSpork proposal) {
		final GridSpork spork = SerializationUtils.clone(proposal);

		spork.cosign(SECOND.getPrivateKey());
		return spork;
	}

	/* The versions the network took, one over the other, the first of them stored by a build from before */
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
		return ofAnEarlierBuild(forged, RETIRED);
	}

	@Example
	public void shouldRefuseAProposalAndASporkWhoseLogGoesOnWithAForgedHead() {
		final GridSpork head = versions(3).getLast();
		final GridSpork forged = forgedOver(head);
		final GridSpork proposal = proposalOver(forged, 20);
		final Node node = new Node(head);

		assertThat(forged.isTakenByTheNetwork(), is(true));
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

	@Example
	public void shouldTakeTheFirstVersionWhenNothingIsStored() {
		final Node node = new Node();
		final GridSpork proposal = proposalOver(null, 99);

		assertThat(node.receive(proposal), is(true));
		assertThat(node.receive(cosigned(proposal)), is(true));
	}
}
