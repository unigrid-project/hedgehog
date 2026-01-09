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

package org.unigrid.hedgehog.service.chain;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class ChainHostRefusalTest {
	private static final long EPOCH_MILLIS = 3_600_000;
	private static final int BOUND = 1_024;
	private static final ChainId NOT_HOSTED = new ChainId(new byte[ChainId.SIZE]);
	private static final WriterId WRITER = ChainFleet.writer(1);

	private static EntryRefused refusal(final ChainId chainId, final Entry entry, final Admission admission) {
		return EntryRefused.builder().chainId(chainId).writer(entry.header().writer()).seq(entry.header().seq())
			.admission(admission).build();
	}

	private static EntryRefused full(final ChainId chainId, final WriterId writer, final long seq) {
		return EntryRefused.builder().chainId(chainId).writer(writer).seq(seq).admission(Admission.FULL).build();
	}

	/* The stream that the host of gn-1 holds to another member of its group */
	private static Channel member(final ChainFleet fleet) {
		return fleet.stream("gn-1", "gn-0");
	}

	/* A hosting with its chains retired by an epoch change, a number of epochs later */
	private static ChainId retiredAfter(final ChainFleet fleet, final int epochs) {
		final ChainId chainId = fleet.chain();

		fleet.clock.addAndGet(epochs * EPOCH_MILLIS);
		fleet.host("gn-1").refresh();
		return chainId;
	}

	@Example
	public void pausesTheWriterOfAnEntryAMemberFoundNoRoomFor() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final Entry entry = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 6, ChainFleet.NOW).get(5);
		final ChainHost host = fleet.host("gn-1");

		host.refused(fleet.stream("gn-1", "gn-0"), refusal(fleet.chain(), entry, Admission.FULL));

		assertThat(host.pauses().isPaused(fleet.chain(), entry.header().writer()), is(true));
		assertThat(host.pauses().refusedFrom(fleet.chain(), entry.header().writer()), equalTo(OptionalLong.of(5)));
	}

	@Example
	public void pausesForARefusalFromEachMemberOfTheGroup() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost host = fleet.host("gn-1");

		List.of("gn-0", "gn-2", "gn-3").forEach(peer -> {
			host.pauses().resume(fleet.chain(), WRITER);
			host.refused(fleet.stream("gn-1", peer), full(fleet.chain(), WRITER, 3));

			assertThat(host.pauses().refusedFrom(fleet.chain(), WRITER), equalTo(OptionalLong.of(3)));
		});
	}

	@Property(tries = 20)
	public void pausesNobodyForAnyOtherRefusal(@ForAll Admission admission) {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final Entry entry = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 1, ChainFleet.NOW).get(0);
		final ChainHost host = fleet.host("gn-1");

		host.refused(fleet.stream("gn-1", "gn-0"), refusal(fleet.chain(), entry, admission));

		assertThat(host.pauses().isPaused(fleet.chain(), entry.header().writer()), is(admission == Admission.FULL));
	}

	@Example
	public void leavesAnAlreadyPausedWriterAloneForAnyOtherRefusal() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost host = fleet.host("gn-1");

		host.refused(member(fleet), full(fleet.chain(), WRITER, 4));

		Stream.of(Admission.values()).filter(admission -> admission != Admission.FULL)
			.forEach(admission -> host.refused(member(fleet), EntryRefused.builder().chainId(fleet.chain())
				.writer(WRITER).seq(1).admission(admission).build()));

		assertThat(host.pauses().refusedFrom(fleet.chain(), WRITER), equalTo(OptionalLong.of(4)));
	}

	@Example
	public void ignoresARefusalFromAnAttachedClient() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final Entry entry = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 1, ChainFleet.NOW).get(0);
		final ChainHost host = fleet.host("gn-1");

		host.refused(fleet.client("gn-1", 5), refusal(fleet.chain(), entry, Admission.FULL));

		assertThat(host.pauses().isPaused(fleet.chain(), entry.header().writer()), is(false));
	}

	@Example
	public void ignoresARefusalFromAClientAttachedAsTheWriterItRefuses() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost host = fleet.host("gn-1");

		host.refused(fleet.client("gn-1", 1), full(fleet.chain(), WRITER, 0));

		assertThat(host.pauses().isPaused(fleet.chain(), WRITER), is(false));
	}

	@Example
	public void ignoresARefusalFromAStreamThatIsNeitherAMemberNorAttached() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final Entry entry = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 1, ChainFleet.NOW).get(0);
		final ChainHost host = fleet.host("gn-1");

		host.refused(new EmbeddedChannel(), refusal(fleet.chain(), entry, Admission.FULL));
		host.refused(member(fleet), refusal(NOT_HOSTED, entry, Admission.FULL));

		assertThat(host.pauses().isPaused(fleet.chain(), entry.header().writer()), is(false));
		assertThat(host.pauses().isPaused(NOT_HOSTED, entry.header().writer()), is(false));
	}

	@Example
	public void ignoresARefusalFromNoStreamAtAll() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost host = fleet.host("gn-1");

		host.refused(null, full(fleet.chain(), WRITER, 0));

		assertThat(host.pauses().isPaused(fleet.chain(), WRITER), is(false));
	}

	@Example
	public void ignoresARefusalFromAStreamOfAnotherHost() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost host = fleet.host("gn-1");

		host.refused(fleet.stream("gn-2", "gn-0"), full(fleet.chain(), WRITER, 0));

		assertThat(host.pauses().isPaused(fleet.chain(), WRITER), is(false));
	}

	@Example
	public void ignoresARefusalForAChainThisNodeDoesNotHost() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost host = fleet.host("gn-1");

		host.refused(member(fleet), full(NOT_HOSTED, WRITER, 0));

		assertThat(host.pauses().isPaused(NOT_HOSTED, WRITER), is(false));
		assertThat(host.pauses().isPaused(fleet.chain(), WRITER), is(false));
	}

	@Example
	public void ignoresARefusalWhileTheNodeHostsNothing() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost host = fleet.host("gn-1");
		final ChainId chainId = fleet.chain();
		final Channel stream = member(fleet);

		fleet.settings.set(Optional.empty());
		host.refresh();
		host.refused(stream, full(chainId, WRITER, 0));

		assertThat(host.pauses().isPaused(chainId, WRITER), is(false));
	}

	@Example
	public void believesAMemberOnAChainThatHasRetired() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final Channel stream = member(fleet);
		final ChainId retired = retiredAfter(fleet, 1);
		final ChainHost host = fleet.host("gn-1");

		assertThat(host.retiredChains().contains(retired), is(true));

		host.refused(stream, full(retired, WRITER, 2));

		assertThat(host.pauses().refusedFrom(retired, WRITER), equalTo(OptionalLong.of(2)));
	}

	@Example
	public void ignoresAStrangerOnAChainThatHasRetired() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final Channel client = fleet.client("gn-1", 1);
		final ChainId retired = retiredAfter(fleet, 1);
		final ChainHost host = fleet.host("gn-1");

		host.refused(new EmbeddedChannel(), full(retired, WRITER, 2));
		host.refused(client, full(retired, WRITER, 2));

		assertThat(host.retiredChains().contains(retired), is(true));
		assertThat(host.pauses().isPaused(retired, WRITER), is(false));
	}

	@Example
	public void ignoresARefusalForAChainThatHasBeenDropped() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final Channel stream = member(fleet);
		final ChainId dropped = retiredAfter(fleet, 6);
		final ChainHost host = fleet.host("gn-1");

		assertThat(host.chain(dropped), is(Optional.empty()));

		host.refused(stream, full(dropped, WRITER, 2));

		assertThat(host.pauses().isPaused(dropped, WRITER), is(false));
	}

	@Example
	public void ignoresARefusalWithoutAnythingInIt() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost host = fleet.host("gn-1");

		host.refused(member(fleet), null);
		host.refused(member(fleet), new EntryRefused());

		assertThat(host.pauses().isPaused(null, null), is(false));
		assertThat(host.pauses().isPaused(fleet.chain(), null), is(false));
	}

	@Example
	public void ignoresARefusalThatDoesNotNameTheWriter() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost host = fleet.host("gn-1");

		host.refused(member(fleet), full(fleet.chain(), null, 1));

		assertThat(host.pauses().isPaused(fleet.chain(), null), is(false));
	}

	@Example
	public void ignoresARefusalThatDoesNotNameTheChain() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost host = fleet.host("gn-1");

		host.refused(member(fleet), full(null, WRITER, 1));

		assertThat(host.pauses().isPaused(null, WRITER), is(false));
		assertThat(host.pauses().isPaused(fleet.chain(), WRITER), is(false));
	}

	@Example
	public void ignoresARefusalThatDoesNotSayWhy() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost host = fleet.host("gn-1");

		host.refused(member(fleet), EntryRefused.builder().chainId(fleet.chain()).writer(WRITER).seq(1).build());

		assertThat(host.pauses().isPaused(fleet.chain(), WRITER), is(false));
	}

	@Example
	public void keepsOnlyTheLatestPausesWhateverAMemberSends() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost host = fleet.host("gn-1");
		final List<WriterId> writers = IntStream.range(0, BOUND + 6).mapToObj(ChainFleet::writer).toList();

		writers.forEach(writer -> host.refused(member(fleet), full(fleet.chain(), writer, 0)));

		assertThat(writers.stream().filter(writer -> host.pauses().isPaused(fleet.chain(), writer)).toList(),
			equalTo(writers.subList(6, writers.size())));
	}

	@Example
	public void keepsTheSameTableForTheWholeLifeOfTheHost() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost host = fleet.host("gn-1");

		assertThat(host.pauses(), sameInstance(host.pauses()));
		assertThat(host.pauses(), not(sameInstance(fleet.host("gn-0").pauses())));
	}

	@Example
	public void learnsOfARefusalThroughTheStreamTheEntryWasSentOn() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final List<Entry> crowd = ChainFleet.entries(ChainFleet.key(2), fleet.chain(), 5, ChainFleet.NOW);
		final Entry mine = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 1, ChainFleet.NOW).get(0);

		fleet.offline("gn-1");
		fleet.refreshAll();
		fleet.host("gn-0").receive(fleet.client("gn-0", 2), ChainEntry.builder().entries(crowd).build());
		fleet.online("gn-1");
		fleet.refreshAll();

		assertThat(fleet.host("gn-1").post(mine), is(Admission.ACCEPTED));
		fleet.deliver();

		assertThat(fleet.host("gn-1").pauses().refusedFrom(fleet.chain(), mine.header().writer()),
			equalTo(OptionalLong.of(0)));
	}

	@Example
	public void tellsTheOwnerWhereToOfferAgainFromWhenMembersRefuseItsPost() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost owner = fleet.host("gn-1");
		final List<Entry> mine = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 3, ChainFleet.NOW);
		final List<Entry> crowd = ChainFleet.entries(ChainFleet.key(2), fleet.chain(), 3, ChainFleet.NOW);

		assertThat(owner.post(mine.get(0)), is(Admission.ACCEPTED));
		assertThat(owner.post(mine.get(1)), is(Admission.ACCEPTED));
		fleet.deliver();
		fleet.offline("gn-1");
		fleet.refreshAll();
		fleet.host("gn-0").receive(fleet.client("gn-0", 2), ChainEntry.builder().entries(crowd).build());
		fleet.online("gn-1");
		fleet.refreshAll();

		assertThat(owner.post(mine.get(2)), is(Admission.ACCEPTED));
		fleet.deliver();

		assertThat(owner.pauses().refusedFrom(fleet.chain(), WRITER), equalTo(OptionalLong.of(2)));
		assertThat(owner.pauses().isPaused(fleet.chain(), ChainFleet.writer(2)), is(false));
	}
}
