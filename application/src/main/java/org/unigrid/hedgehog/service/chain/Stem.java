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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.packet.ChainStem;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Offer;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* The Dandelion++ stem of the chains a host holds, at the author and at a relay alike: it passes entries on to the
   successor drawn for their writer before any member floods them, holds them in the embargo meanwhile, and lets the
   chain take them by flood once they came round or their deadline passed. The chain takes them through the host,
   the way it takes anything else. */
final class Stem {
	/* What a stem entry must be to the chain to be passed on: one it would take, or hold for a gap before it */
	private static final Set<Admission> PASSES_ON = Set.of(Admission.ACCEPTED, Admission.AHEAD);

	/* The stem is a part of the host, and what fails in it is logged as the host's */
	private final Logger log = LoggerFactory.getLogger(ChainHost.class);

	private final StemHost host;
	private final Supplier<Hosting> hosting;
	private final LongSupplier clock;

	/* The hosting is read anew at every use, as a refresh of the host swaps in another */
	Stem(final StemHost host, final Supplier<Hosting> hosting, final LongSupplier clock) {
		this.host = host;
		this.hosting = hosting;
		this.clock = clock;
	}

	/* What one packet passed on to this node comes to: the draw of each of its writers, read once for the packet, the
	   entries the chain had no room for, those this node floods and those it relays, and those that came round */
	private record PassedOn(Map<WriterId, StemRouter.Draw> draws, List<Entry> full, List<Entry> diffused,
		List<Entry> relayed, List<Entry> cameRound) {

		private PassedOn() {
			this(new HashMap<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
		}
	}

	/* Answers the entries the chain had no room for */
	List<Entry> passOn(final HostedChain hosted, final Channel peer, final List<Entry> entries) {
		final long now = clock.getAsLong();
		final PassedOn packet = new PassedOn();

		for (final Entry entry : entries) {
			if (hosted.getEmbargo().holds(entry)) {
				cameRound(hosted, peer, entry, packet.cameRound());
			} else {
				take(hosted, peer, entry, packet, now);
			}
		}

		diffuse(hosted, packet.cameRound());
		diffuse(hosted, packet.diffused());
		relay(hosted, peer, packet.relayed(), now);
		return packet.full();
	}

	/* Takes an entry into the stem as the draw of its writer has it: one of a writer this node diffuses is weighed
	   with what it floods of the packet besides, and one of a writer it relays is held in the embargo. The draw of a
	   writer is kept once an entry of it was taken. */
	private static void take(final HostedChain hosted, final Channel peer, final Entry entry, final PassedOn packet,
		final long now) {

		final WriterId writer = entry.header().writer();
		final StemRouter.Draw draw = packet.draws().computeIfAbsent(writer,
			key -> hosted.getRouter().drawFor(key, peer));
		final Optional<Admission> admission = draw.diffuses()
			? hosted.checkToDiffuse(entry, packet.diffused().stream().mapToLong(Entry::size).sum())
			: hosted.holdRelayed(entry, peer, now);

		if (sortOut(admission, entry, packet.full(), draw.diffuses() ? packet.diffused() : packet.relayed())) {
			hosted.getRouter().keep(writer, draw, peer);
		}
	}

	/* An entry that came round from a member it was not passed to is flooded at once by a member that relayed it, but
	   its author leaves it to the shorter timers of the relays, and both move its writer to the other successor. One
	   that came straight back from any member it was passed to, the one a refusal moved it away from too, is left to
	   the timer by the author and a relay alike, and moves nothing: an honest member never passes an entry back to the
	   one it came from, so such a bounce gets the answer of the author. One that the member it came from sent again is
	   no round either and is dropped, or that member could make a relay flood it at once and move the writer as it
	   pleased; the entries of an author come from no member. */
	private static void cameRound(final HostedChain hosted, final Channel peer, final Entry entry,
		final List<Entry> flooded) {

		final StemEmbargo embargo = hosted.getEmbargo();
		final WriterId writer = entry.header().writer();

		if (!embargo.wasSentTo(entry, peer) && !embargo.sourceOf(entry).equals(Optional.of(peer))) {
			hosted.getRouter().cameRound(writer, hosted.avoidOf(writer));

			if (!embargo.holdsOwn(entry)) {
				flooded.add(entry);
			}
		}
	}

	/* Answers whether the entry was taken */
	private static boolean sortOut(final Optional<Admission> admission, final Entry entry, final List<Entry> full,
		final List<Entry> taken) {

		if (admission.filter(Admission.FULL::equals).isPresent()) {
			full.add(entry);
		} else if (admission.filter(PASSES_ON::contains).isPresent()) {
			taken.add(entry);
			return true;
		}

		return false;
	}

	private void diffuse(final HostedChain hosted, final List<Entry> entries) {
		if (!entries.isEmpty()) {
			host.offerAndQueue(hosted, entries, null, true);
		}
	}

	/* The entries are held in the embargo already, and one without a successor waits there until it has one, or is
	   flooded at the deadline without */
	private void relay(final HostedChain hosted, final Channel peer, final List<Entry> entries, final long now) {
		final Map<Channel, List<Entry>> relayed = new LinkedHashMap<>();

		for (final Entry entry : entries) {
			hosted.getRouter().successor(entry.header().writer(), Set.of(peer))
				.map(next -> relayed.computeIfAbsent(next, key -> new ArrayList<>()))
				.ifPresent(batch -> batch.add(entry));
		}

		relayed.forEach((next, batch) -> pass(hosted, next, batch, now));
	}

	/* Passes entries on to their route, at the author and at a relay alike, but never one to the stream it came in on:
	   that one waits in the embargo, which floods it at the deadline */
	void pass(final HostedChain hosted, final Channel route, final List<Entry> entries, final long now) {
		final StemEmbargo embargo = hosted.getEmbargo();
		final Map<Boolean, List<Entry>> back = entries.stream()
			.collect(Collectors.partitioningBy(entry -> embargo.sourceOf(entry).equals(Optional.of(route))));

		back.get(true).forEach(embargo::heldForSource);

		if (!back.get(false).isEmpty()) {
			stemTo(hosted, route, back.get(false), now);
		}
	}

	/* A successor whose stream takes no writes is passed over, as a flood passes such a stream over, since writes it
	   does not take pile up in memory, and it stays the route of the writer; it is noted since when it took none.
	   What went out is noted as passed to the successor once its stream took it, so an entry whose write failed, as
	   when the stream closed before it went out, never left. One that fails must not stop the others, and only the
	   class of its failure is logged, as a message may name an address. What a successor did not take waits in the
	   embargo, and goes to it as soon as it takes writes again. */
	private void stemTo(final HostedChain hosted, final Channel successor, final List<Entry> entries, final long now) {
		final boolean missed = !successor.isWritable();

		entries.forEach(entry -> hosted.getEmbargo().tried(entry, successor, now, missed));

		if (missed) {
			StemStalls.missed(successor, now, this::retry);
			return;
		}

		try {
			for (final ChainStem batch : ChainCodecs.batches(entries, ChainStem::new)) {
				successor.write(batch)
					.addListener(written -> taken(hosted, successor, batch, written.isSuccess()));
			}

			successor.flush();
		} catch (RuntimeException ex) {
			log.warn("The stem to a member stream failed with {}", ex.getClass().getName());
		}
	}

	private static void taken(final HostedChain hosted, final Channel successor, final ChainStem batch,
		final boolean success) {

		if (success) {
			batch.getEntries().forEach(entry -> hosted.getEmbargo().sent(entry, successor));
			StemStalls.took(successor);
		} else {
			batch.getEntries().forEach(entry -> hosted.getEmbargo().failed(entry, successor));
		}
	}

	Admission postStem(final ChainId chainId, final Entry entry, final Supplier<Set<Channel>> avoid) {
		if (chainId == null || !chainId.equals(entry.header().chainId())) {
			return Admission.WRONG_CHAIN;
		}

		return hosting.get().find(chainId).map(hosted -> postStem(hosted, entry, avoid))
			.orElse(Admission.WRONG_CHAIN);
	}

	boolean isStranded(final ChainId chainId, final WriterId writer) {
		return chainId != null && hosting.get().find(chainId)
			.filter(hosted -> hosted.getRouter().strands(writer, hosted.avoidOf(writer))).isPresent();
	}

	boolean canStem(final ChainId chainId, final Set<Channel> avoid) {
		return chainId != null && hosting.get().find(chainId)
			.filter(hosted -> hosted.getRouter().hasSuccessor(avoid)).isPresent();
	}

	private Admission postStem(final HostedChain hosted, final Entry entry, final Supplier<Set<Channel>> avoid) {
		final long now = clock.getAsLong();
		final Admission admission = hosted.holdOwn(entry, now);

		if (PASSES_ON.contains(admission)) {
			hosted.avoidFor(entry.header().writer(), avoid);
			stemOwn(hosted, entry.header().writer(), List.of(entry), avoid.get(), now);
		}

		return admission;
	}

	private void stemOwn(final HostedChain hosted, final WriterId writer, final List<Entry> entries,
		final Set<Channel> avoid, final long now) {

		hosted.getRouter().ownSuccessor(writer, avoid).ifPresent(next -> pass(hosted, next, entries, now));
	}

	/* What waits for a member whose stream took no writes goes to the route of its writer as soon as that stream takes
	   writes again, at the author and at a relay alike */
	private void retry(final Channel stream) {
		final long now = clock.getAsLong();

		hosting.get().all().forEach(hosted -> byLane(hosted, hosted.getEmbargo().waitingFor(stream))
			.forEach((lane, entries) -> lane.route(hosted)
				.ifPresent(route -> pass(hosted, route, entries, now))));
	}

	private record Lane(WriterId writer, Channel source) {
		/* The entries of one writer that are routed alike: those of a writer of this node's own, without a source,
		   avoid what the writer avoids, and those of a writer it relays avoid the stream they came in on, which
		   keeps the route of a writer past the draws the router keeps the same wherever it is asked for */
		private Set<Channel> avoid(final HostedChain hosted) {
			return source == null ? hosted.avoidOf(writer) : Set.of(source);
		}

		private Optional<Channel> route(final HostedChain hosted) {
			return source == null ? hosted.getRouter().ownSuccessor(writer, avoid(hosted))
				: hosted.getRouter().successor(writer, avoid(hosted));
		}

		/* A writer a relay routes by the hash, past the draws it keeps, which nothing moves */
		private boolean isHashed(final HostedChain hosted) {
			return source != null && !hosted.getRouter().keeps(writer);
		}

		/* A writer of this node's own is never routed by the hash */
		private boolean isLost(final HostedChain hosted) {
			return source == null && !hosted.getRouter().keeps(writer);
		}
	}

	private static Map<Lane, List<Entry>> byLane(final HostedChain hosted, final List<Entry> entries) {
		return entries.stream().collect(Collectors.groupingBy(entry -> laneOf(hosted, entry), LinkedHashMap::new,
			Collectors.toList()));
	}

	private static Lane laneOf(final HostedChain hosted, final Entry entry) {
		return new Lane(entry.header().writer(), hosted.getEmbargo().sourceOf(entry).orElse(null));
	}

	/* What the embargo held past its deadline, passed on and not back, or held as its route is the stream it came in
	   on, is flooded as if it came in now, and told to the listeners like any entry the chain appends: those of this
	   node's own writers they know as such. An entry of this node's own that the chain has no room for stays in the
	   embargo and is flooded again at its next deadline; one the chain will never take goes. What never left is
	   flooded by neither the author nor a relay: it goes to the route of its writer now, once the writer was moved
	   off a route that stalled, and waits on otherwise. Only with no successor left does a relay flood it, while the
	   author drops it and the session of the writer ends, as the first to flood it would be its author. */
	void floodDue(final HostedChain hosted) {
		final long now = clock.getAsLong();
		final List<StemEmbargo.Due> waited = hosted.getEmbargo().waited(now);
		final List<Entry> due = new ArrayList<>(waited.stream().filter(held -> held.sent() || held.bySource())
			.map(StemEmbargo.Due::entry).toList());

		waited.stream().filter(held -> !held.sent() && !held.bySource())
			.collect(Collectors.groupingBy(held -> laneOf(hosted, held.entry()), LinkedHashMap::new,
				Collectors.toList()))
			.forEach((lane, unsent) -> due.addAll(passLate(hosted, lane, unsent, now)));

		if (!due.isEmpty()) {
			final List<Optional<Offer>> offers = host.offerAndQueue(hosted, due, null, true);

			IntStream.range(0, due.size()).filter(place -> !ChainHost.isFull(offers.get(place)))
				.forEach(place -> hosted.getEmbargo().release(due.get(place)));
			host.handOver(hosted);
		}
	}

	/* What never left of a writer at its deadline goes to the route of the writer, once the writer was moved off a
	   route that stalled, and answers what a relay floods for want of a successor. A route counts as stalled for an
	   entry tried on it when it took no writes since the entry first missed it, or when the entry has now missed
	   MISSED_DEADLINES deadlines on it, its write there on its way or not. An entry whose write to the route it stays
	   on is still on its way waits on, and is not written there again. Nothing moves a writer a relay routes by the
	   hash, so it has no successor once its route stalled or takes no writes, and the relay floods what waits. */
	private List<Entry> passLate(final HostedChain hosted, final Lane lane, final List<StemEmbargo.Due> unsent,
		final long now) {

		final List<Entry> entries = unsent.stream().map(StemEmbargo.Due::entry).toList();
		final boolean hashed = lane.isHashed(hosted);
		final Optional<Channel> route = lane.isLost(hosted) ? Optional.empty() : hosted.getRouter()
			.moveIfStalled(lane.writer(), lane.avoid(hosted), current -> stalls(current, unsent))
			.filter(current -> !hashed || current.isWritable() && !stalls(current, unsent));

		if (route.isPresent()) {
			passAgain(hosted, route.get(), unsent, now);
			return List.of();
		}

		if (unsent.get(0).own()) {
			entries.forEach(hosted.getEmbargo()::release);
			return List.of();
		}

		return entries;
	}

	/* Passes on what is due, but for what was written to the route already and is on its way, which waits on */
	private void passAgain(final HostedChain hosted, final Channel route, final List<StemEmbargo.Due> unsent,
		final long now) {

		final List<Entry> again = unsent.stream().filter(held -> held.waiting() == null
			|| !held.waiting().isPending(route)).map(StemEmbargo.Due::entry).toList();

		if (!again.isEmpty()) {
			pass(hosted, route, again, now);
		}
	}

	private static boolean stalls(final Channel route, final List<StemEmbargo.Due> unsent) {
		return unsent.stream().map(StemEmbargo.Due::waiting).filter(Objects::nonNull)
			.anyMatch(waiting -> waiting.route() == route && (StemStalls.since(route, waiting.since())
				|| waiting.missed() >= StemEmbargo.MISSED_DEADLINES));
	}
}
