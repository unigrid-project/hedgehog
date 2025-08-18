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

package org.unigrid.hedgehog.proxy.chain;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;

public class ChainBufferTest {
	private static final long NOW = 1_750_000_000_000L;
	private static final ChainId CHAIN = ChainFixtures.chainId(1);
	private static final ChainLimits LIMITS = new ChainLimits(1024 * 1024, 30, ChainFixtures.MAX_ENTRY_BYTES);
	private static final WriterKey WRITER = ChainFixtures.key(1);
	private static final int ENTRY_SIZE = Entry.OVERHEAD + 1;

	private static List<Entry> linked(int count, EntryKind kind) {
		return ChainFixtures.linked(WRITER, CHAIN, count, NOW, kind);
	}

	@Property(tries = 50)
	public void shouldAppendAShuffledFeedInOrder(@ForAll @IntRange(min = 1, max = 25) int count,
		@ForAll Random random) {
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);
		final List<Entry> entries = linked(count, EntryKind.SEALED);
		final List<Entry> shuffled = new ArrayList<>(entries);
		final List<Entry> appended = new ArrayList<>();

		Collections.shuffle(shuffled, random);

		for (Entry entry : shuffled) {
			final Offer offer = chain.offer(entry);

			assertThat(offer.admission(), is(oneOf(Admission.ACCEPTED, Admission.AHEAD)));
			appended.addAll(offer.appended());
		}

