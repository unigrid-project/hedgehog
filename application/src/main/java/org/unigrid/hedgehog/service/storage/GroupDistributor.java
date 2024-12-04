/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.StorageStatus;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;

@RequiredArgsConstructor
public class GroupDistributor {
	private final FragmentTransport transport;
	private final Random random;
	private final Duration maxJitter;

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

		for (Fragment failed : sendAll(sealed.subList(0, guaranteed), window.subList(0, guaranteed))) {
			if (!retry(failed, spare)) {
				return false;
			}
		}

		final int extras = Math.min(sealed.size() - guaranteed, spare.size());
		sendAll(sealed.subList(guaranteed, guaranteed + extras), new ArrayList<>(spare).subList(0, extras));
		return true;
	}

	public void withdraw(final GroupKey key, final List<Gridnode> window, final long timestamp) {
		final byte[] publicKey = key.publicKey();
		final byte[] signature = key.signDelete(timestamp);

		window.stream().map(gridnode -> transport.delete(gridnode, key.groupId(), publicKey, timestamp, signature))
			.collect(Collectors.toList()).forEach(ack -> ack.exceptionally(error -> null).join());
	}

	/* Fragments leave in random order with jitter so a gridnode cannot read placement order off arrival order. */
	private List<Fragment> sendAll(final List<Fragment> fragments, final List<Gridnode> targets) {
		final List<Integer> order = IntStream.range(0, fragments.size()).boxed().collect(Collectors.toList());
		final Map<Integer, CompletableFuture<StorageAck>> acks = new HashMap<>();

		Collections.shuffle(order, random);
		order.forEach(i -> acks.put(i, send(targets.get(i), fragments.get(i))));

		return order.stream().filter(i -> !stored(acks.get(i))).map(fragments::get).collect(Collectors.toList());
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
