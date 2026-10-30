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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import org.unigrid.hedgehog.proxy.chain.FnvJ64;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* Where this member passes the entries of the stem of one chain on, as Dandelion++ (Fanti et al., 2018) has it. The
   member draws once for each writer, the first time it takes an entry of the writer into the stem, and keeps the draw
   for the life of the writer on the chain, until the chain forgets the writer: a writer it relays is diffused, its
   entries flooded, or relayed, with even odds, and one of its own is always relayed. A relayed writer has two
   successors drawn among the streams to the other members of the group, never a client's, outside what the writer
   avoids, and every entry of it goes to the first of them. Drawing once per writer, and not once in a while, keeps a
   member that watches who floods the entries of a writer first from ruling members out over time.

   A refusal or a round of the stem moves a writer to its second successor once for its life, and the one it moved
   off is never drawn for the writer again, so neither can walk the stem of a writer to members outside its draw. A
   successor that closes or leaves the group, or that a writer of this member's own comes to avoid, is replaced in
   its place by a member drawn outside the draw, the one a writer moved off included, and that does not count as the
   move. */
final class StemRouter {
	static final int SUCCESSORS = 2;

	/* What the member drew for one writer: whether it floods the entries of the writer it is passed, and for a writer
	   past the most it keeps, the hash that stands in for the draw. The successors are in their places, the route
	   first; a place is empty while no member could be drawn for it, and closed once the writer moved off it. Every
	   member a place held is noted, as no other place is ever given one of them, and moved is set once a refusal or a
	   round moved the writer. */
	static final class Draw {
		private final boolean own;
		private final boolean diffuses;
		private final Optional<Long> hash;
		private final Channel[] places = new Channel[SUCCESSORS];
		private final boolean[] closed = new boolean[SUCCESSORS];
		private final Set<Channel> drawn = new HashSet<>();
		private boolean moved;

		private Draw(final boolean own, final boolean diffuses, final Optional<Long> hash) {
			this.own = own;
			this.diffuses = diffuses;
			this.hash = hash;
		}

		boolean diffuses() {
			return diffuses;
		}

		/* The places that are not closed and hold a successor, in order */
		private IntStream open() {
			return IntStream.range(0, SUCCESSORS).filter(place -> !closed[place] && places[place] != null);
		}
	}

	private final RandomGenerator random;
	private final Supplier<List<Channel>> members;
	private final Map<WriterId, Draw> draws = new HashMap<>();

	/* The most writers the member keeps a draw for, as many as the chain takes. A writer past them goes by a hash of
	   its id under a key drawn once for the chain, which keeps its mode and its successor as well, and a stream that
	   floods the stem with fresh writers makes the router hold no more. Nothing moves such a writer, as there is no
	   draw of it to move. */
	private final int maxDraws;
	private final long key;

	/* The members are the streams of the chain to the other members of its group, as the host holds them now */
	StemRouter(final RandomGenerator random, final Supplier<List<Channel>> members, final int maxDraws) {
		this.random = random;
		this.members = members;
		this.maxDraws = maxDraws;
		this.key = random.nextLong();
	}

	/* The draw of a writer this member is passed an entry of, from the given stream, which no successor of it is
	   drawn among. One the member has no draw of yet is drawn now, and kept only once an entry of the writer is
	   taken into the stem, so that entries the chain turns away hold nothing. */
	synchronized Draw drawFor(final WriterId writer, final Channel source) {
		final Draw held = draws.get(writer);

		if (held != null) {
			return held;
		}

		if (draws.size() >= maxDraws) {
			return hashed(writer);
		}

		final Draw drawn = new Draw(false, random.nextBoolean(), Optional.empty());

		if (!drawn.diffuses) {
			refresh(drawn, Set.of(source));
		}

		return drawn;
	}

	/* Keeps the draw of a writer an entry of which was taken into the stem, unless it stands for one past the most
	   the member keeps, or that many are kept already */
	synchronized void keep(final WriterId writer, final Draw draw) {
		if (draw.hash.isEmpty() && draws.size() < maxDraws) {
			draws.putIfAbsent(writer, draw);
		}
	}

	/* The successor the entries of a writer this member relays go to: its route, or the next successor while the route
	   is avoided or takes no writes; none while neither will do */
	synchronized Optional<Channel> successor(final WriterId writer, final Set<Channel> avoid) {
		return successor(Optional.ofNullable(draws.get(writer)).orElseGet(() -> hashed(writer)), avoid);
	}

	/* The same for a writer of this member's own, which is drawn the first time, among the members it does not
	   avoid, and never diffused */
	synchronized Optional<Channel> ownSuccessor(final WriterId writer, final Set<Channel> avoid) {
		Draw draw = draws.get(writer);

		if (draw == null && draws.size() < maxDraws) {
			draw = new Draw(true, false, Optional.empty());
			draws.put(writer, draw);
		}

		return successor(draw == null ? hashed(writer) : draw, avoid);
	}

	/* The successor a writer moves to when its route refused an entry of it for want of room: the second, and the
	   one that refused gets nothing more of the writer. An author and a relay move alike, once for each writer,
	   whether a refusal or a round moves it, so a second one moves nothing, and a refusal from a stream that is not
	   the route moves nothing either. Without a second successor nothing moves. None is answered while the second is
	   among what the caller avoids, though the writer moved. */
	synchronized Optional<Channel> refused(final WriterId writer, final Channel successor, final Set<Channel> avoid) {
		final Draw draw = draws.get(writer);

		if (draw == null || draw.moved) {
			return Optional.empty();
		}

		refresh(draw, avoid);

		return route(draw).filter(successor::equals).flatMap(route -> move(draw))
			.filter(next -> !avoid.contains(next));
	}

