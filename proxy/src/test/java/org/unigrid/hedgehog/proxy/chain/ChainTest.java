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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

public class ChainTest {
	private static final long NOW = 1_750_000_000_000L;
	private static final ChainId CHAIN = ChainFixtures.chainId(1);
	private static final ChainLimits LIMITS = new ChainLimits(1024 * 1024, 30, ChainFixtures.MAX_ENTRY_BYTES);
	private static final WriterKey WRITER = ChainFixtures.key(1);

	private static Chain chain() {
		return new Chain(CHAIN, LIMITS, () -> NOW);
	}

	private static List<Entry> linked(WriterKey key, int count) {
		return ChainFixtures.linked(key, CHAIN, count, NOW, EntryKind.SEALED);
	}

	private static Entry withoutSignature(Entry entry) {
		return new Entry(entry.header(), entry.payload(), entry.hash(), new byte[Entry.SIGNATURE_SIZE]);
	}

	private static int firstByte(WriterKey key) {
		return Byte.toUnsignedInt(key.id().bytes()[0]);
	}

	@Property(tries = 30)
	public void shouldMergeFeedsByTimestampWriterAndSeq(@ForAll @Size(min = 1, max = 4)
		List<@IntRange(min = 0, max = 12) Integer> lengths, @ForAll Random random) {
		final Chain chain = chain();
		final List<Deque<Entry>> feeds = new ArrayList<>();
		final List<Entry> all = new ArrayList<>();

		for (int i = 0; i < lengths.size(); i++) {
			final List<Entry> feed = linked(ChainFixtures.key(i + 1), lengths.get(i));

			feeds.add(new ArrayDeque<>(feed));
			all.addAll(feed);
		}

		for (int left = all.size(); left > 0; left--) {
			final List<Deque<Entry>> open = feeds.stream().filter(feed -> !feed.isEmpty()).toList();
			final Entry next = open.get(random.nextInt(open.size())).poll();

			assertThat(chain.offer(next), equalTo(new Offer(Admission.ACCEPTED, List.of(next))));
		}

		all.sort(Comparator.comparingLong((Entry entry) -> entry.header().timestamp())
			.thenComparing(entry -> entry.header().writer()).thenComparingLong(entry -> entry.header().seq()));
		assertThat(chain.entries(), equalTo(all));
		assertThat(chain.bytes(), is(all.stream().mapToLong(Entry::size).sum()));
	}

	@Example
	public void shouldOrderWritersOfTheSameTimestampAsUnsignedBytes() {
		final Chain chain = chain();
		final WriterKey lowKey = ChainFixtures.key(5);
		final WriterKey highKey = ChainFixtures.key(1);
		final Entry low = linked(lowKey, 1).get(0);
		final Entry high = linked(highKey, 1).get(0);

		assertThat(firstByte(lowKey), is(lessThan(0x80)));
		assertThat(firstByte(highKey), is(greaterThanOrEqualTo(0x80)));
		chain.offer(high);
		chain.offer(low);
		assertThat(chain.entries(), equalTo(List.of(low, high)));
	}

	@Example
	public void shouldKeepTwoEntriesOfOneWriterFromTheSameMillisecond() {
		final Chain chain = chain();
		final Entry first = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW, EntryKind.SEALED);
		final Entry second = ChainFixtures.signed(WRITER, CHAIN, 1, first.hash(), NOW, EntryKind.SEALED);

