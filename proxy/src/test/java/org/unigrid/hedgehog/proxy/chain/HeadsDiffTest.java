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

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class HeadsDiffTest {
	private static final long NOW = 1_750_000_000_000L;
	private static final ChainId CHAIN = ChainFixtures.chainId(1);
	private static final ChainLimits LIMITS = new ChainLimits(1024 * 1024, 30, ChainFixtures.MAX_ENTRY_BYTES);

	private static WriterId writer(int number) {
		final byte[] bytes = new byte[WriterId.SIZE];

		Arrays.fill(bytes, (byte) number);
		return new WriterId(bytes);
	}

	@Provide
	public Arbitrary<Map<WriterId, Head>> heads() {
		return Arbitraries.maps(Arbitraries.integers().between(1, 6).map(HeadsDiffTest::writer),
			Arbitraries.longs().between(0, 40).map(seq -> new Head(seq, seq))).ofMaxSize(6);
	}

	@Property(tries = 300)
	public void shouldAskForWhatFollowsTheLocalHead(@ForAll("heads") Map<WriterId, Head> local,
		@ForAll("heads") Map<WriterId, Head> remote, @ForAll @IntRange(min = 1, max = 10) int maxCount) {

		for (MissingRange range : HeadsDiff.missing(local, remote, maxCount)) {
			final long localSeq = local.containsKey(range.writer()) ? local.get(range.writer()).seq() : -1;

			assertThat(range.fromSeq(), is(localSeq + 1));
			assertThat(range.toSeq(), lessThanOrEqualTo(remote.get(range.writer()).seq()));
			assertThat(range.count(), lessThanOrEqualTo((long) maxCount));
		}
	}

	@Property(tries = 300)
	public void shouldNameEveryWriterTheRemoteIsAheadOn(@ForAll("heads") Map<WriterId, Head> local,
		@ForAll("heads") Map<WriterId, Head> remote) {
		final List<WriterId> behind = remote.keySet().stream().filter(writer -> !local.containsKey(writer)
			|| local.get(writer).seq() < remote.get(writer).seq()).sorted().toList();

		assertThat(HeadsDiff.missing(local, remote, 5).stream().map(MissingRange::writer).toList(), equalTo(behind));
	}

	@Property(tries = 100)
	public void shouldCatchUpInRoundsOfAtMostTheMaximum(@ForAll("heads") Map<WriterId, Head> local,
		@ForAll("heads") Map<WriterId, Head> remote, @ForAll @IntRange(min = 1, max = 10) int maxCount) {
		final Map<WriterId, Head> catching = new HashMap<>(local);
		List<MissingRange> missing = HeadsDiff.missing(catching, remote, maxCount);
		int rounds = 0;

		while (!missing.isEmpty()) {
			missing.forEach(range -> catching.put(range.writer(), new Head(range.toSeq(), range.toSeq())));
			missing = HeadsDiff.missing(catching, remote, maxCount);
			rounds++;
		}

		assertThat(rounds, lessThanOrEqualTo(41 / maxCount + 1));
		remote.forEach((writer, head) -> assertThat(catching.get(writer).seq(), greaterThanOrEqualTo(head.seq())));
	}

	@Example
	public void shouldFindNothingWhenTheHeadsAgree() {
		final Map<WriterId, Head> heads = Map.of(writer(1), new Head(4, 7), writer(2), new Head(0, 9));

		assertThat(HeadsDiff.missing(heads, heads, 10), is(empty()));
		assertThat(HeadsDiff.missing(heads, Map.of(), 10), is(empty()));
	}

	@Example
	public void shouldStartAnUnknownWriterAtItsFirstEntry() {
		assertThat(HeadsDiff.missing(Map.of(), Map.of(writer(3), new Head(2, 5)), 10),
			equalTo(List.of(new MissingRange(writer(3), 0, 2))));
	}

	@Example
	public void shouldBoundARangeToTheMaximumCount() {
		final Map<WriterId, Head> local = Map.of(writer(1), new Head(9, 1));
		final Map<WriterId, Head> remote = Map.of(writer(1), new Head(99, 2));

		assertThat(HeadsDiff.missing(local, remote, 25), equalTo(List.of(new MissingRange(writer(1), 10, 34))));
	}

	@Example
	public void shouldAskForOneEntryAtATimeAtTheSmallestBound() {
		final Map<WriterId, Head> remote = Map.of(writer(1), new Head(99, 2));

		assertThat(HeadsDiff.missing(Map.of(writer(1), new Head(9, 1)), remote, 1),
			equalTo(List.of(new MissingRange(writer(1), 10, 10))));
	}

	@Example
	public void shouldStopAtTheRemoteHeadWhenFewerThanTheMaximumAreMissing() {
		final Map<WriterId, Head> remote = Map.of(writer(1), new Head(12, 2));

		assertThat(HeadsDiff.missing(Map.of(writer(1), new Head(9, 1)), remote, 25),
			equalTo(List.of(new MissingRange(writer(1), 10, 12))));
	}

	@Example
	public void shouldReachTheLargestSequenceNumber() {
		final Map<WriterId, Head> local = Map.of(writer(1), new Head(Long.MAX_VALUE - 1, 1));
		final Map<WriterId, Head> remote = Map.of(writer(1), new Head(Long.MAX_VALUE, 2));

		assertThat(HeadsDiff.missing(local, remote, 10),
			equalTo(List.of(new MissingRange(writer(1), Long.MAX_VALUE, Long.MAX_VALUE))));
	}

	@Example
	public void shouldIgnoreAForkAtTheSameSequenceNumber() {
		final Map<WriterId, Head> local = Map.of(writer(1), new Head(5, 1));
		final Map<WriterId, Head> remote = Map.of(writer(1), new Head(5, 2));

		assertThat(HeadsDiff.missing(local, remote, 10), is(empty()));
	}

	@Example
	public void shouldAskForNothingWhenTheLocalHeadIsAhead() {
		final Map<WriterId, Head> local = Map.of(writer(1), new Head(9, 1));
		final Map<WriterId, Head> remote = Map.of(writer(1), new Head(3, 2));

		assertThat(HeadsDiff.missing(local, remote, 10), is(empty()));
	}

	@Example
	public void shouldOrderTheRangesByWriter() {
		final Map<WriterId, Head> remote = new LinkedHashMap<>();

		remote.put(writer(3), new Head(1, 1));
		remote.put(writer(1), new Head(1, 1));
		remote.put(writer(2), new Head(1, 1));

		assertThat(HeadsDiff.missing(Map.of(), remote, 10), equalTo(List.of(new MissingRange(writer(1), 0, 1),
			new MissingRange(writer(2), 0, 1), new MissingRange(writer(3), 0, 1))));
	}

	@Example
	public void shouldAskForTheFirstEntryOfAnUnknownWriterThatHasOnlyThat() {
		assertThat(HeadsDiff.missing(Map.of(), Map.of(writer(3), new Head(0, 5)), 10),
			equalTo(List.of(new MissingRange(writer(3), 0, 0))));
	}

	@Example
	public void shouldFillARangeWhenExactlyTheMaximumRemains() {
		final Map<WriterId, Head> local = Map.of(writer(1), new Head(9, 1));
		final Map<WriterId, Head> remote = Map.of(writer(1), new Head(19, 2));

		assertThat(HeadsDiff.missing(local, remote, 10), equalTo(List.of(new MissingRange(writer(1), 10, 19))));
	}

	@Example
	public void shouldBoundTheRangeOfAnUnknownWriterWhoseHeadIsTheLargestSequenceNumber() {
		final Map<WriterId, Head> remote = Map.of(writer(1), new Head(Long.MAX_VALUE, 0));

		assertThat(HeadsDiff.missing(Map.of(), remote, 10), equalTo(List.of(new MissingRange(writer(1), 0, 9))));
	}

	@Example
	public void shouldAcceptTheLargestBound() {
		final Map<WriterId, Head> remote = Map.of(writer(1), new Head(Long.MAX_VALUE, 0));

		assertThat(HeadsDiff.missing(Map.of(), remote, Integer.MAX_VALUE),
			equalTo(List.of(new MissingRange(writer(1), 0, Integer.MAX_VALUE - 1))));
	}

	@Example
	public void shouldBoundADistanceBeyondAnInt() {
		final Map<WriterId, Head> remote = Map.of(writer(1), new Head((1L << 32) + 5, 0));

		assertThat(HeadsDiff.missing(Map.of(), remote, 10), equalTo(List.of(new MissingRange(writer(1), 0, 9))));
	}

	@Example
	public void shouldRefuseMissingHeads() {
		assertThat(Expect.assertThrows(NullPointerException.class, () -> HeadsDiff.missing(null, Map.of(), 10))
			.getMessage(), is("local"));
		assertThat(Expect.assertThrows(NullPointerException.class, () -> HeadsDiff.missing(Map.of(), null, 10))
			.getMessage(), is("remote"));
	}

	@Example
	public void shouldBringAChainLevelWithAPeer() {
		final Chain behind = new Chain(CHAIN, LIMITS, () -> NOW);
		final Chain ahead = new Chain(CHAIN, LIMITS, () -> NOW);
		final List<Entry> first = ChainFixtures.linked(ChainFixtures.key(1), CHAIN, 12, NOW, EntryKind.SEALED);
		final List<Entry> second = ChainFixtures.linked(ChainFixtures.key(2), CHAIN, 3, NOW, EntryKind.SEALED);

		first.forEach(ahead::offer);
		second.forEach(ahead::offer);
		first.subList(0, 2).forEach(behind::offer);

		for (int round = 0; round < 3; round++) {
			HeadsDiff.missing(behind.heads(), ahead.heads(), 4).forEach(range -> ahead.range(range.writer(),
				range.fromSeq(), range.toSeq()).forEach(behind::offer));
		}

		assertThat(behind.heads(), equalTo(ahead.heads()));
		assertThat(behind.entries(), equalTo(ahead.entries()));
	}

	@Example
	public void shouldRefuseAnEmptyBound() {
		Expect.assertThrows(IllegalArgumentException.class, () -> HeadsDiff.missing(Map.of(), Map.of(), 0));
		Expect.assertThrows(IllegalArgumentException.class, () -> HeadsDiff.missing(Map.of(), Map.of(), -1));
	}

	@Example
	public void shouldRefuseARangeThatRunsBackwards() {
		Expect.assertThrows(IllegalArgumentException.class, () -> new MissingRange(writer(1), 5, 4));
		Expect.assertThrows(IllegalArgumentException.class, () -> new MissingRange(writer(1), -1, 4));
	}

	@Example
	public void shouldRefuseARangeWhoseCountDoesNotFitALong() {
		Expect.assertThrows(IllegalArgumentException.class, () -> new MissingRange(writer(1), 0, Long.MAX_VALUE));
	}

	@Example
	public void shouldCountTheLargestRangesThatFitALong() {
		assertThat(new MissingRange(writer(1), 1, Long.MAX_VALUE).count(), is(Long.MAX_VALUE));
		assertThat(new MissingRange(writer(1), 0, Long.MAX_VALUE - 1).count(), is(Long.MAX_VALUE));
	}

	@Example
	public void shouldRefuseARangeWithoutAWriter() {
		Expect.assertThrows(NullPointerException.class, () -> new MissingRange(null, 0, 1));
	}

	@Example
	public void shouldCountBothEndsOfARange() {
		assertThat(new MissingRange(writer(1), 4, 4).count(), is(1L));
		assertThat(new MissingRange(writer(1), 0, 2).count(), is(3L));
	}
}