	/* Moves a writer an entry of which came round the stem, from a member it was not passed to, to its second
	   successor, as a refusal does and with the same one move: the route took the entry round, and the entries after
	   it would follow. The author and a relay move alike, though only a relay floods what came round. */
	synchronized void cameRound(final WriterId writer, final Set<Channel> avoid) {
		final Draw draw = draws.get(writer);

		if (draw != null && !draw.moved) {
			refresh(draw, avoid);
			move(draw);
		}
	}

	/* Whether a writer drawn now would find a successor outside what is avoided */
	synchronized boolean hasSuccessor(final Set<Channel> avoid) {
		final List<Channel> current = members.get();

		return current.stream().anyMatch(member -> isOpen(current, member) && !avoid.contains(member));
	}

	/* Lets go of the draws of the writers the chain forgot */
	synchronized void forget(final Collection<WriterId> writers) {
		draws.keySet().removeAll(writers);
	}

	/* The successors of a writer as they stand, the route first, for a test */
	synchronized List<Channel> drawn(final WriterId writer) {
		return Optional.ofNullable(draws.get(writer)).map(draw -> draw.open().mapToObj(place -> draw.places[place])
			.toList()).orElse(List.of());
	}

	/* How many writers the member keeps a draw for, for a test */
	synchronized int draws() {
		return draws.size();
	}

	private Optional<Channel> successor(final Draw draw, final Set<Channel> avoid) {
		final List<Channel> current = members.get();

		if (draw.hash.isPresent()) {
			final List<Channel> open = current.stream().filter(member -> isOpen(current, member)
				&& !avoid.contains(member)).distinct().toList();

			return open.isEmpty() ? Optional.empty()
				: Optional.of(open.get((int) Long.remainderUnsigned(draw.hash.get() >>> 1, open.size())));
		}

		refresh(draw, avoid);

		return draw.open().mapToObj(place -> draw.places[place])
			.filter(successor -> successor.isWritable() && !avoid.contains(successor)).findFirst();
	}

	private static Optional<Channel> route(final Draw draw) {
		return draw.open().mapToObj(place -> draw.places[place]).findFirst();
	}

	/* Closes the place of the route and answers the successor in the next place, or moves nothing without one */
	private static Optional<Channel> move(final Draw draw) {
		final int[] open = draw.open().toArray();

		if (open.length < 2) {
			return Optional.empty();
		}

		draw.closed[open[0]] = true;
		draw.moved = true;
		return Optional.of(draw.places[open[1]]);
	}

	/* Replaces a successor that closed or left the group, or that a writer of this member's own avoids now, and fills
	   a place that is empty, each with a member drawn outside the draw and outside what the writer avoids. A place
	   the writer moved off stays closed. */
	private void refresh(final Draw draw, final Set<Channel> avoid) {
		final List<Channel> current = members.get();

		draw.drawn.removeIf(member -> !member.isActive());
		IntStream.range(0, SUCCESSORS).filter(place -> !draw.closed[place])
			.forEach(place -> refill(draw, place, current, avoid));
	}

	private void refill(final Draw draw, final int place, final List<Channel> current, final Set<Channel> avoid) {
		if (draw.places[place] == null || isLost(draw, draw.places[place], current, avoid)) {
			draw.places[place] = drawn(current, draw, avoid);
		}
	}

	/* A successor is lost once it closed or left the group, and for a writer of this member's own once it avoids it */
	private static boolean isLost(final Draw draw, final Channel successor, final List<Channel> current,
		final Set<Channel> avoid) {

		return !isThere(current, successor) || draw.own && avoid.contains(successor);
	}

	/* A member drawn among those that are there and take writes, outside the draw and what is avoided, or none */
	private Channel drawn(final List<Channel> current, final Draw draw, final Set<Channel> avoid) {
		final List<Channel> candidates = current.stream().filter(member -> isOpen(current, member)
			&& !avoid.contains(member) && !draw.drawn.contains(member)).distinct().toList();

		if (candidates.isEmpty()) {
			return null;
		}

		final Channel drawn = candidates.get(random.nextInt(candidates.size()));

		draw.drawn.add(drawn);
		return drawn;
	}

	private static boolean isThere(final List<Channel> current, final Channel stream) {
		return current.contains(stream) && stream.isActive();
	}

	/* A stream that takes no writes is no member to draw while it does not, as what it was handed would pile up in
	   memory, so an exit whose only successor stopped reading answers no opening */
	private static boolean isOpen(final List<Channel> current, final Channel stream) {
		return isThere(current, stream) && stream.isWritable();
	}

	/* The draw of a writer past the most the member keeps: the lowest bit of the hash is its mode, and the others pick
	   its successor among the members it does not avoid */
	private Draw hashed(final WriterId writer) {
		final long hash = keyed(key, writer);

		return new Draw(false, (hash & 1) == 1, Optional.of(hash));
	}

	/* A hash of the id of a writer under the key, mixed as SplitMix64 mixes its state */
	private static long keyed(final long key, final WriterId writer) {
		long mixed = key ^ FnvJ64.hash(writer.bytes());

		mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
		mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
		return mixed ^ (mixed >>> 31);
	}
}
