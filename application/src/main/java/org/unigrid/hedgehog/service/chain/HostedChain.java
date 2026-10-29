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
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.Offer;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.group.Epoch;

/* One chain this node hosts or follows, with what the host knows about it beyond the entries */
final class HostedChain {
	/* A chain keeps the head of every feed that has not ended, so the writers it takes are bounded. The bound is what
	   one packet of heads carries, which keeps every reconcile and join reply in one packet. */
	static final int MAX_WRITERS = ChainCodecs.MAX_HEADS_PER_PACKET;

	/* The deliveries that may wait for the thread that drains a chain, so that a listener that is slow or stuck
	   cannot make the host hold on to every entry that comes in meanwhile. A delivery beyond it is dropped: the
	   entries are in the chain all the same, a member catches up through reconcile and a session that missed its
	   OPEN times out and tries again. */
	static final int MAX_PENDING_DELIVERIES = 4096;

	private static final Set<Admission> HOLDS_A_FEED = Set.of(Admission.ACCEPTED, Admission.AHEAD);

	enum Attachment { NEW, KNOWN, REFUSED }

	@Getter private final Chain chain;

	/* Held for the offers of one packet and the queueing of what they appended, and for nothing else, so that the
	   queue holds the deliveries in the order the chain appended them. No flood, no listener, no stream write and no
	   log line runs under it, and it is taken before the chain's own lock. */
	@Getter private final Object deliveryLock = new Object();

	/* Replaceable only so that a test can bound the queue or watch it; nothing else sets it */
	@Getter @Setter(AccessLevel.PACKAGE) private volatile DeliveryQueue deliveries = new DeliveryQueue(
		new LinkedBlockingQueue<>(MAX_PENDING_DELIVERIES));

	/* The epoch the chain was created for, which decides how long it is kept once it has retired */
	@Getter private final Epoch epoch;

	/* A parameter only so that a test reaches the limit without taking thousands of writers */
	private final int maxWriters;

	/* The entries of the stem this node holds until they come back to it by flood */
	@Getter private final StemEmbargo embargo;
	private final Map<WriterId, Channel> clients = new ConcurrentHashMap<>();
	private final Set<WriterId> writers = ConcurrentHashMap.newKeySet();

	/* The member stream that the first entry of each writer came in on, as the chain appended it. For the writer of a
	   client that is a member the client attached through, which knows the client and is the one member an exit
	   never passes the entries of a session with that client on to. */
	private final Map<WriterId, Channel> firstFrom = new ConcurrentHashMap<>();

	/* The streams this node holds to the other members of the group, looked up when the hosting is refreshed, or
	   for a chain the node follows, the streams to the members it follows the chain through. Replaced as a whole by
	   an immutable list and never changed in place, volatile so that the netty threads which read it see the list
	   the thread that replaced it has just written. */
	@Getter @Setter private volatile List<Channel> memberStreams = List.of();

	/* Whether what the chain takes in from other nodes is flooded on. A node that only follows a chain leaves that
	   to the members, which flood to each other. */
	@Getter @Setter private volatile boolean relaying = true;

	/* What the host's clock read when the hosting stopped wanting the chain, set when it retires and only then */
	@Getter @Setter private volatile long retiredAt;

	HostedChain(final Chain chain, final Epoch epoch) {
		this(chain, epoch, MAX_WRITERS);
	}

	HostedChain(final Chain chain, final Epoch epoch, final int maxWriters) {
		this(chain, epoch, maxWriters, new StemEmbargo(new SecureRandom()));
	}

	HostedChain(final Chain chain, final Epoch epoch, final int maxWriters, final StemEmbargo embargo) {
		this.chain = chain;
		this.epoch = epoch;
		this.maxWriters = maxWriters;
		this.embargo = embargo;
	}

	synchronized void addMemberStream(final Channel stream) {
		memberStreams = Stream.concat(memberStreams.stream(), Stream.of(stream)).distinct().toList();
	}

	synchronized void dropMemberStream(final Channel stream) {
		memberStreams = memberStreams.stream().filter(member -> member != stream).toList();
	}

	/* Starts a feed after a head that a member named in a join reply. The feed counts as a writer like one an entry
	   started, under the same lock and limit, but only once the chain took the head: one it refused, such as a head
	   that no entry follows, holds no slot. */
	boolean anchor(final WriterId writer, final Head head) {
		synchronized (chain) {
			final boolean anchored = writer != null && head != null && canTake(writer)
				&& chain.anchor(writer, head);

			if (anchored) {
				writers.add(writer);
			}

			return anchored;
		}
	}

	int clientCount() {
		return clients.size();
	}

	boolean isAttached(final WriterId writer) {
		return clients.containsKey(writer);
	}

