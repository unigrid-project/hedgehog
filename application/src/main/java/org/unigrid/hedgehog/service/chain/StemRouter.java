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
import org.unigrid.hedgehog.proxy.chain.FnvJ64;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* Where this member passes the entries of the stem on, as Dandelion++ (Fanti et al., 2018) has it. For each chain and
   each period of a minute the member is either a relay or a diffuser, with even odds, and keeps two successors drawn
   among the streams to the other members of the group, never a client's. Every entry of one writer goes to the same
   successor for the whole period. A successor that closes or leaves the group is passed over for the other one. A
   writer for which each drawn successor is avoided or gone, as when an exit avoids the two members its client
   attached through, or when one successor went and the writer avoids the other, is routed to another member for the
   period instead, as it would otherwise have none in many a period of a small group. A successor that refused an
   entry of a writer counts as gone for the writer but is never replaced that way, so refusals cannot walk the stem
   of a writer to members outside the draw. */
final class StemRouter {
	static final long PERIOD_MILLIS = 60_000;
	static final int SUCCESSORS = 2;

	private record Period(long number, boolean diffuses, List<Channel> successors, long key,
		Map<WriterId, Channel> routes, Map<WriterId, Set<Channel>> barred, Set<WriterId> rerouted) {
		/* The mode and the successors of one chain for one period, the key that routes the writers past the most it
		   keeps, the successor of each writer routed in it, the successor that refused an entry of a writer, which
		   gets none of it for the rest of the period, and the writers moved on a refusal already */
	}

	private final RandomGenerator random;
	private final Function<ChainId, List<Channel>> members;
	private final Map<ChainId, Period> periods = new HashMap<>();

	/* The most writers of one chain that a period keeps a route for, as many as the chain takes. A writer past them
	   goes by a hash of its id under the key of the period, which keeps it on one successor for the period as well,
	   and a stream that floods the stem with fresh writers makes the router hold no more. A refusal moves such a writer
	   nowhere, as there is no route of it to move. */
	private final int maxRoutes;

	/* The members are the streams of a chain to the other members of its group, as the host holds them now */
	StemRouter(final RandomGenerator random, final Function<ChainId, List<Channel>> members) {
		this(random, members, HostedChain.MAX_WRITERS);
	}

	/* A parameter only so that a test reaches the cap without routing thousands of writers */
	StemRouter(final RandomGenerator random, final Function<ChainId, List<Channel>> members, final int maxRoutes) {
		this.random = random;
		this.members = members;
		this.maxRoutes = maxRoutes;
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
		final Channel routed = period.routes().get(writer);

		if (open.isEmpty()) {
			return Optional.empty();
		}

		if (routed != null && open.contains(routed)) {
			return Optional.of(routed);
		}

		if (routed == null && period.routes().size() >= maxRoutes) {
			return Optional.of(open.get((int) Long.remainderUnsigned(keyed(period.key(), writer), open.size())));
		}

		final Channel chosen = open.get(random.nextInt(open.size()));

		period.routes().put(writer, chosen);
		return Optional.of(chosen);
	}

	/* The successor a writer moves to when the one it is routed to refused an entry of it for want of room: the
	   other drawn successor, if it is there and not among what the caller avoids now, and the one that refused gets
	   nothing more of the writer for the period. An author and a relay move alike, once for each writer in a period,
	   so a second refusal moves nothing, and a refusal from a stream the writer is not routed to moves nothing
	   either. */
	synchronized Optional<Channel> refused(final ChainId chainId, final WriterId writer, final Channel successor,
		final Set<Channel> avoid, final long now) {

		final Period period = periods.get(chainId);
		final boolean current = period != null && period.number() == numberOf(now);

		if (!current || period.routes().get(writer) != successor || !period.rerouted().add(writer)) {
			return Optional.empty();
		}

		period.barred().put(writer, Set.of(successor));
		period.routes().remove(writer);
		return successor(chainId, writer, avoid, now);
	}

	/* How many writers the period keeps a route for, for a test */
	synchronized int routed(final ChainId chainId, final long now) {
		return period(chainId, now).routes().size();
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

	/* A stream that takes no writes is passed over while it does not, as what it was handed would pile up in memory,
	   so an exit whose only successor stopped reading answers no opening */
	private static boolean isOpen(final List<Channel> current, final Channel stream, final Set<Channel> avoid,
		final Set<Channel> barred) {

		return current.contains(stream) && stream.isActive() && stream.isWritable() && !avoid.contains(stream)
			&& !barred.contains(stream);
	}

	private Period period(final ChainId chainId, final long now) {
		final long number = numberOf(now);
		final Period held = periods.get(chainId);

		if (held != null && held.number() == number) {
			return held;
		}

		final Period drawn = new Period(number, random.nextBoolean(), drawn(chainId), random.nextLong(),
			new HashMap<>(), new HashMap<>(), new HashSet<>());

		periods.put(chainId, drawn);
		return drawn;
	}

	private List<Channel> drawn(final ChainId chainId) {
		final List<Channel> candidates = new ArrayList<>(members.apply(chainId).stream().filter(Channel::isActive)
			.distinct().toList());

		Collections.shuffle(candidates, random);
		return List.copyOf(candidates.subList(0, Math.min(SUCCESSORS, candidates.size())));
	}

	/* A hash of the id of a writer under the key, mixed as SplitMix64 mixes its state */
	private static long keyed(final long key, final WriterId writer) {
		long mixed = key ^ FnvJ64.hash(writer.bytes());

		mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
		mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
		return mixed ^ (mixed >>> 31);
	}

	private static long numberOf(final long now) {
		return Math.floorDiv(now, PERIOD_MILLIS);
	}
}
