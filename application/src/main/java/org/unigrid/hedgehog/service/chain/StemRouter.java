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
   successor for the whole period. A successor that closes or leaves the group is passed over and nothing is drawn in
   its place before the period is over, so a member that drops its streams cannot steer the stem to another. */
final class StemRouter {
	static final long PERIOD_MILLIS = 60_000;
	static final int SUCCESSORS = 2;

	private record Period(long number, boolean diffuses, List<Channel> successors, Map<WriterId, Channel> routes) {
		/* The mode and the successors of one chain for one period, and the successor of each writer routed in it */
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
	   none while both are. What is avoided is checked on every call, whatever the writer was routed to before. */
	synchronized Optional<Channel> successor(final ChainId chainId, final WriterId writer, final Set<Channel> avoid,
		final long now) {

		final Period period = period(chainId, now);
		final List<Channel> open = open(chainId, period, avoid);
		final Channel routed = period.routes().get(writer);

		if (open.isEmpty()) {
			return Optional.empty();
		}

		if (routed != null && open.contains(routed)) {
			return Optional.of(routed);
		}

		final Channel chosen = open.get(random.nextInt(open.size()));

		period.routes().put(writer, chosen);
		return Optional.of(chosen);
	}

	/* Whether any writer would find a successor outside what is avoided */
	synchronized boolean hasSuccessor(final ChainId chainId, final Set<Channel> avoid, final long now) {
		return !open(chainId, period(chainId, now), avoid).isEmpty();
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

	private List<Channel> open(final ChainId chainId, final Period period, final Set<Channel> avoid) {
		final List<Channel> current = members.apply(chainId);

		return period.successors().stream().filter(successor -> !avoid.contains(successor)
			&& current.contains(successor) && successor.isActive()).toList();
	}

	private Period period(final ChainId chainId, final long now) {
		final long number = numberOf(now);
		final Period held = periods.get(chainId);

		if (held != null && held.number() == number) {
			return held;
		}

		final Period drawn = new Period(number, random.nextBoolean(), drawn(chainId), new HashMap<>());

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
