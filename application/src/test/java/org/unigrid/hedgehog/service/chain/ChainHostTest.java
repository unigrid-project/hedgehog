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

package org.unigrid.hedgehog.service.chain;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.model.network.codec.ChainArbitraries;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class ChainHostTest extends BaseMockedWeldTest {
	private static final ChainId CHAIN = new ChainId(new byte[ChainId.SIZE]);
	private static final WriterId WRITER = new WriterId(new byte[WriterId.SIZE]);

	@Inject
	private ChainService chainService;

	@Provide
	public Arbitrary<WriterId> writers() {
		return ChainArbitraries.writers();
	}

	@Provide
	public Arbitrary<ChainId> anyOrOneChain() {
		return ChainArbitraries.chainIds().injectNull(0.5);
	}

	@Provide
	public Arbitrary<ChainId> chainIds() {
		return ChainArbitraries.chainIds();
	}

	@Provide
	public Arbitrary<List<Entry>> entryLists() {
		return ChainArbitraries.entryLists();
	}

	@Provide
	public Arbitrary<List<Entry>> fullPacketEntries() {
		return ChainArbitraries.entries().list().ofSize(ChainCodecs.MAX_ENTRIES_PER_PACKET);
	}

	@Provide
	public Arbitrary<Map<WriterId, Head>> heads() {
		return ChainArbitraries.heads();
	}

	@Example
	public void hostsTheChainsOfThisNode() {
		assertThat(chainService, instanceOf(ChainHost.class));
	}

	@Property(tries = 10)
	public void refusesEveryJoinWhileDisabled(@ForAll long requestId, @ForAll("writers") WriterId writer,
		@ForAll("anyOrOneChain") ChainId chainId) {

		final JoinReply reply = chainService.join(new EmbeddedChannel(),
			JoinChain.builder().requestId(requestId).writer(writer).chainId(chainId).build());

		assertThat(reply.getRequestId(), equalTo(requestId));
		assertThat(reply.getStatus(), equalTo(JoinReply.Status.DISABLED));
		assertThat(reply.getChainId(), equalTo(Optional.empty()));
	}

	@Property(tries = 10)
	public void answersNoChainTrafficWhileDisabled(@ForAll("entryLists") List<Entry> entries,
		@ForAll("chainIds") ChainId chainId, @ForAll("heads") Map<WriterId, Head> heads) {

		final Channel peer = new EmbeddedChannel();

		assertThat(chainService.receive(peer, ChainEntry.builder().entries(entries).build()), empty());
		assertThat(chainService.reconcile(peer, ChainHeads.builder().chainId(chainId).heads(heads).build()),
			equalTo(Optional.empty()));
		assertThat(chainService.fetch(peer, FetchEntries.builder().chainId(chainId).build()), empty());
	}

	@Example
	public void repliesDisabledAndNothingElseAtTheBoundariesOfTheRequestId() {
		for (final long requestId : new long[] {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
			for (final ChainId chainId : new ChainId[] {null, CHAIN}) {
				final JoinChain request = JoinChain.builder().requestId(requestId).writer(WRITER).chainId(chainId).build();

				assertThat(chainService.join(new EmbeddedChannel(), request),
					equalTo(JoinReply.builder().requestId(requestId).status(JoinReply.Status.DISABLED).build()));
			}
		}
	}

	@Example
	public void servesNothingForARangeOfTheWholeFeed() {
		final FetchEntries request = FetchEntries.builder().chainId(CHAIN)
			.ranges(List.of(new FetchEntries.Range(WRITER, 0, Long.MAX_VALUE))).build();

		assertThat(chainService.fetch(new EmbeddedChannel(), request), empty());
	}

	@Property(tries = 5)
	public void answersTheLargestPacketsWithNothing(@ForAll("fullPacketEntries") List<Entry> entries,
		@ForAll("chainIds") ChainId chainId) {

		final EmbeddedChannel peer = new EmbeddedChannel();
		final Map<WriterId, Head> heads = IntStream.range(0, ChainCodecs.MAX_HEADS_PER_PACKET).boxed()
			.collect(Collectors.toMap(ChainHostTest::writerOf, index -> new Head(index, index)));
		final List<FetchEntries.Range> ranges = heads.keySet().stream()
			.map(writer -> new FetchEntries.Range(writer, 0, Long.MAX_VALUE)).toList();

		assertThat(chainService.receive(peer, ChainEntry.builder().entries(entries).build()), empty());
		assertThat(chainService.reconcile(peer, ChainHeads.builder().chainId(chainId).heads(heads).build()),
			equalTo(Optional.empty()));
		assertThat(chainService.fetch(peer, FetchEntries.builder().chainId(chainId).ranges(ranges).build()), empty());
		assertThat(peer.outboundMessages(), empty());
	}

	@Example
	public void toleratesPacketsWithoutContent() {
		final EmbeddedChannel peer = new EmbeddedChannel();

		assertThat(chainService.join(peer, new JoinChain()),
			equalTo(JoinReply.builder().status(JoinReply.Status.DISABLED).build()));
		assertThat(chainService.receive(peer, ChainEntry.builder().build()), empty());
		assertThat(chainService.receive(peer, new ChainEntry()), empty());
		assertThat(chainService.reconcile(peer, ChainHeads.builder().chainId(CHAIN).build()), equalTo(Optional.empty()));
		assertThat(chainService.reconcile(peer, new ChainHeads()), equalTo(Optional.empty()));
		assertThat(chainService.fetch(peer, new FetchEntries()), empty());
		chainService.refused(peer, new EntryRefused());
		chainService.leave(peer, new LeaveChain());

		assertThat(peer.outboundMessages(), empty());
		assertThat(peer.isActive(), is(true));
	}

	@Property(tries = 10)
	public void ignoresRefusalsAndLeavesWithoutTouchingThePeer(@ForAll("chainIds") ChainId chainId,
		@ForAll("writers") WriterId writer, @ForAll @LongRange(min = 0) long seq, @ForAll Admission admission) {

		final EmbeddedChannel peer = new EmbeddedChannel();

		chainService.refused(peer,
			EntryRefused.builder().chainId(chainId).writer(writer).seq(seq).admission(admission).build());
		chainService.leave(peer, LeaveChain.builder().chainId(chainId).writer(writer).build());

		assertThat(peer.outboundMessages(), empty());
		assertThat(peer.isActive(), is(true));
	}

	private static WriterId writerOf(final int index) {
		return new WriterId(ByteBuffer.allocate(WriterId.SIZE).putInt(index).array());
	}
}
