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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* Where this member passes the entries of the stem on, as Dandelion++ (Fanti et al., 2018) has it. For each chain and
   each period of a minute the member is either a relay or a diffuser, with even odds, and keeps two successors drawn
   among the streams to the other members of the group, never a client's. Every entry of one writer goes to the same
   successor for the whole period. A successor that closes or leaves the group is passed over, and nothing is drawn in
   its place while the other one is there. A writer for which no drawn successor is left but avoided or gone, as an
   exit avoids the two members its client attached through, is routed to another member for the period instead, as
   it would otherwise have none in many a period of a small group. A successor that refused an entry of a writer is
   never replaced that way, so refusals cannot walk the stem of a writer to members outside the draw. */
final class StemRouter {
	static final long PERIOD_MILLIS = 60_000;
	static final int SUCCESSORS = 2;

	private record Period(long number, boolean diffuses, List<Channel> successors, Map<WriterId, Route> routes,
		Map<WriterId, Set<Channel>> barred, Set<WriterId> rerouted) {
		/* The mode and the successors of one chain for one period, the route of each writer in it, the successor
		   that refused an entry of a writer, which gets none of it for the rest of the period, and the writers moved
		   on a refusal already */
	}

	private record Route(Channel successor, Set<Channel> avoid) {
		/* The successor of a writer and the streams it avoided when it was routed */
	}

	private final RandomGenerator random;
	private final Function<ChainId, List<Channel>> members;
	private final Map<ChainId, Period> periods = new HashMap<>();

	/* The members are the streams of a chain to the other members of its group, as the host holds them now */
	StemRouter(final RandomGenerator random, final Function<ChainId, List<Channel>> members) {
		this.random = random;
		this.members = members;
	}

	synchronized boolean diffuses(final ChainId chainId, final long now) {
		return period(chainId, now).diffuses();
	}

	/* The successor a writer is routed to in this period, or the other one while that one is avoided or gone, and
	   another member while both are avoided; none while both are gone. What is avoided is checked on every call,
	   whatever the writer was routed to before. */
	synchronized Optional<Channel> successor(final ChainId chainId, final WriterId writer, final Set<Channel> avoid,
		final long now) {

		final Period period = period(chainId, now);
		final List<Channel> open = open(chainId, period, avoid, period.barred().getOrDefault(writer, Set.of()));
		final Route routed = period.routes().get(writer);

		if (open.isEmpty()) {
			return Optional.empty();
		}

		if (routed != null && open.contains(routed.successor())) {
			return Optional.of(routed.successor());
		}

		final Channel chosen = open.get(random.nextInt(open.size()));

		period.routes().put(writer, new Route(chosen, Set.copyOf(avoid)));
		return Optional.of(chosen);
	}

	/* The successor a writer moves to when the one it is routed to refused an entry of it for want of room: the
	   other drawn successor, if it is there and not avoided, and the one that refused gets nothing more of the writer
	   for the period. An author and a relay move alike, once for each writer in a period, so a second refusal moves
	   nothing, and a refusal from a stream the writer is not routed to moves nothing either. */
	synchronized Optional<Channel> refused(final ChainId chainId, final WriterId writer, final Channel successor,
		final long now) {

		final Period period = periods.get(chainId);
		final Route routed = period == null || period.number() != numberOf(now) ? null : period.routes().get(writer);

		if (routed == null || routed.successor() != successor || !period.rerouted().add(writer)) {
			return Optional.empty();
		}

		period.barred().put(writer, Set.of(successor));
		period.routes().remove(writer);
		return successor(chainId, writer, routed.avoid(), now);
	}

	/* The successors drawn for the period, for a test */
	synchronized List<Channel> drawn(final ChainId chainId, final long now) {
		return period(chainId, now).successors();
	}

	/* Whether any writer would find a successor outside what is avoided */
	synchronized boolean hasSuccessor(final ChainId chainId, final Set<Channel> avoid, final long now) {
		return !open(chainId, period(chainId, now), avoid, Set.of()).isEmpty();
	}

	/* Lets go of the periods that are over, those of the chains the host no longer holds among them */
	synchronized void forgetBefore(final long now) {
		final long current = numberOf(now);

		periods.values().removeIf(period -> period.number() < current);
	}

	/* How many chains have a period, for a test */
	synchronized int periods() {
		return periods.size();
	}

	/* The successors of the period that are still there and neither avoided nor barred for the writer, or while none
	   of them is barred and each is avoided or gone, the members outside the draw that are not avoided */
	private List<Channel> open(final ChainId chainId, final Period period, final Set<Channel> avoid,
		final Set<Channel> barred) {

		final List<Channel> current = members.apply(chainId);
		final List<Channel> open = period.successors().stream()
			.filter(successor -> isOpen(current, successor, avoid, barred)).toList();

		if (!open.isEmpty() || !barred.isEmpty()) {
			return open;
		}

		return current.stream().filter(member -> isOpen(current, member, avoid, Set.of())
			&& !period.successors().contains(member)).distinct().toList();
	}

	private static boolean isOpen(final List<Channel> current, final Channel stream, final Set<Channel> avoid,
		final Set<Channel> barred) {

		return current.contains(stream) && stream.isActive() && !avoid.contains(stream) && !barred.contains(stream);
	}

	private Period period(final ChainId chainId, final long now) {
		final long number = numberOf(now);
		final Period held = periods.get(chainId);

		if (held != null && held.number() == number) {
			return held;
		}

		final Period drawn = new Period(number, random.nextBoolean(), drawn(chainId), new HashMap<>(),
			new HashMap<>(), new HashSet<>());

		periods.put(chainId, drawn);
		return drawn;
	}

	private List<Channel> drawn(final ChainId chainId) {
		final List<Channel> candidates = new ArrayList<>(members.apply(chainId).stream().filter(Channel::isActive)
			.distinct().toList());

		Collections.shuffle(candidates, random);
		return List.copyOf(candidates.subList(0, Math.min(SUCCESSORS, candidates.size())));
	}

	private static long numberOf(final long now) {
		return Math.floorDiv(now, PERIOD_MILLIS);
	}
}
