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
import java.util.Random;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.proxy.Padding;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class ChainCheckTest {
	private static final long NOW = 1_750_000_000_000L;
	private static final ChainId CHAIN = ChainFixtures.chainId(1);
	private static final WriterKey WRITER = ChainFixtures.key(1);
	private static final int ENTRY_SIZE = ChainFixtures.ENTRY_SIZE;

	/* Room for four entries of the only class of the chain, and for one more in the reserve */
	private static final ChainLimits FOUR_ENTRIES = new ChainLimits(4L * ENTRY_SIZE, 30, ENTRY_SIZE);

	/* Room for eight entries of the larger of two classes, and a reserve of two entries of the smaller one */
	private static final ChainLimits TWO_CLASSES = new ChainLimits(8L * 4 * ENTRY_SIZE, 30, 4 * ENTRY_SIZE);

	private record Snapshot(List<Entry> entries, Object heads, long bytes, long heldBytes) {
		private static Snapshot of(Chain chain) {
			return new Snapshot(chain.entries(), chain.heads(), chain.bytes(), chain.heldBytes());
		}
	}

	/* Entries of two writers in a random order, some of them twice, past a gap, without a signature or of another
	   chain, so that every answer of a chain turns up */
	private static List<Entry> mixed(Random random) {
		final List<Entry> pool = new ArrayList<>(ChainFixtures.linked(WRITER, CHAIN, 6, NOW));
		final Entry genuine = pool.get(3);

		pool.addAll(ChainFixtures.linked(ChainFixtures.key(2), CHAIN, 4, NOW));
		pool.add(new Entry(genuine.header(), genuine.payload(), genuine.hash(), new byte[Entry.SIGNATURE_SIZE]));
		pool.add(ChainFixtures.signed(ChainFixtures.key(3), ChainFixtures.chainId(2), 0, 0, NOW));

		final List<Entry> mixed = new ArrayList<>();

		for (int i = 0; i < 24; i++) {
			mixed.add(pool.get(random.nextInt(pool.size())));
		}

		return mixed;
	}

	@Property(tries = 30)
	public void answersAsAnOfferWouldAndTakesNothingIn(@ForAll Random random) {
		final Chain checked = new Chain(CHAIN, FOUR_ENTRIES, () -> NOW);
		final Chain offered = new Chain(CHAIN, FOUR_ENTRIES, () -> NOW);

		for (final Entry entry : mixed(random)) {
			final Snapshot before = Snapshot.of(checked);
			final Admission admission = checked.check(entry, 0);

			assertThat(Snapshot.of(checked), equalTo(before));
			assertThat(admission, is(offered.offer(entry).admission()));
			checked.offer(entry);
		}
	}

	@Property(tries = 10)
	public void countsWhatIsPendingAgainstTheRoom(@ForAll @IntRange(min = 0, max = 4) int appended) {
		final Chain chain = new Chain(CHAIN, FOUR_ENTRIES, () -> NOW);
		final List<Entry> entries = ChainFixtures.linked(WRITER, CHAIN, appended + 1, NOW);
		final long room = FOUR_ENTRIES.capacityBytes() + FOUR_ENTRIES.reserveBytes() - (long) appended * ENTRY_SIZE;
		final Entry next = entries.get(appended);

		entries.subList(0, appended).forEach(chain::offer);
		assertThat(chain.check(next, room - ENTRY_SIZE), is(Admission.ACCEPTED));
		assertThat(chain.check(next, room - ENTRY_SIZE + 1), is(Admission.FULL));
	}

	@Example
	public void leavesTheReserveToTheSmallestClassAlone() {
		final Chain chain = new Chain(CHAIN, TWO_CLASSES, () -> NOW);
		final long prev = 0;
		final Entry large = WRITER.sign(new EntryHeader(CHAIN, WRITER.id(), 0, prev, NOW, ChainFixtures.TAG),
			Padding.padded(new byte[ENTRY_SIZE], TWO_CLASSES.maxEntryBytes()));
		final Entry small = WRITER.sign(new EntryHeader(CHAIN, WRITER.id(), 0, prev, NOW, ChainFixtures.TAG),
			Padding.padded(new byte[0], TWO_CLASSES.maxEntryBytes()));
		final long pending = TWO_CLASSES.capacityBytes() - small.size();

		assertThat(chain.check(large, pending), is(Admission.FULL));
		assertThat(chain.check(small, pending), is(Admission.ACCEPTED));
		assertThat(chain.check(small, pending + TWO_CLASSES.reserveBytes()), is(Admission.ACCEPTED));
		assertThat(chain.check(small, pending + TWO_CLASSES.reserveBytes() + 1), is(Admission.FULL));
	}

	@Example
	public void checksAnEntryPastAGapAgainstTheRoomToo() {
		final Chain chain = new Chain(CHAIN, FOUR_ENTRIES, () -> NOW);
		final List<Entry> entries = ChainFixtures.linked(WRITER, CHAIN, 3, NOW);
		final long room = FOUR_ENTRIES.capacityBytes() + FOUR_ENTRIES.reserveBytes() - ENTRY_SIZE;

		chain.offer(entries.get(0));
		assertThat(chain.check(entries.get(2), room - ENTRY_SIZE), is(Admission.AHEAD));
		assertThat(chain.check(entries.get(2), room), is(Admission.FULL));
		assertThat(chain.heldBytes(), is(0L));
	}
}
