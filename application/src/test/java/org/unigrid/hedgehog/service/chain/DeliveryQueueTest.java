/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.service.chain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;

public class DeliveryQueueTest {
	private static final int TIMEOUT_SECONDS = 10;
	private static final List<Entry> ENTRIES = ChainFleet.entries(ChainFleet.key(1), new ChainId(new byte[ChainId.SIZE]),
		4, ChainFleet.NOW);

	private static DeliveryQueue.Delivery delivery(final int number) {
		return new DeliveryQueue.Delivery(List.of(ENTRIES.get(number)), null, true);
	}

	private static DeliveryQueue queueOf(final int... numbers) {
		final DeliveryQueue queue = new DeliveryQueue(new LinkedBlockingQueue<>());

		for (final int number : numbers) {
			queue.add(delivery(number));
		}

		return queue;
	}

	@SneakyThrows
	private static void awaitIn(final CountDownLatch latch) {
		assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
	}

	@SneakyThrows
	private static void within(final CompletableFuture<?> running) {
		running.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	@Example
	public void handlesTheDeliveriesInTheOrderTheyWereAdded() {
		final DeliveryQueue queue = queueOf(0, 1, 2);
		final List<DeliveryQueue.Delivery> handled = new ArrayList<>();

		queue.drain(handled::add);
		queue.drain(handled::add);

		assertThat(handled, contains(delivery(0), delivery(1), delivery(2)));
	}

	@Example
	public void handlesWhatTheHandlerAddsAfterTheDeliveryItHandlesAndNeverInsideIt() {
		final DeliveryQueue queue = queueOf(0);
		final List<DeliveryQueue.Delivery> handled = new ArrayList<>();
		final AtomicInteger running = new AtomicInteger();
		final AtomicInteger deepest = new AtomicInteger();

		queue.drain(each -> {
			deepest.accumulateAndGet(running.incrementAndGet(), Math::max);

			if (each.equals(delivery(0))) {
				queue.add(delivery(1));
				queue.drain(handled::add);
			}

			handled.add(each);
			running.decrementAndGet();
		});

		assertThat(handled, contains(delivery(0), delivery(1)));
		assertThat(deepest.get(), is(1));
	}

	@Example
	public void handlesADeliveryAddedWhileTheLastPollFoundTheQueueEmpty() {
		final AtomicBoolean late = new AtomicBoolean(true);
		final List<DeliveryQueue.Delivery> handled = new ArrayList<>();
		final DeliveryQueue[] holder = new DeliveryQueue[1];

		holder[0] = new DeliveryQueue(new LinkedBlockingQueue<>() {
			@Override
			public DeliveryQueue.Delivery poll() {
				final DeliveryQueue.Delivery next = super.poll();

				if (next == null && late.getAndSet(false)) {
					holder[0].add(delivery(1));
					holder[0].drain(handled::add);
				}

				return next;
			}
		});
		holder[0].add(delivery(0));

		holder[0].drain(handled::add);

		assertThat(handled, contains(delivery(0), delivery(1)));
	}

	@Example
	public void freesTheQueueAndKeepsTheRestWhenTheHandlerThrowsAnError() {
		final DeliveryQueue queue = queueOf(0, 1);
		final List<DeliveryQueue.Delivery> handled = new ArrayList<>();

		assertThrows(LinkageError.class, () -> queue.drain(each -> {
			throw new LinkageError("A handler that fails");
		}));

		queue.add(delivery(2));
		queue.drain(handled::add);

		assertThat(handled, contains(delivery(1), delivery(2)));
	}

	@Example
	public void freesTheQueueAndKeepsTheRestWhenTheHandlerThrowsAnException() {
		final DeliveryQueue queue = queueOf(0, 1);
		final List<DeliveryQueue.Delivery> handled = new ArrayList<>();

		assertThrows(IllegalStateException.class, () -> queue.drain(each -> {
			throw new IllegalStateException("A handler that fails");
		}));

		queue.drain(handled::add);

		assertThat(handled, contains(delivery(1)));
	}

	@Example
	public void leavesTheHandlingToTheThreadThatHoldsTheQueue() {
		final DeliveryQueue queue = queueOf(0);
		final CountDownLatch inHandler = new CountDownLatch(1);
		final CountDownLatch letGo = new CountDownLatch(1);
		final List<DeliveryQueue.Delivery> byFirst = new ArrayList<>();
		final List<DeliveryQueue.Delivery> byOther = new ArrayList<>();
		final CompletableFuture<Void> first = CompletableFuture.runAsync(() -> queue.drain(each -> {
			inHandler.countDown();
			awaitIn(letGo);
			byFirst.add(each);
		}));

		awaitIn(inHandler);
		queue.add(delivery(1));
		within(CompletableFuture.runAsync(() -> queue.drain(byOther::add)));
		letGo.countDown();
		within(first);

		assertThat(byOther, is(empty()));
		assertThat(byFirst, contains(delivery(0), delivery(1)));
	}

	@Example
	public void beginsAFullEpisodeOnlyForTheFirstDropUntilADeliveryIsTaken() {
		final DeliveryQueue queue = new DeliveryQueue(new LinkedBlockingQueue<>(1));
		final List<DeliveryQueue.Delivery> handled = new ArrayList<>();

		queue.add(delivery(0));

		assertThat(queue.add(delivery(1)), is(false));
		assertThat(queue.beginsFullEpisode(), is(true));
		assertThat(queue.add(delivery(2)), is(false));
		assertThat(queue.beginsFullEpisode(), is(false));

		queue.drain(handled::add);
		queue.add(delivery(3));

		assertThat(queue.add(delivery(0)), is(false));
		assertThat(queue.beginsFullEpisode(), is(true));
	}

	@Example
	public void refusesADeliveryBeyondTheBoundOfItsQueueAndTakesMoreOnceRoomIsMade() {
		final DeliveryQueue queue = new DeliveryQueue(new LinkedBlockingQueue<>(2));
		final List<DeliveryQueue.Delivery> handled = new ArrayList<>();

		assertThat(queue.add(delivery(0)), is(true));
		assertThat(queue.add(delivery(1)), is(true));
		assertThat(queue.add(delivery(2)), is(false));

		queue.drain(handled::add);

		assertThat(queue.add(delivery(3)), is(true));
		queue.drain(handled::add);
		assertThat(handled, contains(delivery(0), delivery(1), delivery(3)));
	}
}
