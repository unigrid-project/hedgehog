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
import java.util.Optional;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class FeedTest {
	private static final long NOW = 1_750_000_000_000L;
	private static final ChainId CHAIN = ChainFixtures.chainId(1);
	private static final WriterKey WRITER = ChainFixtures.key(1);

	private static List<Entry> linked(int count) {
		return ChainFixtures.linked(WRITER, CHAIN, count, NOW);
	}

	private static void assertAt(Feed feed, Optional<Head> head, long nextSeq) {
		assertThat(feed.head(), equalTo(head));
		assertThat(feed.nextSeq(), is(nextSeq));
	}

	@Property(tries = 20)
	public void shouldAppendEveryLinkInOrder(@ForAll @IntRange(min = 1, max = 30) int count) {
		final List<Entry> entries = linked(count);
		final Feed feed = new Feed(WRITER.id());
		final Entry last = entries.get(count - 1);

		entries.forEach(entry -> assertThat(feed.append(entry), is(Link.NEXT)));
		assertThat(feed.head(), equalTo(Optional.of(new Head(count - 1, last.hash()))));
		assertThat(feed.nextSeq(), is((long) count));
	}

	/* Nothing follows the last entry of a feed, neither the next entry nor one past it, while the last entry itself
	   and those before it are still known */
	@Example
	public void shouldCallWhatFollowsTheLastEntryBroken() {
		final FeedWriter writer = new FeedWriter(WRITER, CHAIN);
		final Entry first = writer.write(NOW, ChainFixtures.TAG, new byte[0]);
		final Entry last = writer.writeLast(NOW, ChainFixtures.TAG, new byte[0]);
		final Feed feed = new Feed(WRITER.id());

		feed.append(first);
		assertThat(feed.hasEnded(), is(false));
		assertThat(feed.append(last), is(Link.NEXT));
		assertThat(feed.hasEnded(), is(true));
		assertThat(feed.check(ChainFixtures.signed(WRITER, CHAIN, 2, last.hash(), NOW)), is(Link.BROKEN));
		assertThat(feed.check(ChainFixtures.signed(WRITER, CHAIN, 3, 0, NOW)), is(Link.BROKEN));
		assertThat(feed.check(last), is(Link.DUPLICATE));
		assertThat(feed.check(first), is(Link.REPLAYED));
	}

	@Example
	public void shouldStartWithSequenceZeroAndNoPrevious() {
		final Feed feed = new Feed(WRITER.id());

		assertThat(feed.head(), equalTo(Optional.empty()));
		assertThat(feed.check(ChainFixtures.signed(WRITER, CHAIN, 0, 1, NOW)), is(Link.BROKEN));
		assertThat(feed.check(ChainFixtures.signed(WRITER, CHAIN, 1, 0, NOW)), is(Link.AHEAD));
		assertThat(feed.check(ChainFixtures.signed(WRITER, CHAIN, 0, 0, NOW)), is(Link.NEXT));
		assertAt(feed, Optional.empty(), 0);
	}

	@Example
	public void shouldCallTheHeadAgainADuplicate() {
		final List<Entry> entries = linked(3);
		final Feed feed = new Feed(WRITER.id());

		entries.forEach(feed::append);
		assertThat(feed.append(entries.get(2)), is(Link.DUPLICATE));
		assertThat(feed.head(), equalTo(Optional.of(new Head(2, entries.get(2).hash()))));
	}

	@Example
	public void shouldCallAnOlderEntryAReplay() {
		final List<Entry> entries = linked(3);
		final Feed feed = new Feed(WRITER.id());

		entries.forEach(feed::append);
		assertThat(feed.append(entries.get(0)), is(Link.REPLAYED));
		assertThat(feed.append(entries.get(1)), is(Link.REPLAYED));
		assertThat(feed.nextSeq(), is(3L));
	}

	@Example
	public void shouldCallAnEntryPastTheNextOneAhead() {
		final List<Entry> entries = linked(4);
		final Feed feed = new Feed(WRITER.id());

		feed.append(entries.get(0));
		assertThat(feed.append(entries.get(2)), is(Link.AHEAD));
		assertThat(feed.append(entries.get(3)), is(Link.AHEAD));
		assertThat(feed.nextSeq(), is(1L));
	}

	@Example
	public void shouldCallAWrongPreviousHashBroken() {
		final List<Entry> entries = linked(2);
		final Feed feed = new Feed(WRITER.id());

		feed.append(entries.get(0));
		assertThat(feed.append(ChainFixtures.signed(WRITER, CHAIN, 1, entries.get(0).hash() + 1, NOW + 1000)),
			is(Link.BROKEN));
		assertAt(feed, Optional.of(new Head(0, entries.get(0).hash())), 1);
	}

	@Example
	public void shouldCallAnotherEntryAtTheHeadBroken() {
		final List<Entry> entries = linked(2);
		final Feed feed = new Feed(WRITER.id());

		entries.forEach(feed::append);
		assertThat(feed.append(ChainFixtures.signed(WRITER, CHAIN, 1, entries.get(0).hash(), NOW + 2000)),
			is(Link.BROKEN));
		assertAt(feed, Optional.of(new Head(1, entries.get(1).hash())), 2);
	}

	@Example
	public void shouldCallTimeRunningBackwardsBroken() {
		final List<Entry> entries = linked(1);
		final Feed feed = new Feed(WRITER.id());

		feed.append(entries.get(0));
		assertThat(feed.append(ChainFixtures.signed(WRITER, CHAIN, 1, entries.get(0).hash(), NOW - 1000)),
			is(Link.BROKEN));
		assertAt(feed, Optional.of(new Head(0, entries.get(0).hash())), 1);
	}

	@Example
	public void shouldCallTheTimeOfTheEntryBeforeNext() {
		final List<Entry> entries = linked(1);
		final Feed feed = new Feed(WRITER.id());

		feed.append(entries.get(0));
		assertThat(feed.append(ChainFixtures.signed(WRITER, CHAIN, 1, entries.get(0).hash(), NOW)), is(Link.NEXT));
	}

	@Example
	public void shouldCallAnotherWriterForeign() {
		final Feed feed = new Feed(WRITER.id());
		final Entry other = ChainFixtures.signed(ChainFixtures.key(2), CHAIN, 0, 0, NOW);

		assertThat(feed.append(other), is(Link.FOREIGN));
		assertAt(feed, Optional.empty(), 0);
	}

	@Example
	public void shouldContinueFromAChosenStart() {
		final List<Entry> entries = linked(6);
		final Entry anchor = entries.get(3);
		final Feed feed = new Feed(WRITER.id(), new Head(3, anchor.hash()));

		assertThat(feed.head(), equalTo(Optional.of(new Head(3, anchor.hash()))));
		assertThat(feed.append(entries.get(2)), is(Link.REPLAYED));
		assertThat(feed.append(anchor), is(Link.DUPLICATE));
		assertThat(feed.append(entries.get(5)), is(Link.AHEAD));
		assertThat(feed.append(entries.get(4)), is(Link.NEXT));
		assertThat(feed.append(entries.get(5)), is(Link.NEXT));
	}

	@Example
	public void shouldTakeTheFirstEntryAfterAChosenStartAtAnyTimeButNotTheNextOneBackInTime() {
		final Entry anchor = linked(4).get(3);
		final Feed feed = new Feed(WRITER.id(), new Head(3, anchor.hash()));
		final Entry first = ChainFixtures.signed(WRITER, CHAIN, 4, anchor.hash(), NOW - 1_000);
		final Entry older = ChainFixtures.signed(WRITER, CHAIN, 5, first.hash(), NOW - 1_001);

		assertThat(feed.append(first), is(Link.NEXT));
		assertThat(feed.append(older), is(Link.BROKEN));
		assertAt(feed, Optional.of(new Head(4, first.hash())), 5);
	}

	@Example
	public void shouldRefuseANegativeSequenceNumberInAHead() {
		Expect.assertThrows(IllegalArgumentException.class, () -> new Head(-1, 1));
		Expect.assertThrows(IllegalArgumentException.class, () -> new Head(Long.MIN_VALUE, 1));
	}

	@Example
	public void shouldAcceptSequenceNumberZeroInAHead() {
		assertThat(new Head(0, 1).seq(), is(0L));
	}

	@Example
	public void shouldRefuseAStartAfterTheLastSequenceNumber() {
		Expect.assertThrows(IllegalArgumentException.class, () -> new Feed(WRITER.id(), new Head(Long.MAX_VALUE, 1)));
		assertThat(Feed.canStartAfter(new Head(Long.MAX_VALUE, 1)), is(false));
		assertThat(Feed.canStartAfter(new Head(Long.MAX_VALUE - 1, 1)), is(true));
		assertThat(new Feed(WRITER.id(), new Head(Long.MAX_VALUE - 1, 1)).nextSeq(), is(Long.MAX_VALUE));
	}

	@Example
	public void shouldCallTheLastSequenceNumberBrokenSinceNoEntryCanFollowIt() {
		final Entry last = ChainFixtures.signed(WRITER, CHAIN, Long.MAX_VALUE, 1, NOW);
		final Feed next = new Feed(WRITER.id(), new Head(Long.MAX_VALUE - 1, 1));

		assertThat(new Feed(WRITER.id()).check(last), is(Link.BROKEN));
		assertThat(next.append(last), is(Link.BROKEN));
		assertAt(next, Optional.of(new Head(Long.MAX_VALUE - 1, 1)), Long.MAX_VALUE);
	}
}