		assertThat(appended, equalTo(entries));
		assertThat(chain.entries(), equalTo(entries));
		assertThat(chain.heldBytes(), is(0L));
	}

	@Example
	public void shouldReleaseWhatWaitedWhenTheGapFills() {
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);
		final List<Entry> entries = linked(4, EntryKind.SEALED);

		chain.offer(entries.get(0));
		assertThat(chain.offer(entries.get(2)), equalTo(new Offer(Admission.AHEAD, List.of())));
		assertThat(chain.offer(entries.get(3)).admission(), is(Admission.AHEAD));
		assertThat(chain.heldBytes(), is(2L * ENTRY_SIZE));
		assertThat(chain.offer(entries.get(1)), equalTo(new Offer(Admission.ACCEPTED, entries.subList(1, 4))));
		assertThat(chain.heldBytes(), is(0L));
	}

	@Example
	public void shouldDropAWaitingEntryThatDoesNotLink() {
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);
		final List<Entry> entries = linked(3, EntryKind.SEALED);
		final Entry stray = ChainFixtures.signed(WRITER, CHAIN, 2, 7, NOW + 2, EntryKind.SEALED);

		chain.offer(entries.get(0));
		chain.offer(stray);
		assertThat(chain.offer(entries.get(1)), equalTo(new Offer(Admission.ACCEPTED, List.of(entries.get(1)))));
		assertThat(chain.heldBytes(), is(0L));
		assertThat(chain.offer(entries.get(2)).admission(), is(Admission.ACCEPTED));
	}

	@Example
	public void shouldHoldAnEntryOnlyOnce() {
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);
		final List<Entry> entries = linked(3, EntryKind.SEALED);

		chain.offer(entries.get(0));
		chain.offer(entries.get(2));
		assertThat(chain.offer(entries.get(2)).admission(), is(Admission.AHEAD));
		assertThat(chain.heldBytes(), is((long) ENTRY_SIZE));
	}

	@Example
	public void shouldNotHoldAnEntryThatIsNotAuthentic() {
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);
		final List<Entry> entries = linked(3, EntryKind.SEALED);
		final Entry ahead = entries.get(2);
		final Entry tampered = new Entry(ahead.header(), ahead.payload(), ahead.hash() + 1, ahead.signature());

		chain.offer(entries.get(0));
		assertThat(chain.offer(tampered).admission(), is(Admission.NOT_AUTHENTIC));
		assertThat(chain.heldBytes(), is(0L));
	}

	@Example
	public void shouldBoundTheEntriesThatWait() {
		final ChainLimits limits = new ChainLimits(8L * ENTRY_SIZE, 30, ENTRY_SIZE);
		final Chain chain = new Chain(CHAIN, limits, () -> NOW);
		final List<Entry> joins = linked(5, EntryKind.JOIN);

		assertThat(limits.bufferBytes(), is(2L * ENTRY_SIZE));
		chain.offer(joins.get(0));
		assertThat(chain.offer(joins.get(2)).admission(), is(Admission.AHEAD));
		assertThat(chain.offer(joins.get(3)).admission(), is(Admission.AHEAD));
		assertThat(chain.offer(joins.get(4)).admission(), is(Admission.FULL));
		assertThat(chain.heldBytes(), is(2L * ENTRY_SIZE));
	}

	@Example
	public void shouldNotHoldSealedEntriesWhileFull() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(4L * ENTRY_SIZE, 30, ENTRY_SIZE), () -> NOW);
		final List<Entry> sealed = linked(6, EntryKind.SEALED);

		sealed.subList(0, 4).forEach(chain::offer);
		assertThat(chain.offer(sealed.get(5)).admission(), is(Admission.FULL));
		assertThat(chain.heldBytes(), is(0L));
	}

	@Example
	public void shouldSizeTheBufferFromTheLargerOfAnEntryAndAQuarterOfTheCapacity() {
		assertThat(new ChainLimits(100L * ENTRY_SIZE, 30, ENTRY_SIZE).bufferBytes(), is(25L * ENTRY_SIZE));
		assertThat(new ChainLimits(2L * ENTRY_SIZE, 30, 2 * ENTRY_SIZE).bufferBytes(), is(2L * ENTRY_SIZE));
	}

	@Example
	public void shouldKeepWaitingEntriesOutOfTheChain() {
		final WriterKey other = ChainFixtures.key(2);
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);
		final List<Entry> entries = linked(3, EntryKind.SEALED);
		final Entry stranger = ChainFixtures.linked(other, CHAIN, 2, NOW, EntryKind.SEALED).get(1);

		chain.offer(entries.get(0));
		chain.offer(entries.get(2));
		chain.offer(stranger);
		assertThat(chain.entries(), equalTo(entries.subList(0, 1)));
		assertThat(chain.range(WRITER.id(), 0, 9), equalTo(entries.subList(0, 1)));
		assertThat(chain.range(other.id(), 0, 9), is(empty()));
		assertThat(chain.bytes(), is((long) ENTRY_SIZE));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(0, entries.get(0).hash()))));
		assertThat(chain.anchor(other.id(), new Head(0, 1)), is(true));
	}

	@Example
	public void shouldReleaseOnlyWhatLinksWhileAGapRemains() {
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);
		final List<Entry> entries = linked(4, EntryKind.SEALED);

		chain.offer(entries.get(0));
		chain.offer(entries.get(3));
		assertThat(chain.offer(entries.get(1)), equalTo(new Offer(Admission.ACCEPTED, List.of(entries.get(1)))));
		assertThat(chain.heldBytes(), is((long) ENTRY_SIZE));
		assertThat(chain.offer(entries.get(2)), equalTo(new Offer(Admission.ACCEPTED, entries.subList(2, 4))));
		assertThat(chain.heldBytes(), is(0L));
	}

	@Example
	public void shouldKeepTheEntryThatWaitedFirstForASequenceNumber() {
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);
		final List<Entry> entries = linked(3, EntryKind.SEALED);
		final Entry fork = WRITER.sign(new EntryHeader(CHAIN, WRITER.id(), 2, 7, NOW + 2, Fingerprint.NONE,
			EntryKind.SEALED), new byte[3]);

		chain.offer(entries.get(0));
		chain.offer(entries.get(2));
		assertThat(chain.offer(fork).admission(), is(Admission.AHEAD));
		assertThat(chain.heldBytes(), is((long) ENTRY_SIZE));
		assertThat(chain.offer(entries.get(1)).appended(), equalTo(entries.subList(1, 3)));
		assertThat(chain.heldBytes(), is(0L));
	}

	@Example
	public void shouldShareTheBufferBetweenWriters() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(8L * ENTRY_SIZE, 30, ENTRY_SIZE), () -> NOW);
		final List<List<Entry>> feeds = List.of(joins(1), joins(2), joins(3));

		feeds.forEach(feed -> chain.offer(feed.get(0)));
		assertThat(chain.offer(feeds.get(0).get(2)).admission(), is(Admission.AHEAD));
		assertThat(chain.offer(feeds.get(1).get(2)).admission(), is(Admission.AHEAD));
		assertThat(chain.offer(feeds.get(2).get(2)).admission(), is(Admission.FULL));
		assertThat(chain.offer(feeds.get(0).get(1)).appended(), equalTo(feeds.get(0).subList(1, 3)));
		assertThat(chain.heldBytes(), is((long) ENTRY_SIZE));
		assertThat(chain.offer(feeds.get(2).get(2)).admission(), is(Admission.AHEAD));
		assertThat(chain.offer(feeds.get(1).get(1)).appended(), equalTo(feeds.get(1).subList(1, 3)));
		assertThat(chain.heldBytes(), is((long) ENTRY_SIZE));
	}

	@Example
	public void shouldAnswerNotAuthenticBeforeFullForABufferThatIsFull() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(8L * ENTRY_SIZE, 30, ENTRY_SIZE), () -> NOW);
		final List<Entry> joins = linked(5, EntryKind.JOIN);
		final Entry forged = new Entry(joins.get(4).header(), joins.get(4).payload(), joins.get(4).hash(),
			new byte[Entry.SIGNATURE_SIZE]);

		chain.offer(joins.get(0));
		chain.offer(joins.get(2));
		chain.offer(joins.get(3));
		assertThat(chain.offer(forged).admission(), is(Admission.NOT_AUTHENTIC));
		assertThat(chain.offer(joins.get(4)).admission(), is(Admission.FULL));
		assertThat(chain.heldBytes(), is(2L * ENTRY_SIZE));
	}

	@Example
	public void shouldAnswerNotAuthenticBeforeFullForAChainThatIsFull() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(4L * ENTRY_SIZE, 30, ENTRY_SIZE), () -> NOW);
		final List<Entry> sealed = linked(6, EntryKind.SEALED);
		final Entry forged = new Entry(sealed.get(5).header(), sealed.get(5).payload(), sealed.get(5).hash(),
			new byte[Entry.SIGNATURE_SIZE]);

		sealed.subList(0, 4).forEach(chain::offer);
		assertThat(chain.offer(forged).admission(), is(Admission.NOT_AUTHENTIC));
		assertThat(chain.offer(sealed.get(5)).admission(), is(Admission.FULL));
	}

	@Example
	public void shouldHoldOtherKindsWhileFull() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(4L * ENTRY_SIZE, 30, ENTRY_SIZE), () -> NOW);
		final List<Entry> sealed = linked(4, EntryKind.SEALED);
		final Entry leave = ChainFixtures.signed(WRITER, CHAIN, 4, sealed.get(3).hash(), NOW + 4, EntryKind.LEAVE);
		final Entry later = ChainFixtures.signed(WRITER, CHAIN, 5, leave.hash(), NOW + 5, EntryKind.LEAVE);

		sealed.forEach(chain::offer);
		assertThat(chain.offer(later).admission(), is(Admission.AHEAD));
		assertThat(chain.offer(leave), equalTo(new Offer(Admission.ACCEPTED, List.of(leave, later))));
	}

	@Example
	public void shouldHoldASealedEntryThatFillsTheChainExactly() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(4L * ENTRY_SIZE, 30, ENTRY_SIZE), () -> NOW);
		final List<Entry> sealed = linked(5, EntryKind.SEALED);

		sealed.subList(0, 3).forEach(chain::offer);
		assertThat(chain.offer(sealed.get(4)).admission(), is(Admission.AHEAD));
		assertThat(chain.heldBytes(), is((long) ENTRY_SIZE));
	}

	@Example
	public void shouldLetAReleasedEntryTrimTheOldestWhenTheChainIsFull() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(4L * ENTRY_SIZE, 30, ENTRY_SIZE), () -> NOW);
		final List<Entry> sealed = linked(5, EntryKind.SEALED);

		sealed.subList(0, 3).forEach(chain::offer);
		assertThat(chain.offer(sealed.get(4)).admission(), is(Admission.AHEAD));
		assertThat(chain.offer(sealed.get(3)), equalTo(new Offer(Admission.ACCEPTED, sealed.subList(3, 5))));
		assertThat(chain.entries(), equalTo(sealed.subList(1, 5)));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(4, sealed.get(4).hash()))));
		assertThat(chain.bytes(), is(4L * ENTRY_SIZE));
		assertThat(chain.heldBytes(), is(0L));
		assertThat(chain.offer(sealed.get(4)).admission(), is(Admission.DUPLICATE));
	}

	@Example
	public void shouldNotHoldAnEntryThatIsRefused() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(8L * ENTRY_SIZE, 30, ENTRY_SIZE), () -> NOW);
		final Entry elsewhere = ChainFixtures.signed(WRITER, ChainFixtures.chainId(2), 2, 7, NOW, EntryKind.SEALED);
		final Entry future = ChainFixtures.signed(WRITER, CHAIN, 2, 7, NOW + 30_001, EntryKind.SEALED);
		final Entry expired = ChainFixtures.signed(WRITER, CHAIN, 2, 7, NOW - 30_001, EntryKind.SEALED);
		final Entry large = WRITER.sign(new EntryHeader(CHAIN, WRITER.id(), 2, 7, NOW, Fingerprint.NONE,
			EntryKind.SEALED), new byte[2]);

		assertThat(chain.offer(elsewhere).admission(), is(Admission.WRONG_CHAIN));
		assertThat(chain.offer(future).admission(), is(Admission.FUTURE));
		assertThat(chain.offer(expired).admission(), is(Admission.EXPIRED));
		assertThat(chain.offer(large).admission(), is(Admission.TOO_LARGE));
		assertThat(chain.heldBytes(), is(0L));
	}

	@Example
	public void shouldDropWhatWaitedBehindAnAnchor() {
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);
		final List<Entry> entries = linked(6, EntryKind.SEALED);

		chain.offer(entries.get(1));
		assertThat(chain.anchor(WRITER.id(), new Head(3, entries.get(3).hash())), is(true));
		assertThat(chain.offer(entries.get(4)), equalTo(new Offer(Admission.ACCEPTED, List.of(entries.get(4)))));
		assertThat(chain.heldBytes(), is(0L));
	}

	@Example
	public void shouldForgetAWriterOnceNothingWaitsForIt() throws ReflectiveOperationException {
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);
		final List<Entry> first = joins(1);
		final List<Entry> second = joins(2);

		chain.offer(first.get(0));
		chain.offer(first.get(2));
		chain.offer(second.get(0));
		chain.offer(second.get(2));
		assertThat(waitingWriters(chain), is(2));
		chain.offer(first.get(1));
		assertThat(waitingWriters(chain), is(1));
		chain.offer(second.get(1));
		assertThat(waitingWriters(chain), is(0));
	}

	private static List<Entry> joins(int writer) {
		return ChainFixtures.linked(ChainFixtures.key(writer), CHAIN, 3, NOW, EntryKind.JOIN);
	}

	/* No accessor shows an empty waiting list left behind for a writer, and it would leak as long as the chain lives */
	private static int waitingWriters(Chain chain) throws ReflectiveOperationException {
		final Field held = Chain.class.getDeclaredField("held");

		held.setAccessible(true);
		return ((Map<?, ?>) held.get(chain)).size();
	}
}
