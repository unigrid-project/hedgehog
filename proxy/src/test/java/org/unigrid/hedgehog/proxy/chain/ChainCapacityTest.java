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

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class ChainCapacityTest {
	private static final long NOW = 1_750_000_000_000L;
	private static final ChainId CHAIN = ChainFixtures.chainId(1);
	private static final WriterKey WRITER = ChainFixtures.key(1);
	private static final int ENTRY_SIZE = Entry.OVERHEAD + 1;
	private static final ChainLimits FOUR_ENTRIES = new ChainLimits(4L * ENTRY_SIZE, 30, ENTRY_SIZE);

	@Property(tries = 5)
	public void shouldRefuseSealedEntriesOnceFull(@ForAll @IntRange(min = 1, max = 5) int attempts) {
		final Chain chain = new Chain(CHAIN, FOUR_ENTRIES, () -> NOW);
		final List<Entry> sealed = ChainFixtures.linked(WRITER, CHAIN, 5, NOW, EntryKind.SEALED);

		sealed.subList(0, 4).forEach(entry -> assertThat(chain.offer(entry).admission(), is(Admission.ACCEPTED)));

		for (int i = 0; i < attempts; i++) {
			assertThat(chain.offer(sealed.get(4)), equalTo(new Offer(Admission.FULL, List.of())));
		}

		assertThat(chain.entries(), equalTo(sealed.subList(0, 4)));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(3, sealed.get(3).hash()))));
	}

	@Example
	public void shouldStillTakeOtherKindsWhenFull() {
		final Chain chain = new Chain(CHAIN, FOUR_ENTRIES, () -> NOW);
		final List<Entry> sealed = ChainFixtures.linked(WRITER, CHAIN, 4, NOW, EntryKind.SEALED);
		final Entry leave = ChainFixtures.signed(WRITER, CHAIN, 4, sealed.get(3).hash(), NOW + 4, EntryKind.LEAVE);

		sealed.forEach(chain::offer);
		assertThat(chain.offer(leave).admission(), is(Admission.ACCEPTED));
		assertThat(chain.entries(), equalTo(List.of(sealed.get(1), sealed.get(2), sealed.get(3), leave)));
	}

	@Example
	public void shouldTakeTheRefusedEntryOnceTheOldOnesExpire() {
		final AtomicLong clock = new AtomicLong(NOW);
		final Chain chain = new Chain(CHAIN, FOUR_ENTRIES, clock::get);
		final List<Entry> sealed = ChainFixtures.linked(WRITER, CHAIN, 4, NOW, EntryKind.SEALED);
		final Entry refused = ChainFixtures.signed(WRITER, CHAIN, 4, sealed.get(3).hash(), NOW + 20_000,
			EntryKind.SEALED);

		sealed.forEach(chain::offer);
		assertThat(chain.offer(refused).admission(), is(Admission.FULL));
		clock.set(NOW + 30_001);
		assertThat(chain.offer(refused).admission(), is(Admission.ACCEPTED));
		assertThat(chain.entries(), equalTo(List.of(sealed.get(1), sealed.get(2), sealed.get(3), refused)));
	}

	@Property(tries = 4, generation = GenerationMode.EXHAUSTIVE)
	public void shouldTakeEveryOtherKindWhenFull(@ForAll("unsealedKinds") EntryKind kind) {
		final Chain chain = new Chain(CHAIN, FOUR_ENTRIES, () -> NOW);
		final List<Entry> sealed = ChainFixtures.linked(WRITER, CHAIN, 4, NOW, EntryKind.SEALED);
		final Entry other = ChainFixtures.signed(WRITER, CHAIN, 4, sealed.get(3).hash(), NOW + 4, kind);

		sealed.forEach(chain::offer);
		assertThat(chain.offer(other), equalTo(new Offer(Admission.ACCEPTED, List.of(other))));
		assertThat(chain.entries(), equalTo(List.of(sealed.get(1), sealed.get(2), sealed.get(3), other)));
	}

	@Provide
	Arbitrary<EntryKind> unsealedKinds() {
		return Arbitraries.of(EnumSet.complementOf(EnumSet.of(EntryKind.SEALED)));
	}

	@Example
	public void shouldRefuseASealedEntryThatIsOneByteTooLarge() {
		final Chain chain = new Chain(CHAIN, new ChainLimits(4L * ENTRY_SIZE, 30, ENTRY_SIZE + 1), () -> NOW);
		final List<Entry> sealed = ChainFixtures.linked(WRITER, CHAIN, 3, NOW, EntryKind.SEALED);
		final long prev = sealed.get(2).hash();
		final Entry oneTooLarge = WRITER.sign(new EntryHeader(CHAIN, WRITER.id(), 3, prev, NOW + 3, Fingerprint.NONE,
			EntryKind.SEALED), new byte[2]);
		final Entry fits = ChainFixtures.signed(WRITER, CHAIN, 3, prev, NOW + 3, EntryKind.SEALED);

		sealed.forEach(chain::offer);
		assertThat(chain.offer(oneTooLarge).admission(), is(Admission.FULL));
		assertThat(chain.offer(fits).admission(), is(Admission.ACCEPTED));
		assertThat(chain.bytes(), is(4L * ENTRY_SIZE));
	}

	@Example
	public void shouldLeaveNoTraceOfARefusedSealedEntryFromAnotherWriter() {
		final WriterKey other = ChainFixtures.key(2);
		final Chain chain = new Chain(CHAIN, FOUR_ENTRIES, () -> NOW);
		final List<Entry> sealed = ChainFixtures.linked(WRITER, CHAIN, 4, NOW, EntryKind.SEALED);

		sealed.forEach(chain::offer);
		assertThat(chain.offer(ChainFixtures.signed(other, CHAIN, 0, 0, NOW + 4, EntryKind.SEALED)).admission(),
			is(Admission.FULL));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(3, sealed.get(3).hash()))));
		assertThat(chain.bytes(), is(4L * ENTRY_SIZE));
		assertThat(chain.range(other.id(), 0, 0), is(empty()));
		assertThat(chain.anchor(other.id(), new Head(0, 1)), is(true));
	}

	@Example
	public void shouldAnswerFromTheFeedBeforeRefusingForRoom() {
		final Chain chain = new Chain(CHAIN, FOUR_ENTRIES, () -> NOW);
		final List<Entry> sealed = ChainFixtures.linked(WRITER, CHAIN, 4, NOW, EntryKind.SEALED);

		sealed.forEach(chain::offer);
		assertThat(chain.offer(sealed.get(3)).admission(), is(Admission.DUPLICATE));
		assertThat(chain.offer(sealed.get(0)).admission(), is(Admission.REPLAYED));
	}

	@Example
	public void shouldNotAnswerFullToAnEntryThatIsNotAuthentic() {
		final Chain chain = new Chain(CHAIN, FOUR_ENTRIES, () -> NOW);
		final List<Entry> sealed = ChainFixtures.linked(WRITER, CHAIN, 5, NOW, EntryKind.SEALED);
		final Entry genuine = sealed.get(4);
		final Entry tampered = new Entry(genuine.header(), genuine.payload(), genuine.hash() + 1, genuine.signature());

		sealed.subList(0, 4).forEach(chain::offer);
		assertThat(chain.offer(tampered).admission(), is(Admission.NOT_AUTHENTIC));
		assertThat(chain.entries(), equalTo(sealed.subList(0, 4)));
		assertThat(chain.heads(), equalTo(Map.of(WRITER.id(), new Head(3, sealed.get(3).hash()))));
		assertThat(chain.bytes(), is(4L * ENTRY_SIZE));
		assertThat(chain.offer(genuine).admission(), is(Admission.FULL));
	}
}