	long clientsOn(final Channel stream) {
		return clients.values().stream().filter(stream::equals).count();
	}

	/* Whether a writer the chain does not know yet would be taken */
	boolean takesNewWriters() {
		return writers.size() < maxWriters;
	}

	/* A chain that has taken as many writers as it may only takes more entries from those it knows */
	boolean canTake(final WriterId writer) {
		return writers.contains(writer) || writers.size() < maxWriters;
	}

	/* A writer keeps the live stream it attached through, so another stream cannot take its place by naming it,
	   and a stream without room left may only renew what it has. Only joins call this, one at a time, so a
	   chain never gets past its limit. */
	Attachment attach(final WriterId writer, final Channel stream, final int limit, final boolean streamHasRoom) {
		final Channel registered = clients.get(writer);

		if (registered == stream) {
			return Attachment.KNOWN;
		}

		if (!streamHasRoom || !canTake(writer) || isHeldByAnother(registered, limit)) {
			return Attachment.REFUSED;
		}

		clients.put(writer, stream);
		return Attachment.NEW;
	}

	/* A writer without a place needs a free one, and a writer with one keeps it while its stream lives */
	private boolean isHeldByAnother(final Channel registered, final int limit) {
		return registered == null ? clients.size() >= limit : registered.isActive();
	}

	void detach(final WriterId writer, final Channel stream) {
		clients.remove(writer, stream);
	}

	void detachAll(final Channel stream) {
		clients.values().removeIf(stream::equals);
	}

	/* Every member stream and every client stream, which is who a new entry is flooded to */
	Stream<Channel> audience() {
		return Stream.concat(memberStreams.stream(), clients.values().stream()).distinct();
	}

	/* Only the members may ask what this chain holds: a client that is attached could otherwise draw whole packets
	   of entries for the price of a join */
	boolean trusts(final Channel stream) {
		return stream != null && memberStreams.contains(stream);
	}

	/* Members relay the entries of any writer, a client only posts its own */
	boolean accepts(final Channel stream, final WriterId writer) {
		return stream != null && (memberStreams.contains(stream) || clients.get(writer) == stream);
	}

	/* Forgets the writers whose feeds ended before the cutoff, so that their places come free, under the chain's own
	   lock like the offers that count the writers. Answers how many were forgotten. */
	int forgetEnded(final long cutoffMillis) {
		synchronized (chain) {
			final Set<WriterId> forgotten = chain.forgetEnded(cutoffMillis);

			writers.removeAll(forgotten);
			firstFrom.keySet().removeAll(forgotten);
			return forgotten.size();
		}
	}

	/* Notes an entry the chain appended and the stream it came in on, none for this node's own. An entry of the stem
	   that the chain appended has come back, whichever way. */
	void appended(final Entry entry, final Channel origin) {
		embargo.release(entry);

		if (origin != null && memberStreams.contains(origin)) {
			firstFrom.putIfAbsent(entry.header().writer(), origin);
		}
	}

	/* What the chain would make of an entry, counting what the embargo holds as if the chain had it already. There is
	   no answer for a writer the chain takes no more of, as offer gives none. */
	Optional<Admission> check(final Entry entry) {
		synchronized (chain) {
			return canTake(entry.header().writer()) ? Optional.of(chain.check(entry, embargo.bytes()))
				: Optional.empty();
		}
	}

	Optional<Channel> firstFrom(final WriterId writer) {
		return Optional.ofNullable(firstFrom.get(writer));
	}

	/* Holds an entry of this node's own in the embargo, where it waits to come back by flood, once the chain would
	   take it in or hold it past a gap, counting what the embargo holds already as if it were in the chain. A writer
	   that would be one too many is told FULL, as a post is. */
	Admission holdOwn(final Entry entry, final long now) {
		final WriterId writer = entry.header().writer();

		synchronized (chain) {
			if (!canTake(writer)) {
				return Admission.FULL;
			}

			final Admission admission = chain.check(entry, embargo.bytes());

			if (HOLDS_A_FEED.contains(admission)) {
				embargo.hold(entry, true, now);
			}

			return admission;
		}
	}

	/* Offering and counting happen under the chain's own lock, so streams that offer at once cannot take the
	   writers past the limit. An entry of a writer that would be one too many is not offered at all. */
	Optional<Offer> offer(final Entry entry) {
		final WriterId writer = entry.header().writer();

		synchronized (chain) {
			if (!canTake(writer)) {
				return Optional.empty();
			}

			final Offer offer = chain.offer(entry);

			if (HOLDS_A_FEED.contains(offer.admission())) {
				writers.add(writer);
			}

			return Optional.of(offer);
		}
	}
}
