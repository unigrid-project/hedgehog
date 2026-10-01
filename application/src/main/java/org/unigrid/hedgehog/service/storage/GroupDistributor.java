/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.service.storage;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.StorageStatus;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;

public class GroupDistributor {
	private static final Duration JITTER_LIMIT = Duration.ofMillis(Integer.MAX_VALUE);

	private final FragmentTransport transport;
	private final Random random;
	private final Duration maxJitter;

	public GroupDistributor(final FragmentTransport transport, final Random random, final Duration maxJitter) {
		if (maxJitter.isNegative() || maxJitter.compareTo(JITTER_LIMIT) >= 0) {
			throw new IllegalArgumentException("The jitter must lie between zero and " + JITTER_LIMIT);
		}

		this.transport = transport;
		this.random = random;
		this.maxJitter = maxJitter;
	}

	public static boolean stored(final CompletableFuture<StorageAck> ack) {
		try {
			return ack.join().getStatus() == StorageStatus.OK;
		} catch (CompletionException ex) {
			return false;
		}
	}

	public boolean place(final List<Fragment> sealed, final List<Gridnode> window) {
		final int guaranteed = sealed.get(0).getDescriptor().guaranteedFragments();

		if (window.size() < guaranteed) {
			return false;
		}

		final Deque<Gridnode> spare = new ArrayDeque<>(window.subList(guaranteed, window.size()));
		final List<Fragment> required = sealed.subList(0, guaranteed);

		for (Map.Entry<Integer, CompletableFuture<StorageAck>> ack : sendAll(required, window).entrySet()) {
			if (!stored(ack.getValue()) && !retry(required.get(ack.getKey()), spare)) {
				return false;
			}
		}

		/* Extras only widen the margin, so nobody waits for their acknowledgements */
		final int extras = Math.min(sealed.size() - guaranteed, spare.size());
		sendAll(sealed.subList(guaranteed, guaranteed + extras), new ArrayList<>(spare));
		return true;
	}

	public void withdraw(final GroupKey key, final List<Gridnode> window, final long timestamp) {
		final byte[] publicKey = key.publicKey();
		final byte[] signature = key.signDelete(timestamp);

		window.stream().map(gridnode -> transport.delete(gridnode, key.groupId(), publicKey, timestamp, signature))
			.collect(Collectors.toList()).forEach(ack -> ack.exceptionally(error -> null).join());
	}

	/* Fragments leave in random order with jitter so a gridnode cannot read placement order off arrival order. */
	private Map<Integer, CompletableFuture<StorageAck>> sendAll(final List<Fragment> fragments,
		final List<Gridnode> targets) {

		final List<Integer> order = IntStream.range(0, fragments.size()).boxed().collect(Collectors.toList());
		final Map<Integer, CompletableFuture<StorageAck>> acks = new LinkedHashMap<>();

		Collections.shuffle(order, random);
		order.forEach(i -> acks.put(i, send(targets.get(i), fragments.get(i))));
		return acks;
	}

	private CompletableFuture<StorageAck> send(final Gridnode target, final Fragment fragment) {
		if (maxJitter.isZero()) {
			return transport.store(target, fragment.encode());
		}

		final long delay = random.nextInt((int) maxJitter.toMillis() + 1);

		return CompletableFuture.supplyAsync(fragment::encode, CompletableFuture.delayedExecutor(delay,
			TimeUnit.MILLISECONDS)).thenCompose(bytes -> transport.store(target, bytes));
	}

	private boolean retry(final Fragment fragment, final Deque<Gridnode> spare) {
		while (!spare.isEmpty()) {
			if (stored(transport.store(spare.poll(), fragment.encode()))) {
				return true;
			}
		}

		return false;
	}
}
