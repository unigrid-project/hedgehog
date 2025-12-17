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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Offer;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* One chain this node hosts, with what the host knows about it beyond the entries */
@RequiredArgsConstructor
final class HostedChain {
	/* A chain never forgets a feed head, so the writers it takes are bounded. The bound is what one packet of
	   heads carries, which keeps every reconcile and join reply in one packet. */
	static final int MAX_WRITERS = ChainCodecs.MAX_HEADS_PER_PACKET;

	private static final Set<Admission> HOLDS_A_FEED = Set.of(Admission.ACCEPTED, Admission.AHEAD);

	enum Attachment { NEW, KNOWN, REFUSED }

	@Getter private final Chain chain;

	/* A parameter only so that a test reaches the limit without taking thousands of writers */
	private final int maxWriters;
	private final Map<WriterId, Channel> clients = new ConcurrentHashMap<>();
	private final Set<WriterId> writers = ConcurrentHashMap.newKeySet();

	/* The streams this node holds to the other members of the group, looked up when the hosting is refreshed.
	   Replaced as a whole by an immutable list and never changed in place, volatile so that the netty threads
	   which read it see the list the refresh thread has just written. */
	@Getter @Setter private volatile List<Channel> memberStreams = List.of();

	HostedChain(final Chain chain) {
		this(chain, MAX_WRITERS);
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

	/* Members relay the entries of any writer, a client only posts its own */
	boolean accepts(final Channel stream, final WriterId writer) {
		return stream != null && (memberStreams.contains(stream) || clients.get(writer) == stream);
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