		assertThat(chain.offer(first).admission(), is(Admission.ACCEPTED));
		assertThat(chain.offer(second).admission(), is(Admission.ACCEPTED));
		assertThat(chain.entries(), equalTo(List.of(first, second)));
		assertThat(chain.bytes(), is((long) first.size() + second.size()));
	}

	@Example
	public void shouldReportTheHeadOfEveryFeed() {
		final Chain chain = chain();
		final WriterKey other = ChainFixtures.key(2);
		final List<Entry> first = linked(WRITER, 3);
		final List<Entry> second = linked(other, 2);

		first.forEach(chain::offer);
		second.forEach(chain::offer);
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(2, first.get(2).hash()),
			other.id(), new Head(1, second.get(1).hash()))));
	}

	@Example
	public void shouldListTheRetainedRangeOfAWriter() {
		final Chain chain = chain();
		final List<Entry> entries = linked(WRITER, 5);

		entries.forEach(chain::offer);
		assertThat(chain.range(WRITER.id(), 1, 3), equalTo(entries.subList(1, 4)));
		assertThat(chain.range(WRITER.id(), 2, 2), equalTo(entries.subList(2, 3)));
		assertThat(chain.range(WRITER.id(), 4, 9), equalTo(entries.subList(4, 5)));
		assertThat(chain.range(WRITER.id(), 3, 1), is(empty()));
		assertThat(chain.range(ChainFixtures.key(2).id(), 0, 9), is(empty()));
	}

	@Example
	public void shouldRefuseAnEntryOfAnotherChain() {
		final Entry entry = ChainFixtures.signed(WRITER, ChainFixtures.chainId(2), 0, 0, NOW, EntryKind.JOIN);

		assertThat(chain().offer(entry).admission(), is(Admission.WRONG_CHAIN));
	}

	@Example
	public void shouldRefuseAnEntryOverTheLimit() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(1024, 30, Entry.OVERHEAD + 10), () -> NOW);
		final EntryHeader header = new EntryHeader(CHAIN, WRITER.id(), 0, 0, NOW, Fingerprint.NONE, EntryKind.OPEN);

		assertThat(chain.offer(WRITER.sign(header, new byte[11])).admission(), is(Admission.TOO_LARGE));
		assertThat(chain.offer(WRITER.sign(header, new byte[10])).admission(), is(Admission.ACCEPTED));
	}

	/* Every entry is padded to a size class, so one of another size was not written by a writer of the chain */
	@Example
	public void refusesAnOddSize() {
		final Chain chain = chain();
		final EntryHeader header = new EntryHeader(CHAIN, WRITER.id(), 0, 0, NOW, Fingerprint.NONE, EntryKind.SEALED);
		final int small = SizeClasses.of(LIMITS.maxEntryBytes()).get(0) - Entry.OVERHEAD;

		assertThat(chain.offer(WRITER.sign(header, new byte[small + 1])), equalTo(new Offer(Admission.MALFORMED,
			List.of())));
		assertThat(chain.offer(WRITER.sign(header, new byte[small - 1])).admission(), is(Admission.MALFORMED));
		assertThat(chain.heads(), equalTo(Map.of()));
		assertThat(chain.offer(WRITER.sign(header, new byte[small])).admission(), is(Admission.ACCEPTED));
	}

	/* Writers stamp their entries in whole seconds, so that the time between two entries tells nobody more */
	@Example
	public void refusesASubSecondTimestamp() {
		final Chain chain = chain();

		assertThat(chain.offer(ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW + 1, EntryKind.SEALED)).admission(),
			is(Admission.MALFORMED));
		assertThat(chain.offer(ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW - 999, EntryKind.SEALED)).admission(),
			is(Admission.MALFORMED));
		assertThat(chain.heads(), equalTo(Map.of()));
		assertThat(chain.offer(ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW - 1000, EntryKind.SEALED)).admission(),
			is(Admission.ACCEPTED));
	}

	@Example
	public void shouldNotVerifyAMalformedEntry() {
		final Entry subSecond = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW + 1, EntryKind.SEALED);

		assertThat(chain().offer(withoutSignature(subSecond)).admission(), is(Admission.MALFORMED));
	}

	@Example
	public void shouldRefuseAnEntryFromBeyondTheRetentionWindow() {
		final Entry future = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW + 31_000, EntryKind.JOIN);
		final Entry edge = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW + 30_000, EntryKind.JOIN);
		final Chain chain = chain();

		assertThat(chain.offer(future).admission(), is(Admission.FUTURE));
		assertThat(chain.offer(edge).admission(), is(Admission.ACCEPTED));
	}

	@Example
	public void shouldRefuseAnEntryThatIsNotAuthentic() {
		final Entry entry = linked(WRITER, 1).get(0);
		final Entry tampered = new Entry(entry.header(), entry.payload(), entry.hash() + 1, entry.signature());

		assertThat(chain().offer(tampered).admission(), is(Admission.NOT_AUTHENTIC));
	}

	@Example
	public void shouldNotVerifyWhatIsAlreadyBehindItsFeed() {
		final Chain chain = chain();
		final List<Entry> entries = linked(WRITER, 3);

		entries.forEach(chain::offer);
		assertThat(chain.offer(withoutSignature(entries.get(2))).admission(), is(Admission.DUPLICATE));
		assertThat(chain.offer(withoutSignature(entries.get(0))).admission(), is(Admission.REPLAYED));
	}

	@Example
	public void shouldNotVerifyAnEntryWhoseLinkIsBroken() {
		final Entry broken = ChainFixtures.signed(WRITER, CHAIN, 0, 7, NOW, EntryKind.SEALED);

		assertThat(chain().offer(withoutSignature(broken)).admission(), is(Admission.BROKEN));
	}

	@Example
	public void shouldVerifyAnEntryAheadOfItsFeedBeforeHoldingIt() {
		final Chain chain = chain();

		assertThat(chain.offer(withoutSignature(linked(WRITER, 3).get(2))).admission(), is(Admission.NOT_AUTHENTIC));
		assertThat(chain.heldBytes(), is(0L));
	}

	@Example
	public void shouldNotVerifyAnEntryOfAnotherChain() {
		final Entry entry = ChainFixtures.signed(WRITER, ChainFixtures.chainId(2), 0, 0, NOW, EntryKind.JOIN);

		assertThat(chain().offer(withoutSignature(entry)).admission(), is(Admission.WRONG_CHAIN));
	}

	@Example
	public void shouldNotVerifyAnEntryOverTheLimit() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(1024, 30, Entry.OVERHEAD + 10), () -> NOW);
		final EntryHeader header = new EntryHeader(CHAIN, WRITER.id(), 0, 0, NOW, Fingerprint.NONE, EntryKind.OPEN);

		assertThat(chain.offer(withoutSignature(WRITER.sign(header, new byte[11]))).admission(),
			is(Admission.TOO_LARGE));
	}

	@Example
	public void shouldNotVerifyAnEntryFromBeyondTheRetentionWindow() {
		final Entry future = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW + 31_000, EntryKind.JOIN);

		assertThat(chain().offer(withoutSignature(future)).admission(), is(Admission.FUTURE));
	}

	@Example
	public void shouldTellDuplicatesReplaysGapsAndBrokenLinksApart() {
		final Chain chain = chain();
		final List<Entry> entries = linked(WRITER, 4);

		chain.offer(entries.get(0));
		chain.offer(entries.get(1));
		assertThat(chain.offer(entries.get(1)).admission(), is(Admission.DUPLICATE));
		assertThat(chain.offer(entries.get(0)).admission(), is(Admission.REPLAYED));
		assertThat(chain.offer(entries.get(3)).admission(), is(Admission.AHEAD));
		assertThat(chain.offer(ChainFixtures.signed(WRITER, CHAIN, 2, 7, NOW + 2000, EntryKind.SEALED)).admission(),
			is(Admission.BROKEN));
		assertThat(chain.entries(), equalTo(entries.subList(0, 2)));
	}

	@Example
	public void shouldLeaveNoTraceOfARefusedWriter() {
		final Chain chain = chain();

		chain.offer(ChainFixtures.signed(WRITER, CHAIN, 0, 7, NOW, EntryKind.SEALED));
		assertThat(chain.heads(), equalTo(Map.of()));
		assertThat(chain.bytes(), is(0L));
		assertThat(chain.anchor(WRITER.id(), new Head(0, 1)), is(true));
	}

	@Example
	public void shouldLeaveNoTraceOfAWriterThatIsNotAuthentic() {
		final Chain chain = chain();

		assertThat(chain.offer(withoutSignature(linked(WRITER, 1).get(0))).admission(), is(Admission.NOT_AUTHENTIC));
		assertThat(chain.heads(), equalTo(Map.of()));
		assertThat(chain.entries(), is(empty()));
		assertThat(chain.bytes(), is(0L));
		assertThat(chain.anchor(WRITER.id(), new Head(0, 1)), is(true));
	}

	@Example
	public void shouldNotAdvanceAFeedOnAnEntryThatIsNotAuthentic() {
		final Chain chain = chain();
		final List<Entry> entries = linked(WRITER, 2);

		chain.offer(entries.get(0));
		assertThat(chain.offer(withoutSignature(entries.get(1))).admission(), is(Admission.NOT_AUTHENTIC));
		assertThat(chain.entries(), equalTo(entries.subList(0, 1)));
		assertThat(chain.offer(entries.get(1)).admission(), is(Admission.ACCEPTED));
		assertThat(chain.entries(), equalTo(entries));
	}

	@Example
	public void shouldContinueAWriterFromAnAnchor() {
		final Chain chain = chain();
		final List<Entry> entries = linked(WRITER, 4);

		assertThat(chain.anchor(WRITER.id(), new Head(1, entries.get(1).hash())), is(true));
		assertThat(chain.anchor(WRITER.id(), new Head(0, entries.get(0).hash())), is(false));
		assertThat(chain.offer(entries.get(2)).admission(), is(Admission.ACCEPTED));
		assertThat(chain.offer(entries.get(3)).admission(), is(Admission.ACCEPTED));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(3, entries.get(3).hash()))));
	}

	@Example
	public void shouldNotAnchorAWriterThatAlreadyHasEntries() {
		final Chain chain = chain();
		final List<Entry> entries = linked(WRITER, 2);
		final Head head = new Head(1, entries.get(1).hash());

		entries.forEach(chain::offer);
		assertThat(chain.anchor(WRITER.id(), head), is(false));
		assertThat(chain.anchor(WRITER.id(), new Head(5, 1)), is(false));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), head)));
	}

	@Example
	public void shouldNotAnchorAWriterAfterTheLastSequenceNumber() {
		final Chain chain = chain();

		assertThat(chain.anchor(WRITER.id(), new Head(Long.MAX_VALUE, 1)), is(false));
		assertThat(chain.heads(), equalTo(Map.of()));
		assertThat(chain.anchor(WRITER.id(), new Head(Long.MAX_VALUE - 1, 1)), is(true));
	}
}
