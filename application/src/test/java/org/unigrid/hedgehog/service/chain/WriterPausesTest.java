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

import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class WriterPausesTest {
	private static final int BOUND = 1_024;
	private static final int DISTINCT = 2_000;
	private static final int THREADS = 8;
	private static final int ROUNDS = 300;
	private static final int TIMEOUT_SECONDS = 30;
	private static final ChainId CHAIN = chain(0);
	private static final WriterId WRITER = ChainFleet.writer(1);
	private static final List<WriterId> WRITERS = IntStream.range(0, DISTINCT).mapToObj(ChainFleet::writer).toList();

	private static ChainId chain(final int number) {
		return new ChainId(ByteBuffer.allocate(ChainId.SIZE).putInt(number).array());
	}

	private static EntryRefused refusal(final ChainId chainId, final WriterId writer, final long seq) {
		return EntryRefused.builder().chainId(chainId).writer(writer).seq(seq).admission(Admission.FULL).build();
	}

	private static void pause(final WriterPauses pauses, final int writer, final long seq) {
		pauses.pause(refusal(CHAIN, WRITERS.get(writer), seq));
	}

	private static void pauseEach(final WriterPauses pauses, final int from, final int to, final long seq) {
		IntStream.range(from, to).forEach(writer -> pause(pauses, writer, seq));
	}

	private static List<Integer> held(final WriterPauses pauses) {
		return IntStream.range(0, DISTINCT).filter(writer -> pauses.isPaused(CHAIN, WRITERS.get(writer))).boxed()
			.toList();
	}

	private static List<Integer> numbers(final int from, final int to) {
		return IntStream.range(from, to).boxed().toList();
	}

	private static List<Integer> numbersExcept(final int from, final int to, final int skipped) {
		return IntStream.range(from, to).filter(number -> number != skipped).boxed().toList();
	}

	@SneakyThrows
	private static void awaitIn(final CountDownLatch latch) {
		assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
	}

	/* Every thread is waiting at the gate before the first is let through, so that they overlap */
	@SneakyThrows
	private static void atOnce(final ExecutorService threads, final IntConsumer task) {
		final CountDownLatch ready = new CountDownLatch(THREADS);
		final CountDownLatch go = new CountDownLatch(1);
		final List<Future<?>> running = IntStream.range(0, THREADS).<Future<?>>mapToObj(thread ->
			threads.submit(() -> {
				ready.countDown();
				awaitIn(go);
				task.accept(thread);
			})).toList();

		awaitIn(ready);
		go.countDown();

		for (final Future<?> finished : running) {
			finished.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
	}

	@Example
	public void knowsNothingOfAWriterNobodyRefused() {
		final WriterPauses pauses = new WriterPauses();

		assertThat(pauses.refusedFrom(CHAIN, WRITER), is(OptionalLong.empty()));
		assertThat(pauses.isPaused(CHAIN, WRITER), is(false));
	}

	@Property
	public void keepsTheLowestRefusedSequenceNumberToOfferAgainFromThere(@ForAll @LongRange(min = 0) long first,
		@ForAll @LongRange(min = 0) long second) {

		final WriterPauses pauses = new WriterPauses();

		pauses.pause(refusal(CHAIN, WRITER, first));
		pauses.pause(refusal(CHAIN, WRITER, second));

		assertThat(pauses.refusedFrom(CHAIN, WRITER), equalTo(OptionalLong.of(Math.min(first, second))));
		assertThat(pauses.isPaused(CHAIN, WRITER), is(true));
	}

	@Example
	public void pausesEachWriterOnEachChainOnItsOwn() {
		final WriterPauses pauses = new WriterPauses();
		final ChainId other = chain(1);

		pauses.pause(refusal(CHAIN, WRITER, 4));

		assertThat(pauses.isPaused(other, WRITER), is(false));
		assertThat(pauses.isPaused(CHAIN, ChainFleet.writer(2)), is(false));
	}

	@Example
	public void resumesAWriterThatOfferedItsEntriesAgain() {
		final WriterPauses pauses = new WriterPauses();

		pauses.pause(refusal(CHAIN, WRITER, 4));
		pauses.resume(CHAIN, WRITER);

		assertThat(pauses.isPaused(CHAIN, WRITER), is(false));
		assertThat(pauses.refusedFrom(CHAIN, WRITER), is(OptionalLong.empty()));
	}

	@Example
	public void forgetsTheOldestPausesRatherThanGrowWithoutBound() {
		final WriterPauses pauses = new WriterPauses();

		IntStream.range(0, WriterPauses.MAX_PAUSES + 10).forEach(writer -> pauses.pause(refusal(CHAIN,
			ChainFleet.writer(writer), 0)));

		assertThat(pauses.isPaused(CHAIN, ChainFleet.writer(0)), is(false));
		assertThat(pauses.isPaused(CHAIN, ChainFleet.writer(9)), is(false));
		assertThat(pauses.isPaused(CHAIN, ChainFleet.writer(10)), is(true));
		assertThat(pauses.isPaused(CHAIN, ChainFleet.writer(WriterPauses.MAX_PAUSES + 9)), is(true));
	}

	@Example
	public void boundsTheTableAtOneThousandAndTwentyFourPauses() {
		assertThat(WriterPauses.MAX_PAUSES, is(BOUND));
	}

	@Example
	public void lowersAPauseWhenALowerSequenceNumberIsRefused() {
		final WriterPauses pauses = new WriterPauses();

		pause(pauses, 0, 7);
		pause(pauses, 0, 3);

		assertThat(pauses.refusedFrom(CHAIN, WRITERS.get(0)), is(OptionalLong.of(3)));
	}

	@Example
	public void keepsTheLowerSequenceNumberWhenAHigherOneIsRefused() {
		final WriterPauses pauses = new WriterPauses();

		pause(pauses, 0, 3);
		pause(pauses, 0, 7);

		assertThat(pauses.refusedFrom(CHAIN, WRITERS.get(0)), is(OptionalLong.of(3)));
	}

	@Example
	public void keepsTheSequenceNumberWhenTheSameOneIsRefusedAgain() {
		final WriterPauses pauses = new WriterPauses();

		pause(pauses, 0, 3);
		pause(pauses, 0, 3);

		assertThat(pauses.refusedFrom(CHAIN, WRITERS.get(0)), is(OptionalLong.of(3)));
	}

	@Example
	public void pausesAtTheFirstAndAtTheLastSequenceNumber() {
		final WriterPauses pauses = new WriterPauses();

		pause(pauses, 0, 0);
		pause(pauses, 1, Long.MAX_VALUE);

		assertThat(pauses.refusedFrom(CHAIN, WRITERS.get(0)), is(OptionalLong.of(0)));
		assertThat(pauses.isPaused(CHAIN, WRITERS.get(0)), is(true));
		assertThat(pauses.refusedFrom(CHAIN, WRITERS.get(1)), is(OptionalLong.of(Long.MAX_VALUE)));
	}

	@Example
	public void keepsWhatIsPausedOnOtherChainsAndWritersApart() {
		final WriterPauses pauses = new WriterPauses();

		pauses.pause(refusal(CHAIN, WRITER, 4));
		pauses.pause(refusal(chain(1), WRITER, 2));
		pauses.pause(refusal(CHAIN, ChainFleet.writer(2), 9));

		assertThat(pauses.refusedFrom(CHAIN, WRITER), is(OptionalLong.of(4)));
		assertThat(pauses.refusedFrom(chain(1), WRITER), is(OptionalLong.of(2)));
		assertThat(pauses.refusedFrom(CHAIN, ChainFleet.writer(2)), is(OptionalLong.of(9)));
	}

	@Example
	public void resumesOnlyThePairItIsGiven() {
		final WriterPauses pauses = new WriterPauses();

		pauses.pause(refusal(CHAIN, WRITER, 4));
		pauses.pause(refusal(chain(1), WRITER, 2));
		pauses.pause(refusal(CHAIN, ChainFleet.writer(2), 9));

		pauses.resume(CHAIN, WRITER);

		assertThat(pauses.isPaused(CHAIN, WRITER), is(false));
		assertThat(pauses.refusedFrom(chain(1), WRITER), is(OptionalLong.of(2)));
		assertThat(pauses.refusedFrom(CHAIN, ChainFleet.writer(2)), is(OptionalLong.of(9)));
	}

	@Example
	public void resumesAPairThatWasNeverPausedWithoutHarm() {
		final WriterPauses pauses = new WriterPauses();

		pauses.resume(CHAIN, WRITER);
		pauses.pause(refusal(CHAIN, ChainFleet.writer(2), 9));
		pauses.resume(CHAIN, WRITER);
		pauses.resume(chain(1), ChainFleet.writer(2));

		assertThat(pauses.isPaused(CHAIN, WRITER), is(false));
		assertThat(pauses.refusedFrom(CHAIN, ChainFleet.writer(2)), is(OptionalLong.of(9)));
	}

	@Example
	public void resumesAPairOnceAndThenKnowsNothingOfIt() {
		final WriterPauses pauses = new WriterPauses();

		pauses.pause(refusal(CHAIN, WRITER, 4));
		pauses.resume(CHAIN, WRITER);
		pauses.resume(CHAIN, WRITER);

		assertThat(pauses.refusedFrom(CHAIN, WRITER), is(OptionalLong.empty()));
	}

	@Example
	public void pausesFromTheSequenceNumberRefusedAfterTheLastResume() {
		final WriterPauses pauses = new WriterPauses();

		pauses.pause(refusal(CHAIN, WRITER, 4));
		pauses.resume(CHAIN, WRITER);
		pauses.pause(refusal(CHAIN, WRITER, 9));

		assertThat(pauses.refusedFrom(CHAIN, WRITER), is(OptionalLong.of(9)));
	}

	@Example
	public void keepsEveryPauseUpToTheBound() {
		final WriterPauses pauses = new WriterPauses();

		pauseEach(pauses, 0, BOUND, 0);

		assertThat(held(pauses), equalTo(numbers(0, BOUND)));
	}

	@Example
	public void forgetsExactlyTheOldestPauseWhenOneMoreComes() {
		final WriterPauses pauses = new WriterPauses();

		pauseEach(pauses, 0, BOUND + 1, 0);

		assertThat(held(pauses), equalTo(numbers(1, BOUND + 1)));
	}

	@Example
	public void forgetsOnePauseForEveryPauseBeyondTheBound() {
		final WriterPauses pauses = new WriterPauses();

		pauseEach(pauses, 0, DISTINCT, 0);

		assertThat(held(pauses), equalTo(numbers(DISTINCT - BOUND, DISTINCT)));
	}

	@Example
	public void keepsAPausedPairOnItsPlaceWhenALowerSequenceNumberIsRefusedAgain() {
		final WriterPauses pauses = new WriterPauses();

		pauseEach(pauses, 0, BOUND, 5);
		pause(pauses, 0, 3);

		assertThat(pauses.refusedFrom(CHAIN, WRITERS.get(0)), is(OptionalLong.of(3)));

		pause(pauses, BOUND, 5);

		assertThat(held(pauses), equalTo(numbers(1, BOUND + 1)));
	}

	@Example
	public void keepsAPausedPairOnItsPlaceWhenAHigherSequenceNumberIsRefusedAgain() {
		final WriterPauses pauses = new WriterPauses();

		pauseEach(pauses, 0, BOUND, 5);
		pause(pauses, 0, 9);

		assertThat(pauses.refusedFrom(CHAIN, WRITERS.get(0)), is(OptionalLong.of(5)));

		pause(pauses, BOUND, 5);

		assertThat(held(pauses), equalTo(numbers(1, BOUND + 1)));
	}

	@Example
	public void keepsAPausedPairOnItsPlaceWhenTheSameSequenceNumberIsRefusedAgain() {
		final WriterPauses pauses = new WriterPauses();

		pauseEach(pauses, 0, BOUND, 5);
		pause(pauses, 0, 5);
		pause(pauses, BOUND, 5);

		assertThat(held(pauses), equalTo(numbers(1, BOUND + 1)));
	}

	@Example
	public void givesThePlaceOfAResumedPairToTheNextPause() {
		final WriterPauses pauses = new WriterPauses();

		pauseEach(pauses, 0, BOUND, 0);
		pauses.resume(CHAIN, WRITERS.get(5));
		pause(pauses, BOUND, 0);

		assertThat(held(pauses), equalTo(numbersExcept(0, BOUND + 1, 5)));
	}

	@Example
	public void putsAPairThatPausesAgainAfterAResumeLast() {
		final WriterPauses pauses = new WriterPauses();

		pauseEach(pauses, 0, BOUND, 0);
		pauses.resume(CHAIN, WRITERS.get(0));
		pause(pauses, 0, 0);
		pause(pauses, BOUND, 0);

		assertThat(held(pauses), equalTo(numbersExcept(0, BOUND + 1, 1)));
	}

	@Example
	public void countsTheChainAsPartOfThePairWhenForgettingTheOldest() {
		final WriterPauses pauses = new WriterPauses();
		final ChainId other = chain(1);

		pauses.pause(refusal(CHAIN, WRITER, 0));
		pauses.pause(refusal(other, WRITER, 0));
		IntStream.range(0, BOUND - 1).forEach(writer -> pause(pauses, writer + 2, 0));

		assertThat(pauses.isPaused(CHAIN, WRITER), is(false));
		assertThat(pauses.isPaused(other, WRITER), is(true));
	}

	@Example
	public void keepsTheLowestSequenceNumberWhenWritersArePausedAtOnce() {
		final ExecutorService threads = Executors.newFixedThreadPool(THREADS);

		try {
			for (int round = 0; round < ROUNDS; round++) {
				final WriterPauses pauses = new WriterPauses();

				atOnce(threads, thread -> pause(pauses, 0, thread));

				assertThat(pauses.refusedFrom(CHAIN, WRITERS.get(0)), is(OptionalLong.of(0)));
			}
		} finally {
			threads.shutdownNow();
		}
	}

	@Example
	public void holdsNoMoreThanTheBoundWhenManyPairsArePausedAtOnce() {
		final ExecutorService threads = Executors.newFixedThreadPool(THREADS);
		final int share = DISTINCT / THREADS;

		try {
			for (int round = 0; round < ROUNDS / 10; round++) {
				final WriterPauses pauses = new WriterPauses();

				atOnce(threads, thread -> pauseEach(pauses, thread * share, (thread + 1) * share, 0));

				assertThat(held(pauses).size(), is(BOUND));
			}
		} finally {
			threads.shutdownNow();
		}
	}
}
