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

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Head;

/* Nothing is written to a stream that does not take what it was sent, because the writes would pile up in memory */
public class ChainHostBackpressureTest {
	private static List<Entry> entriesOnTheChain(final ChainFleet fleet) {
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 3, ChainFleet.NOW);

		fleet.host("gn-0").receive(fleet.client("gn-0", 1), ChainEntry.builder().entries(entries).build());
		fleet.deliver();
		return entries;
	}

	/* Heads of a writer the chain has no entries of, which a member is asked about */
	private static ChainHeads ahead(final ChainFleet fleet) {
		return ChainHeads.builder().chainId(fleet.chain()).heads(Map.of(ChainFleet.writer(2), new Head(5, 1))).build();
	}

	@Example
	public void refusesAJoinFromAStreamThatDoesNotTakeWhatItWasSent() {
		final ChainFleet fleet = ChainFleet.group();
		final JoinChain join = JoinChain.builder().requestId(1).writer(ChainFleet.writer(1)).chainId(fleet.chain())
			.build();
		final JoinReply refused = fleet.host("gn-0").join(ChainFleet.unwritable(new EmbeddedChannel()), join);

		assertThat(refused.getStatus(), is(JoinReply.Status.FULL));
		assertThat(fleet.host("gn-0").join(new EmbeddedChannel(), join).getStatus(), is(JoinReply.Status.JOINED));
	}

	@Example
	public void servesNothingToAMemberThatDoesNotTakeWhatItWasSent() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = entriesOnTheChain(fleet);
		final EmbeddedChannel member = fleet.stream("gn-0", "gn-1");
		final FetchEntries request = FetchEntries.builder().chainId(fleet.chain())
			.ranges(List.of(new FetchEntries.Range(ChainFleet.writer(1), 0, 2))).build();

		assertThat(fleet.host("gn-0").fetch(member, request), equalTo(entries));

		ChainFleet.unwritable(member);
		assertThat(fleet.host("gn-0").fetch(member, request), is(empty()));
	}

	@Example
	public void tellsNoMemberThatDoesNotTakeWhatItWasSentWhereItsChainStands() {
		final ChainFleet fleet = ChainFleet.group();

		entriesOnTheChain(fleet);
		fleet.deliveries.clear();
		fleet.host("gn-0").reconcileRound();
		fleet.deliver();

		assertThat(fleet.deliveries.stream().filter(delivery -> delivery.packet() instanceof ChainHeads).count(), is(1L));

		final List<EmbeddedChannel> members = List.of("gn-1", "gn-2", "gn-3").stream()
			.map(member -> ChainFleet.unwritable(fleet.stream("gn-0", member))).toList();

		fleet.host("gn-0").reconcileRound();

		members.forEach(member -> assertThat(member.outboundMessages(), is(empty())));
	}

	@Example
	public void asksNothingOfAMemberThatDoesNotTakeWhatItWasSent() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel member = fleet.stream("gn-0", "gn-1");

		assertThat(fleet.host("gn-0").reconcile(member, ahead(fleet)).isPresent(), is(true));

		ChainFleet.unwritable(member);
		assertThat(fleet.host("gn-0").reconcile(member, ahead(fleet)), is(Optional.empty()));
	}

	@Example
	public void chargesNoHeadsToAMemberWhileItDoesNotTakeWhatItWasSent() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel member = ChainFleet.unwritable(fleet.stream("gn-0", "gn-1"));
		final int budget = StreamBudgets.Kind.HEADS.getPerSecond();

		IntStream.range(0, budget).forEach(asked -> assertThat(fleet.host("gn-0").reconcile(member, ahead(fleet)),
			is(Optional.empty())));

		member.flush();
		IntStream.range(0, budget).forEach(asked -> assertThat(fleet.host("gn-0").reconcile(member, ahead(fleet))
			.isPresent(), is(true)));
	}

	@Example
	public void chargesNoJoinsToAStreamWhileItDoesNotTakeWhatItWasSent() {
		final ChainFleet fleet = ChainFleet.group();
		final JoinChain join = JoinChain.builder().requestId(1).writer(ChainFleet.writer(1)).chainId(fleet.chain())
			.build();
		final EmbeddedChannel stream = ChainFleet.unwritable(new EmbeddedChannel());
		final int budget = StreamBudgets.Kind.JOINS.getPerSecond();

		IntStream.range(0, budget).forEach(attempt -> assertThat(fleet.host("gn-0").join(stream, join).getStatus(),
			is(JoinReply.Status.FULL)));

		stream.flush();
		IntStream.range(0, budget).forEach(attempt -> assertThat(fleet.host("gn-0").join(stream, join).getStatus(),
			is(JoinReply.Status.JOINED)));
	}

	@Example
	public void chargesNoRangesToAMemberWhileItDoesNotTakeWhatItWasSent() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = entriesOnTheChain(fleet);
		final EmbeddedChannel member = ChainFleet.unwritable(fleet.stream("gn-0", "gn-1"));
		final FetchEntries request = FetchEntries.builder().chainId(fleet.chain())
			.ranges(List.of(new FetchEntries.Range(ChainFleet.writer(1), 0, 2))).build();
		final int budget = StreamBudgets.Kind.RANGES.getPerSecond();

		IntStream.range(0, budget).forEach(range -> assertThat(fleet.host("gn-0").fetch(member, request),
			is(empty())));

		member.flush();
		IntStream.range(0, budget).forEach(range -> assertThat(fleet.host("gn-0").fetch(member, request),
			equalTo(entries)));
	}
}