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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.proxy.Padding;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class ChainCapacityTest {
	private static final long NOW = 1_750_000_000_000L;
	private static final ChainId CHAIN = ChainFixtures.chainId(1);
	private static final WriterKey WRITER = ChainFixtures.key(1);
	private static final int ENTRY_SIZE = ChainFixtures.ENTRY_SIZE;

	/* Room for four entries of the only class of the chain, and for one more in the reserve */
	private static final ChainLimits FOUR_ENTRIES = new ChainLimits(4L * ENTRY_SIZE, 30, ENTRY_SIZE);

	/* Room for eight entries of the larger of two classes, and a reserve of two entries of the smaller one */
	private static final ChainLimits TWO_CLASSES = new ChainLimits(8L * 4 * ENTRY_SIZE, 30, 4 * ENTRY_SIZE);

	private static List<Entry> feed(ChainLimits limits, int... payloadBytes) {
		return feed(WRITER, NOW, limits, payloadBytes);
	}

	/* A feed whose entries carry payloads of the given sizes, each padded to its class, one second apart */
	private static List<Entry> feed(WriterKey key, long timestamp, ChainLimits limits, int... payloadBytes) {
		final List<Entry> entries = new ArrayList<>();
		long prev = 0;

		for (int seq = 0; seq < payloadBytes.length; seq++) {
			final Entry entry = key.sign(new EntryHeader(CHAIN, key.id(), seq, prev,
				timestamp + seq * Entry.STAMP_MILLIS, ChainFixtures.TAG),
				Padding.padded(new byte[payloadBytes[seq]], limits.maxEntryBytes()));

			entries.add(entry);
			prev = entry.hash();
		}

		return entries;
	}

	private static int[] large(int count, int... thenSmall) {
		return IntStream.concat(IntStream.generate(() -> ENTRY_SIZE).limit(count), IntStream.of(thenSmall)).toArray();
	}

	/* Openings and answers fill the smallest class, so a chain full of larger entries still lets sessions open,
	   answer and close, until the reserve is full too. Nothing is trimmed for the reserve. */
	@Example
	public void smallEntriesUseTheReserve() {
		final Chain chain = new Chain(CHAIN, TWO_CLASSES, () -> NOW);
		final List<Entry> entries = feed(TWO_CLASSES, large(8, 0, 0, 0));
		final Entry anotherLarge = feed(TWO_CLASSES, large(9)).get(8);

		assertThat(TWO_CLASSES.reserveBytes(), is(2L * ENTRY_SIZE));
		entries.subList(0, 8).forEach(entry -> assertThat(chain.offer(entry).admission(), is(Admission.ACCEPTED)));
		assertThat(chain.bytes(), is(TWO_CLASSES.capacityBytes()));
		assertThat(chain.offer(anotherLarge), equalTo(new Offer(Admission.FULL, List.of())));
		assertThat(chain.offer(entries.get(8)).admission(), is(Admission.ACCEPTED));
		assertThat(chain.offer(entries.get(9)).admission(), is(Admission.ACCEPTED));
		assertThat(chain.offer(entries.get(10)), equalTo(new Offer(Admission.FULL, List.of())));
		assertThat(chain.bytes(), is(TWO_CLASSES.capacityBytes() + TWO_CLASSES.reserveBytes()));
		assertThat(chain.entries(), equalTo(entries.subList(0, 10)));
	}

	/* Larger entries that waited for a gap come in unchecked once a small entry fills it. Were they kept past the
	   capacity, they would take the reserve until they aged out and turn away every opening and answer meanwhile. */
	@Example
	public void shouldKeepLargerEntriesReleasedFromTheBufferOutOfTheReserve() {
		final ChainLimits limits = new ChainLimits(16L * 4 * ENTRY_SIZE, 30, 4 * ENTRY_SIZE);
		final Chain chain = new Chain(CHAIN, limits, () -> NOW);
		final List<Entry> waiting = feed(WRITER, NOW, limits, 0, 0, ENTRY_SIZE, ENTRY_SIZE);
		final List<Entry> others = feed(ChainFixtures.key(2), NOW + 4000, limits, large(15));
		final Entry opening = feed(ChainFixtures.key(3), NOW + 20_000, limits, 0).get(0);

		chain.offer(waiting.get(0));
		assertThat(chain.offer(waiting.get(2)).admission(), is(Admission.AHEAD));
		assertThat(chain.offer(waiting.get(3)).admission(), is(Admission.AHEAD));
		others.forEach(entry -> assertThat(chain.offer(entry).admission(), is(Admission.ACCEPTED)));
		assertThat(chain.offer(waiting.get(1)).appended(), equalTo(waiting.subList(1, 4)));
		assertThat(ChainFixtures.largerBytes(chain), is(limits.capacityBytes()));
		assertThat(chain.offer(opening).admission(), is(Admission.ACCEPTED));
		assertThat(chain.bytes(), lessThanOrEqualTo(limits.capacityBytes() + limits.reserveBytes()));
	}

	@Example
	public void shouldKeepTheReserveForTheSmallestClassOnly() {
		final Chain chain = new Chain(CHAIN, TWO_CLASSES, () -> NOW);
		final List<Entry> entries = feed(TWO_CLASSES, large(7, 0, 0));
		final Entry largeAfterSmall = feed(TWO_CLASSES, large(7, 0, ENTRY_SIZE)).get(8);

		entries.subList(0, 8).forEach(chain::offer);
		assertThat(chain.offer(largeAfterSmall).admission(), is(Admission.FULL));
		assertThat(chain.offer(entries.get(8)).admission(), is(Admission.ACCEPTED));
		assertThat(chain.bytes(), is(TWO_CLASSES.capacityBytes() - 2L * ENTRY_SIZE));
	}

	@Property(tries = 5)
	public void shouldRefuseEntriesOnceTheChainAndItsReserveAreFull(@ForAll @IntRange(min = 1, max = 5) int attempts) {
		final Chain chain = new Chain(CHAIN, FOUR_ENTRIES, () -> NOW);
		final List<Entry> entries = ChainFixtures.linked(WRITER, CHAIN, 6, NOW);

		entries.subList(0, 5).forEach(entry -> assertThat(chain.offer(entry).admission(), is(Admission.ACCEPTED)));

		for (int i = 0; i < attempts; i++) {
			assertThat(chain.offer(entries.get(5)), equalTo(new Offer(Admission.FULL, List.of())));
		}

		assertThat(chain.entries(), equalTo(entries.subList(0, 5)));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(4, entries.get(4).hash()))));
	}

	@Example
	public void shouldTakeTheRefusedEntryOnceTheOldOnesExpire() {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, FOUR_ENTRIES, clock::get);
		final List<Entry> entries = ChainFixtures.linked(WRITER, CHAIN, 5, NOW);
		final Entry refused = ChainFixtures.signed(WRITER, CHAIN, 5, entries.get(4).hash(), NOW + 20_000);

		entries.forEach(chain::offer);
		assertThat(chain.offer(refused).admission(), is(Admission.FULL));
		clock.set(NOW + 30_001);
		assertThat(chain.offer(refused).admission(), is(Admission.ACCEPTED));
		assertThat(chain.entries(), equalTo(List.of(entries.get(1), entries.get(2), entries.get(3), entries.get(4),
			refused)));
	}

	@Example
	public void shouldRefuseALargerEntryThatIsOneByteOverTheCapacity() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(4L * ENTRY_SIZE, 30, ENTRY_SIZE + 1), () -> NOW);
		final List<Entry> entries = ChainFixtures.linked(WRITER, CHAIN, 3, NOW);
		final long prev = entries.get(2).hash();
		final Entry oneTooLarge = WRITER.sign(new EntryHeader(CHAIN, WRITER.id(), 3, prev, NOW + 3000, ChainFixtures.TAG),
			Padding.padded(new byte[ENTRY_SIZE + 1 - Entry.OVERHEAD], ENTRY_SIZE + 1));
		final Entry fits = ChainFixtures.signed(WRITER, CHAIN, 3, prev, NOW + 3000);

		entries.forEach(chain::offer);
		assertThat(chain.offer(oneTooLarge).admission(), is(Admission.FULL));
		assertThat(chain.offer(fits).admission(), is(Admission.ACCEPTED));
		assertThat(chain.bytes(), is(4L * ENTRY_SIZE));
	}

	@Example
	public void shouldLeaveNoTraceOfARefusedEntryFromAnotherWriter() {
		final WriterKey other = ChainFixtures.key(2);
		final Chain chain = new Chain(CHAIN, FOUR_ENTRIES, () -> NOW);
		final List<Entry> entries = ChainFixtures.linked(WRITER, CHAIN, 5, NOW);

		entries.forEach(chain::offer);
		assertThat(chain.offer(ChainFixtures.signed(other, CHAIN, 0, 0, NOW + 5000)).admission(),
			is(Admission.FULL));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(4, entries.get(4).hash()))));
		assertThat(chain.bytes(), is(5L * ENTRY_SIZE));
		assertThat(chain.range(other.id(), 0, 0), is(empty()));
		assertThat(chain.anchor(other.id(), new Head(0, 1)), is(true));
	}

	@Example
	public void shouldAnswerFromTheFeedBeforeRefusingForRoom() {
		final Chain chain = new Chain(CHAIN, FOUR_ENTRIES, () -> NOW);
		final List<Entry> entries = ChainFixtures.linked(WRITER, CHAIN, 5, NOW);

		entries.forEach(chain::offer);
		assertThat(chain.offer(entries.get(4)).admission(), is(Admission.DUPLICATE));
		assertThat(chain.offer(entries.get(0)).admission(), is(Admission.REPLAYED));
	}

	@Example
	public void shouldNotAnswerFullToAnEntryThatIsNotAuthentic() {
		final Chain chain = new Chain(CHAIN, FOUR_ENTRIES, () -> NOW);
		final List<Entry> entries = ChainFixtures.linked(WRITER, CHAIN, 6, NOW);
		final Entry genuine = entries.get(5);
		final Entry tampered = new Entry(genuine.header(), genuine.payload(), genuine.hash() + 1, genuine.signature());

		entries.subList(0, 5).forEach(chain::offer);
		assertThat(chain.offer(tampered).admission(), is(Admission.NOT_AUTHENTIC));
		assertThat(chain.entries(), equalTo(entries.subList(0, 5)));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(4, entries.get(4).hash()))));
		assertThat(chain.bytes(), is(5L * ENTRY_SIZE));
		assertThat(chain.offer(genuine).admission(), is(Admission.FULL));
	}
}
