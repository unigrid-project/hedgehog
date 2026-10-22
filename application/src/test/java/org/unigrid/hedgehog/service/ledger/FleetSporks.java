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

package org.unigrid.hedgehog.service.ledger;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import org.apache.commons.lang3.SerializationUtils;
import static org.awaitility.Awaitility.await;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.spork.ChunkData;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;
import static org.unigrid.hedgehog.service.ledger.FleetContainers.bean;
import static org.unigrid.hedgehog.service.ledger.FleetContainers.within;

/* Sporks as the network keys sign them and the nodes take them. The two network keys are made once for the process
   and are the keys NetworkKey answers with while mockNetworkKeys() is in force, so a spork the fleet signs is one
   that a node accepts, and any other is not. A spork reaches a node through the same method that the handler of the
   packet calls, with the database, the pending proposals and the spork lag of that node's container. */
@RequiredArgsConstructor
final class FleetSporks {
	private static final Signature PROPOSER = networkKey();
	private static final Signature COSIGNER = networkKey();
	private static final Duration TAKEN_WITHIN = Duration.ofSeconds(2).plus(SporkLag.VERIFY_GAP);

	private final IntFunction<String> containerOf;
	private final IntSupplier nodes;

	@SneakyThrows
	private static Signature networkKey() {
		return new Signature();
	}

	/* The private keys, which the commands of the foundation send to a node with the data of a spork */
	static String proposerKey() {
		return PROPOSER.getPrivateKey();
	}

	static String cosignerKey() {
		return COSIGNER.getPrivateKey();
	}

	/* The two keys of the fleet are the network keys, with none retired */
	static void mockNetworkKeys() {
		new MockUp<NetworkKey>() {
			@Mock
			public String[] getPublicKeys() {
				return new String[] { PROPOSER.getPublicKey(), COSIGNER.getPublicKey() };
			}

			@Mock
			public String[] getRetiredPublicKeys() {
				return new String[0];
			}
		};
	}

	GridSpork stored(final int node, final Type type) {
		final String container = containerOf.apply(node);

		return within(container, () -> bean(container, SporkDatabase.class).get(type));
	}

	/* The next version of the spork over the one the first node holds, with this data and signed once, which is
	   a proposal. A version is made newer than the stored one even within the millisecond it was made in. */
	@SneakyThrows
	GridSpork proposal(final Type type, final ChunkData data) {
		final GridSpork stored = stored(0, type);
		final GridSpork spork = Objects.isNull(stored) ? GridSpork.create(type) : SerializationUtils.clone(stored);

		spork.archive();

		if (Objects.nonNull(stored) && !spork.getTimeStamp().isAfter(stored.getTimeStamp())) {
			spork.setTimeStamp(stored.getTimeStamp().plusMillis(1));
		}

		spork.setData(data);
		spork.sign(PROPOSER.getPrivateKey());
		return spork;
	}

	@SneakyThrows
	GridSpork signed(final Type type, final ChunkData data) {
		final GridSpork spork = proposal(type, data);

		spork.cosign(COSIGNER.getPrivateKey());
		return spork;
	}

	/* A copy, as a spork that came over the wire is the node's own. Whether the node takes it is its own business. */
	void deliver(final int node, final GridSpork spork) {
		final String container = containerOf.apply(node);

		within(container, () -> new PublishSporkChannelHandler().receive(SerializationUtils.clone(spork),
			bean(container, SporkDatabase.class), bean(container, PendingSporks.class),
			bean(container, SporkLag.class), "fleet"));
	}

	/* To every node, and again to each node that does not hold it yet, as peers send a spork again and again, until
	   each of them holds it or an error says that one did not take it. A node that has just turned a forged spork of
	   the type away from the fleet checks the next one from it a second later. The nodes take it side by side:
	   checking the signatures of a spork is most of what a node does with it. */
	void publish(final GridSpork spork) {
		await("every node holds the spork").atMost(TAKEN_WITHIN).until(() -> {
			final List<Integer> missing = IntStream.range(0, nodes.getAsInt()).filter(node -> !holds(node, spork))
				.boxed().toList();

			missing.parallelStream().forEach(node -> deliver(node, spork));
			return missing.isEmpty();
		});
	}

	private boolean holds(final int node, final GridSpork spork) {
		final GridSpork held = stored(node, spork.getType());

		return Objects.nonNull(held) && Objects.equals(held.getTimeStamp(), spork.getTimeStamp())
			&& Arrays.equals(held.getSignature(), spork.getSignature())
			&& Arrays.equals(held.getCosignature(), spork.getCosignature());
	}
}
