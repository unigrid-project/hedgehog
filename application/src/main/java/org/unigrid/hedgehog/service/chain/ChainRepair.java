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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.HeadsDiff;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* How a member that lags catches up: the heads a node announces to one of its members, the ranges it asks for when
   the heads of a peer are ahead of its own, and the retained entries it answers a fetch with. */
@Slf4j
final class ChainRepair {
	/* What one fetch can ask for and what one answer can hold: a few packets full, however much a peer asks */
	private static final int RANGE_ENTRIES = ChainCodecs.MAX_ENTRIES_PER_PACKET;
	private static final int MAX_RANGES = 8;

	/* The order of the chain itself, so that the entries of a writer reach the requester in sequence */
	private static final Comparator<Entry> OLDEST_FIRST = Comparator
		.comparingLong((Entry entry) -> entry.header().timestamp()).thenComparing(entry -> entry.header().writer())
		.thenComparingLong(entry -> entry.header().seq());

	private final StreamBudgets budgets;
	private final RandomGenerator random;
	private final Function<ChainId, Optional<HostedChain>> hosted;
	private final Supplier<Hosting> hosting;

	ChainRepair(final StreamBudgets budgets, final RandomGenerator random,
		final Function<ChainId, Optional<HostedChain>> hosted, final Supplier<Hosting> hosting) {

		this.budgets = budgets;
		this.random = random;
		this.hosted = hosted;
		this.hosting = hosting;
	}

	/* Run once a second: each chain with entries tells one random member how far it has got, so a member that
	   is behind asks for what it lacks. Those entries come back through receive like any other. Only streams that
	   are open and take writes are chosen from, and a client is none of them: it never needs another's entries. */
	void reconcileRound() {
		hosting.get().current().values().forEach(this::announce);
	}

	private void announce(final HostedChain chain) {
		final List<Channel> members = chain.getMemberStreams().stream().filter(Channel::isActive)
			.filter(Channel::isWritable).toList();
		final Map<WriterId, Head> heads = chain.getChain().heads();

		if (members.isEmpty() || heads.isEmpty()) {
			return;
		}

		final Channel member = members.get(random.nextInt(members.size()));
		final ChainHeads announcement = ChainHeads.builder().chainId(chain.getChain().id()).heads(capped(heads))
			.build();

		try {
			member.writeAndFlush(announcement);
		} catch (RuntimeException ex) {
			log.warn("The heads to a member stream failed with {}", ex.getClass().getName());
		}
	}

	/* A packet of heads holds only so many, and the writers a chain takes are bounded by the same number, so this
	   only matters to a chain that was handed more feeds than it takes. It announces a part of them. */
	private static Map<WriterId, Head> capped(final Map<WriterId, Head> heads) {
		if (heads.size() <= ChainCodecs.MAX_HEADS_PER_PACKET) {
			return heads;
		}

		return heads.entrySet().stream().limit(ChainCodecs.MAX_HEADS_PER_PACKET)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/* The heads of a peer only say what to ask for: nothing is adopted from them, and no feed is anchored at a
	   head a peer names (follow is the one exception, and only for the member a client chose), so a peer cannot pin
	   a writer to a head that is not its own. A feed this chain has not
	   seen starts at its first entry, and if that entry is gone the entries that follow it wait until the gap
	   expires. Only a member is answered, and only while its stream takes writes, as answers it does not read would
	   pile up in memory, and only so many of its packets of heads in a second, counted before the heads of the chain
	   are read. */
	Optional<FetchEntries> reconcile(final Channel peer, final ChainHeads heads) {
		return hosted.apply(heads.getChainId()).filter(chain -> chain.trusts(peer))
			.filter(chain -> peer.isWritable())
			.filter(chain -> budgets.take(peer, StreamBudgets.Kind.HEADS, 1))
			.map(chain -> missing(chain, heads.getHeads())).filter(ranges -> !ranges.isEmpty())
			.map(ranges -> FetchEntries.builder().chainId(heads.getChainId()).ranges(ranges).build());
	}

	/* The writers this chain is behind the peer on and can take, by ascending id. A feed whose entries are gone at
	   every member is claimed again in every round, so asking for the lowest ids each time would never reach a
	   writer that sorts after eight of them: with more than eight, the eight asked for start at a random place of
	   the list and carry on from its start when the end is passed. */
	private List<FetchEntries.Range> missing(final HostedChain chain, final Map<WriterId, Head> remote) {
		final List<FetchEntries.Range> ahead = HeadsDiff.missing(chain.getChain().heads(), remote, RANGE_ENTRIES)
			.stream().filter(range -> chain.canTake(range.writer()))
			.map(range -> new FetchEntries.Range(range.writer(), range.fromSeq(), range.toSeq())).toList();

		if (ahead.size() <= MAX_RANGES) {
			return ahead;
		}

		final int start = random.nextInt(ahead.size());

		return IntStream.range(0, MAX_RANGES).mapToObj(place -> ahead.get((start + place) % ahead.size())).toList();
	}

	/* The answers to a member that never reads would pile up in the buffer of its stream, which only turns unwritable
	   when it is full, so a stream that takes no writes gets no answer and asks again. The ranges are charged before
	   the answer is built, which waits for the monitor of the chain, and the bytes of the answer once it is built:
	   an answer that does not fit what is left of the second is not sent, and the member asks again later. */
	List<Entry> fetch(final Channel peer, final FetchEntries request) {
		return hosted.apply(request.getChainId()).filter(chain -> chain.trusts(peer))
			.filter(chain -> peer.isActive() && peer.isWritable())
			.filter(chain -> budgets.take(peer, StreamBudgets.Kind.RANGES,
				Math.min(request.getRanges().size(), MAX_RANGES)))
			.map(chain -> withinBudget(peer, chain.getChain(), request.getRanges())).orElse(List.of());
	}

	private List<Entry> withinBudget(final Channel peer, final Chain chain, final List<FetchEntries.Range> ranges) {
		final List<Entry> answer = retained(chain, ranges);
		final int bytes = answer.stream().mapToInt(ChainCodecs::encodedBytes).sum();

		return budgets.take(peer, StreamBudgets.Kind.FETCH_BYTES, bytes) ? answer : List.of();
	}

	/* A range asked for twice is answered once, and a range wider than a packet is answered with its first entries */
	private static List<Entry> retained(final Chain chain, final List<FetchEntries.Range> ranges) {
		final List<Entry> oldestFirst = ranges.stream().limit(MAX_RANGES)
			.flatMap(range -> chain.range(range.writer(), range.fromSeq(),
				range.fromSeq() + Math.min(range.toSeq() - range.fromSeq(), RANGE_ENTRIES - 1)).stream())
			.distinct().sorted(OLDEST_FIRST).toList();

		return withinBytes(oldestFirst);
	}

	/* One answer must fit the frames the requester accepts, so it stops at the entry that would pass the cap and
	   the requester asks for the rest next round */
	private static List<Entry> withinBytes(final List<Entry> entries) {
		final List<Entry> within = new ArrayList<>();
		long bytes = 0;

		for (final Entry entry : entries) {
			bytes += ChainCodecs.encodedBytes(entry);

			if (bytes > ChainCodecs.MAX_FRAME_BYTES) {
				break;
			}

			within.add(entry);
		}

		return within;
	}
}
