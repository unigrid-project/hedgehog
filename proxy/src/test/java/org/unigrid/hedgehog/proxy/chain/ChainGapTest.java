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

package org.unigrid.hedgehog.proxy.chain;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class ChainGapTest {
	private static final long NOW = 1_750_000_000_000L;
	private static final ChainId CHAIN = ChainFixtures.chainId(1);
	private static final ChainLimits LIMITS = new ChainLimits(1024 * 1024, 30, ChainFixtures.MAX_ENTRY_BYTES);
	private static final WriterKey WRITER = ChainFixtures.key(1);
	private static final WriterKey OTHER = ChainFixtures.key(2);

	private static Fingerprint fingerprint(int number) {
		final byte[] bytes = new byte[Fingerprint.SIZE];

		Arrays.fill(bytes, (byte) number);
		return new Fingerprint(bytes);
	}

	private static Entry signed(WriterKey key, long seq, long prev, long timestamp, Fingerprint fingerprint) {
		return key.sign(new EntryHeader(CHAIN, key.id(), seq, prev, timestamp, fingerprint, EntryKind.SEALED),
			new byte[] { (byte) seq });
	}

	@Example
	public void shouldSkipAGapThatOutlivedTheRetentionWindow() {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Entry first = signed(WRITER, 0, 0, NOW, fingerprint(1));
		final Entry lost = signed(WRITER, 1, first.hash(), NOW + 1, fingerprint(1));
		final Entry third = signed(WRITER, 2, lost.hash(), NOW + 2, fingerprint(2));
		final Entry fourth = signed(WRITER, 3, third.hash(), NOW + 3, fingerprint(3));
		final Entry fifth = signed(WRITER, 4, fourth.hash(), NOW + 30_010, fingerprint(1));

		chain.offer(first);
		chain.offer(third);
		chain.offer(fourth);
		clock.set(NOW + 30_003);
		assertThat(chain.expireGaps(), equalTo(List.of(new Gap(WRITER.id(), 1, 3,
			Set.of(fingerprint(2), fingerprint(3))))));
		assertThat(chain.heldBytes(), is(0L));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(3, fourth.hash()))));
		assertThat(chain.offer(fifth).admission(), is(Admission.ACCEPTED));
		assertThat(chain.expireGaps(), is(empty()));
	}

	@Example
	public void shouldKeepWaitingWithinTheRetentionWindow() {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Entry first = signed(WRITER, 0, 0, NOW, fingerprint(1));
		final Entry second = signed(WRITER, 1, first.hash(), NOW + 1, fingerprint(1));
		final Entry third = signed(WRITER, 2, second.hash(), NOW + 2, fingerprint(1));

		chain.offer(first);
		chain.offer(third);
		clock.set(NOW + 30_001);
		assertThat(chain.expireGaps(), is(empty()));
		assertThat(chain.heldBytes(), is((long) third.size()));
		assertThat(chain.offer(second).appended(), equalTo(List.of(second, third)));
	}

	@Example
	public void shouldSkipFromTheStartOfAWriterNeverSeen() {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Entry waiting = signed(OTHER, 2, 42, NOW, fingerprint(4));

		chain.offer(waiting);
		clock.set(NOW + 30_001);
		assertThat(chain.expireGaps(), equalTo(List.of(new Gap(OTHER.id(), 0, 2, Set.of(fingerprint(4))))));
		assertThat(chain.heads(), equalTo(Map.of(OTHER.id(), new Head(2, waiting.hash()))));
	}

	@Example
	public void shouldLeaveTheGapsOfOtherWritersAlone() {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Entry old = signed(WRITER, 1, 42, NOW, fingerprint(1));
		final Entry recent = signed(OTHER, 1, 42, NOW + 20_000, fingerprint(2));

		chain.offer(old);
		chain.offer(recent);
		clock.set(NOW + 30_001);
		assertThat(chain.expireGaps(), equalTo(List.of(new Gap(WRITER.id(), 0, 1, Set.of(fingerprint(1))))));
		assertThat(chain.heldBytes(), is((long) recent.size()));
	}

	@Example
	public void shouldSkipTheGapsOfEveryWriterThatOutlivedTheWindow() {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);

		chain.offer(waiting(WRITER, 2, NOW));
		chain.offer(waiting(OTHER, 3, NOW));
		clock.set(NOW + 30_001);
		assertThat(chain.expireGaps(), containsInAnyOrder(new Gap(WRITER.id(), 0, 2, Set.of(fingerprint(2))),
			new Gap(OTHER.id(), 0, 3, Set.of(fingerprint(3)))));
		assertThat(chain.heldBytes(), is(0L));
		assertThat(chain.expireGaps(), is(empty()));
	}

	@Example
	public void shouldKeepWaitingForAnEntryExactlyAsOldAsTheWindow() throws ReflectiveOperationException {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);

		chain.offer(waiting(WRITER, 2, NOW + 2));
		clock.set(NOW + 30_002);
		assertThat(chain.expireGaps(), is(empty()));
		assertThat(waitingWriters(chain), is(1));
		clock.set(NOW + 30_003);
		assertThat(chain.expireGaps(), equalTo(List.of(new Gap(WRITER.id(), 0, 2, Set.of(fingerprint(2))))));
	}

	@Example
	public void shouldNotMoveAFeedBackBehindAnAnchor() throws ReflectiveOperationException {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Head anchor = new Head(5, 99);
		final Entry next = signed(WRITER, 6, 99, NOW + 30_002, fingerprint(6));

		chain.offer(waiting(WRITER, 1, NOW));
		chain.offer(waiting(WRITER, 2, NOW + 1));
		assertThat(chain.anchor(WRITER.id(), anchor), is(true));
		clock.set(NOW + 30_002);
		assertThat(chain.expireGaps(), is(empty()));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), anchor)));
		assertThat(chain.heldBytes(), is(0L));
		assertThat(waitingWriters(chain), is(0));
		assertThat(chain.offer(next).admission(), is(Admission.ACCEPTED));
	}

	@Example
	public void shouldSkipOnlyWhatWaitsPastTheAnchor() throws ReflectiveOperationException {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Entry last = waiting(WRITER, 8, NOW);

		chain.offer(waiting(WRITER, 4, NOW));
		chain.offer(waiting(WRITER, 5, NOW));
		chain.offer(waiting(WRITER, 6, NOW));
		chain.offer(last);
		assertThat(chain.anchor(WRITER.id(), new Head(5, 99)), is(true));
		clock.set(NOW + 30_001);
		assertThat(chain.expireGaps(), equalTo(List.of(new Gap(WRITER.id(), 6, 8,
			Set.of(fingerprint(6), fingerprint(8))))));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(8, last.hash()))));
		assertThat(chain.heldBytes(), is(0L));
		assertThat(waitingWriters(chain), is(0));
	}

	@Example
	public void shouldSkipAnEntryThatWaitsAtTheNextSeqOfAnAnchoredFeed() {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Entry next = waiting(WRITER, 6, NOW);

		chain.offer(next);
		assertThat(chain.anchor(WRITER.id(), new Head(5, 99)), is(true));
		clock.set(NOW + 30_001);
		assertThat(chain.expireGaps(), equalTo(List.of(new Gap(WRITER.id(), 6, 6, Set.of(fingerprint(6))))));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(6, next.hash()))));
	}

	@Example
	public void shouldJudgeAGapByTheFirstEntryThatStillWaits() {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Head anchor = new Head(5, 99);
		final Entry sixth = signed(WRITER, 6, 99, NOW + 10_000, fingerprint(6));
		final Entry seventh = signed(WRITER, 7, sixth.hash(), NOW + 15_000, fingerprint(7));
		final Entry eighth = signed(WRITER, 8, seventh.hash(), NOW + 20_000, fingerprint(8));

		chain.offer(waiting(WRITER, 3, NOW));
		chain.offer(eighth);
		assertThat(chain.anchor(WRITER.id(), anchor), is(true));
		clock.set(NOW + 30_001);
		assertThat(chain.expireGaps(), is(empty()));
		assertThat(chain.heldBytes(), is((long) eighth.size()));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), anchor)));
		chain.offer(sixth);
		assertThat(chain.offer(seventh).appended(), equalTo(List.of(seventh, eighth)));
		assertThat(chain.heldBytes(), is(0L));
	}

	@Example
	public void shouldNotFailOnAWriterWithNothingWaiting() throws ReflectiveOperationException {
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);

		heldOf(chain).put(WRITER.id(), new TreeMap<>());
		assertThat(chain.expireGaps(), is(empty()));
		assertThat(waitingWriters(chain), is(0));
	}

	@Example
	public void shouldKeepAGapOfItsOwn() {
		final Set<Fingerprint> fingerprints = new HashSet<>(Set.of(fingerprint(1)));
		final Gap gap = new Gap(WRITER.id(), 1, 2, fingerprints);

		fingerprints.add(fingerprint(2));
		assertThat(gap.fingerprints(), equalTo(Set.of(fingerprint(1))));
		Expect.assertThrows(UnsupportedOperationException.class, () -> gap.fingerprints().add(fingerprint(3)));
	}

	@Example
	public void shouldRefuseAGapWithoutAWriterOrFingerprints() {
		final NullPointerException noWriter = Expect.assertThrows(NullPointerException.class,
			() -> new Gap(null, 1, 2, Set.of()));

		assertThat(noWriter.getMessage(), is("writer"));
		Expect.assertThrows(NullPointerException.class, () -> new Gap(WRITER.id(), 1, 2, null));
	}

	private static Entry waiting(WriterKey key, long seq, long timestamp) {
		return signed(key, seq, 42, timestamp, fingerprint((int) seq));
	}

	@SuppressWarnings("unchecked")
	private static Map<WriterId, NavigableMap<Long, Entry>> heldOf(Chain chain) throws ReflectiveOperationException {
		final Field held = Chain.class.getDeclaredField("held");

		held.setAccessible(true);
		return (Map<WriterId, NavigableMap<Long, Entry>>) held.get(chain);
	}

	/* No accessor shows an empty waiting list left behind for a writer, and it would leak as long as the chain lives */
	private static int waitingWriters(Chain chain) throws ReflectiveOperationException {
		return heldOf(chain).size();
	}
}
