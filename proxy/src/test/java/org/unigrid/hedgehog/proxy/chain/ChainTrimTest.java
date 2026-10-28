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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.proxy.Padding;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class ChainTrimTest {
	private static final long NOW = 1_750_000_000_000L;
	private static final ChainId CHAIN = ChainFixtures.chainId(1);
	private static final ChainLimits LIMITS = new ChainLimits(1024 * 1024, 30, ChainFixtures.MAX_ENTRY_BYTES);
	private static final WriterKey WRITER = ChainFixtures.key(1);
	private static final int ENTRY_SIZE = ChainFixtures.ENTRY_SIZE;

	private static Entry withoutSignature(Entry entry) {
		return new Entry(entry.header(), entry.payload(), entry.hash(), new byte[Entry.SIGNATURE_SIZE]);
	}

	@Property(tries = 50)
	public void shouldTrimWhatIsOlderThanTheRetentionWindow(@ForAll @IntRange(min = 1, max = 30) int count,
		@ForAll @IntRange(min = 25_000, max = 65_000) int elapsed) {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final List<Entry> entries = ChainFixtures.linked(WRITER, CHAIN, count, NOW);
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

	/* Only an entry released from the buffer passes the room of the chain, and the oldest entries make way for it,
	   which may be one that came in with it */
	@Example
	public void shouldTakeAnEntryThatTrimmingDropsAtOnce() {
		final WriterKey other = ChainFixtures.key(2);
		final Chain chain = new Chain(CHAIN, new ChainLimits(4L * ENTRY_SIZE, 30, ENTRY_SIZE), () -> NOW);
		final List<Entry> entries = ChainFixtures.linked(WRITER, CHAIN, 4, NOW);
		final Entry oldest = ChainFixtures.signed(other, CHAIN, 0, 0, NOW - 1000);
		final Entry next = ChainFixtures.signed(other, CHAIN, 1, oldest.hash(), NOW - 1000);

		entries.forEach(chain::offer);
		assertThat(chain.offer(next).admission(), is(Admission.AHEAD));
		assertThat(chain.offer(oldest), equalTo(new Offer(Admission.ACCEPTED, List.of(oldest, next))));
		assertThat(chain.entries(), equalTo(List.of(next, entries.get(0), entries.get(1), entries.get(2),
			entries.get(3))));
		assertThat(chain.bytes(), is(5L * ENTRY_SIZE));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(3, entries.get(3).hash()),
			other.id(), new Head(1, next.hash()))));
		assertThat(chain.offer(oldest).admission(), is(Admission.REPLAYED));
		assertThat(chain.range(other.id(), 0, 1), equalTo(List.of(next)));
	}

	@Example
	public void shouldTrimByAgeBeforeTakingAnEntry() {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Entry first = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW);
		final Entry second = ChainFixtures.signed(WRITER, CHAIN, 1, first.hash(), NOW + 20_000);

		chain.offer(first);
		clock.set(NOW + 30_001);
		assertThat(chain.offer(second).admission(), is(Admission.ACCEPTED));
		assertThat(chain.entries(), equalTo(List.of(second)));
	}

	@Example
	public void shouldRefuseAnEntryOlderThanTheRetentionWindow() {
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);
		final Entry expired = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW - 31_000);
		final Entry edge = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW - 30_000);

		assertThat(chain.offer(expired).admission(), is(Admission.EXPIRED));
		assertThat(chain.offer(edge).admission(), is(Admission.ACCEPTED));
	}

	@Example
	public void shouldKeepAnEntryExactlyAtTheEdgeOfTheRetentionWindow() {
		final AtomicLong clock = new AtomicLong(NOW + 30_000);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Entry entry = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW);

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
		final Entry stale = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW);
		final Entry foreign = ChainFixtures.signed(WRITER, ChainFixtures.chainId(2), 0, 0, NOW);

		chain.offer(stale);
		clock.set(NOW + 30_001);
		assertThat(chain.offer(foreign).admission(), is(Admission.WRONG_CHAIN));
		assertThat(chain.entries(), is(empty()));
	}

	@Example
	public void shouldNotVerifyAnExpiredEntry() {
		final Entry expired = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW - 31_000);

		assertThat(new Chain(CHAIN, LIMITS, () -> NOW).offer(withoutSignature(expired)).admission(),
			is(Admission.EXPIRED));
	}

	@Example
	public void shouldLeaveNoTraceOfAnExpiredEntry() {
		final Chain chain = new Chain(CHAIN, LIMITS, () -> NOW);

		chain.offer(ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW - 31_000));
		assertThat(chain.heads(), equalTo(Map.of()));
		assertThat(chain.entries(), is(empty()));
		assertThat(chain.bytes(), is(0L));
		assertThat(chain.anchor(WRITER.id(), new Head(0, 1)), is(true));
	}

	@Example
	public void shouldRefuseAnEntryThatWasTrimmedForItsAge() {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, LIMITS, clock::get);
		final Entry entry = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW);

		chain.offer(entry);
		clock.set(NOW + 30_001);
		assertThat(chain.offer(entry).admission(), is(Admission.EXPIRED));
		assertThat(chain.entries(), is(empty()));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(0, entry.hash()))));
	}

	/* A larger entry released from the buffer takes the room of as many smaller ones as it needs */
	@Example
	public void shouldTrimAsManyEntriesAsALargerOneNeeds() {
		final ChainLimits limits = new ChainLimits(6L * ENTRY_SIZE, 30, 4 * ENTRY_SIZE);
		final Chain chain = new Chain(CHAIN, limits, () -> NOW);
		final List<Entry> others = ChainFixtures.linked(ChainFixtures.key(2), CHAIN, 5, NOW + 1000);
		final Entry first = ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW);
		final Entry second = ChainFixtures.signed(WRITER, CHAIN, 1, first.hash(), NOW + 6000);
		final Entry large = WRITER.sign(new EntryHeader(CHAIN, WRITER.id(), 2, second.hash(), NOW + 7000,
			ChainFixtures.TAG), Padding.padded(new byte[ENTRY_SIZE], limits.maxEntryBytes()));

		chain.offer(first);
		assertThat(chain.offer(large).admission(), is(Admission.AHEAD));
		others.forEach(entry -> assertThat(chain.offer(entry).admission(), is(Admission.ACCEPTED)));
		assertThat(chain.offer(second).appended(), equalTo(List.of(second, large)));
		assertThat(chain.entries(), equalTo(List.of(others.get(3), others.get(4), second, large)));
		assertThat(chain.bytes(), is(limits.capacityBytes() + limits.reserveBytes()));
	}
}
