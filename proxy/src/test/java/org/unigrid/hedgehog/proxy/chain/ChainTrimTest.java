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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class ChainTrimTest {
	private static final long NOW = 1_750_000_000_000L;
	private static final ChainId CHAIN = ChainFixtures.chainId(1);
	private static final ChainLimits LIMITS = new ChainLimits(1024 * 1024, 30, ChainFixtures.MAX_ENTRY_BYTES);
	private static final WriterKey WRITER = ChainFixtures.key(1);
	private static final int ENTRY_SIZE = Entry.OVERHEAD + 1;

	private static Entry withoutSignature(Entry entry) {
		return new Entry(entry.header(), entry.payload(), entry.hash(), new byte[Entry.SIGNATURE_SIZE]);
	}

	@Property(tries = 50)
	public void shouldTrimWhatIsOlderThanTheRetentionWindow(@ForAll @IntRange(min = 1, max = 40) int count,
		@ForAll @IntRange(min = 29_990, max = 30_050) int elapsed) {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final List<Entry> entries = ChainFixtures.linked(WRITER, CHAIN, count, NOW, EntryKind.SEALED);
		final Entry last = entries.get(count - 1);

		entries.forEach(chain::offer);
		clock.addAndGet(elapsed);
		chain.trim();

		final List<Entry> kept = entries.stream()
			.filter(entry -> entry.header().timestamp() >= clock.get() - LIMITS.retentionMillis()).toList();

		assertThat(chain.entries(), equalTo(kept));
		assertThat(chain.bytes(), is(kept.stream().mapToLong(Entry::size).sum()));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(count - 1, last.hash()))));
	}

	@Example
	public void shouldTrimTheOldestEntriesOverCapacity() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(4L * ENTRY_SIZE, 30, ENTRY_SIZE), () -> NOW);
		final List<Entry> joins = ChainFixtures.linked(WRITER, CHAIN, 10, NOW, EntryKind.JOIN);

		joins.forEach(entry -> assertThat(chain.offer(entry).admission(), is(Admission.ACCEPTED)));
		assertThat(chain.entries(), equalTo(joins.subList(6, 10)));
		assertThat(chain.range(WRITER.id(), 0, 9), equalTo(joins.subList(6, 10)));
		assertThat(chain.bytes(), is(4L * ENTRY_SIZE));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(9, joins.get(9).hash()))));
	}

	@Example
	public void shouldNotTakeBackATrimmedEntry() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(4L * ENTRY_SIZE, 30, ENTRY_SIZE), () -> NOW);
		final List<Entry> joins = ChainFixtures.linked(WRITER, CHAIN, 10, NOW, EntryKind.JOIN);

		joins.forEach(chain::offer);
		assertThat(chain.offer(joins.get(2)).admission(), is(Admission.REPLAYED));
		assertThat(chain.offer(joins.get(9)).admission(), is(Admission.DUPLICATE));
		assertThat(chain.entries(), equalTo(joins.subList(6, 10)));
	}

	@Example
	public void shouldTrimByAgeBeforeTakingAnEntry() {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Entry first = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW, EntryKind.JOIN);
		final Entry second = ChainFixtures.signed(WRITER, CHAIN, 1, first.hash(), NOW + 20_000, EntryKind.SEALED);

		chain.offer(first);
		clock.set(NOW + 30_001);
		assertThat(chain.offer(second).admission(), is(Admission.ACCEPTED));
		assertThat(chain.entries(), equalTo(List.of(second)));
	}

	@Example
	public void shouldRefuseAnEntryOlderThanTheRetentionWindow() {
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);
		final Entry expired = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW - 30_001, EntryKind.JOIN);
		final Entry edge = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW - 30_000, EntryKind.JOIN);

		assertThat(chain.offer(expired).admission(), is(Admission.EXPIRED));
		assertThat(chain.offer(edge).admission(), is(Admission.ACCEPTED));
	}

	@Example
	public void shouldKeepAnEntryExactlyAtTheEdgeOfTheRetentionWindow() {
		final AtomicLong clock = new AtomicLong(NOW + 30_000);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Entry entry = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW, EntryKind.JOIN);

		chain.offer(entry);
		chain.trim();
		assertThat(chain.entries(), equalTo(List.of(entry)));
		clock.incrementAndGet();
		chain.trim();
		assertThat(chain.entries(), is(empty()));
		assertThat(chain.bytes(), is(0L));
	}

	@Example
	public void shouldTrimWhenAnOfferIsRefused() {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Entry stale = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW, EntryKind.JOIN);
		final Entry foreign = ChainFixtures.signed(WRITER, ChainFixtures.chainId(2), 0, 0, NOW, EntryKind.JOIN);

		chain.offer(stale);
		clock.set(NOW + 30_001);
		assertThat(chain.offer(foreign).admission(), is(Admission.WRONG_CHAIN));
		assertThat(chain.entries(), is(empty()));
	}

	@Example
	public void shouldNotVerifyAnExpiredEntry() {
		final Entry expired = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW - 30_001, EntryKind.JOIN);

		assertThat(new Chain(CHAIN, LIMITS, () -> NOW).offer(withoutSignature(expired)).admission(),
			is(Admission.EXPIRED));
	}

	@Example
	public void shouldLeaveNoTraceOfAnExpiredEntry() {
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);

		chain.offer(ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW - 30_001, EntryKind.JOIN));
		assertThat(chain.heads(), equalTo(Map.of()));
		assertThat(chain.entries(), is(empty()));
		assertThat(chain.bytes(), is(0L));
		assertThat(chain.anchor(WRITER.id(), new Head(0, 1)), is(true));
	}

	@Example
	public void shouldRefuseAnEntryThatWasTrimmedForItsAge() {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Entry entry = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW, EntryKind.JOIN);

		chain.offer(entry);
		clock.set(NOW + 30_001);
		assertThat(chain.offer(entry).admission(), is(Admission.EXPIRED));
		assertThat(chain.entries(), is(empty()));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(0, entry.hash()))));
	}

	@Example
	public void shouldTrimAsManyEntriesAsALargerOneNeeds() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(6L * ENTRY_SIZE, 30, 4 * ENTRY_SIZE), () -> NOW);
		final List<Entry> joins = ChainFixtures.linked(WRITER, CHAIN, 6, NOW, EntryKind.JOIN);
		final long prev = joins.get(5).hash();
		final Entry large = WRITER.sign(new EntryHeader(CHAIN, WRITER.id(), 6, prev, NOW + 6, Fingerprint.NONE,
			EntryKind.JOIN), new byte[3 * ENTRY_SIZE - Entry.OVERHEAD]);

		joins.forEach(chain::offer);
		assertThat(chain.offer(large).admission(), is(Admission.ACCEPTED));
		assertThat(chain.entries(), equalTo(List.of(joins.get(3), joins.get(4), joins.get(5), large)));
		assertThat(chain.bytes(), is(6L * ENTRY_SIZE));
	}
}
