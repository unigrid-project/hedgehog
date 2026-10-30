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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
   member that watches who floods the entries of a writer first from ruling members out over time. A successor that
   takes no writes for a while is skipped for what comes meanwhile and stays where it is.

   A refusal or a round of the stem moves a writer to its second successor once for its life, and the one it moved
   off is never drawn for the writer again, so neither can walk the stem of a writer to members outside its draw. A
   successor that closes or leaves the group, or that a writer of this member's own comes to avoid, leaves the writer
   to the other one, which does not count as the move. Only once both are gone is a member drawn outside the draw in
   their place, and only twice for the life of a writer, so a successor that keeps going away and coming back cannot
   walk the stem either. */
final class StemRouter {
	static final int SUCCESSORS = 2;

	/* How often a writer whose successors are all gone has one drawn in their place */
	static final int REPLACEMENTS = 2;

	/* What the member drew for one writer: whether it floods the entries of the writer it is passed, and for a writer
	   past the most it keeps, the hash that stands in for the draw. The successors that are left come route first.
	   Every member drawn for the writer is noted by its identity, as none of them is drawn again on any stream, and so
	   is how often one was drawn in
	   place of the others, whether the last went away and none could be drawn yet, and whether a refusal or a round
	   moved the writer. A writer this member relays avoids the streams its entries came in on. */
	static final class Draw {
		private final boolean own;
		private final boolean diffuses;
		private final Optional<Long> hash;
		private final List<Channel> successors = new ArrayList<>();
		private final Set<Object> drawn = new HashSet<>();
		private final Set<Channel> sources = new HashSet<>();
		private int replacements;
		private boolean wanting;
		private boolean moved;

		private Draw(final boolean own, final boolean diffuses, final Optional<Long> hash) {
			this.own = own;
			this.diffuses = diffuses;
			this.hash = hash;
		}

		boolean diffuses() {
			return diffuses;
		}
	}

	private final RandomGenerator random;
	private final Supplier<List<Channel>> members;
	private final Function<Channel, Object> identity;
	private final Map<WriterId, Draw> draws = new HashMap<>();

	/* The most writers the member keeps a draw for, as many as the chain takes. A writer past them goes by a hash of
	   its id under a key drawn once for the chain, which keeps its mode and its successor as well, and a stream that
	   floods the stem with fresh writers makes the router hold no more. Nothing moves such a writer, as there is no
	   draw of it to move. */
	private final int maxDraws;
	private final long key;

	/* The members are the streams of the chain to the other members of its group, as the host holds them now, and the
	   identity of a stream is the member it goes to, which a stream that closed and came back keeps */
	StemRouter(final RandomGenerator random, final Supplier<List<Channel>> members,
		final Function<Channel, Object> identity, final int maxDraws) {

		this.random = random;
		this.members = members;
		this.identity = identity;
		this.maxDraws = maxDraws;
		this.key = random.nextLong();
	}

	/* A router whose streams are their own identity, for a test */
	StemRouter(final RandomGenerator random, final Supplier<List<Channel>> members, final int maxDraws) {
		this(random, members, stream -> stream, maxDraws);
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

		drawn.sources.add(source);

		if (!drawn.diffuses) {
			drawFirst(drawn, drawn.sources);
		}

		return drawn;
	}

	/* Keeps the draw of a writer an entry of which was taken into the stem, unless it stands for one past the most
	   the member keeps, or that many are kept already, and notes the stream the entry came in on among those the
	   writer avoids */
	synchronized void keep(final WriterId writer, final Draw draw, final Channel source) {
		if (draw.hash.isEmpty() && draws.size() < maxDraws) {
			draws.putIfAbsent(writer, draw);
		}

		Optional.ofNullable(draws.get(writer)).ifPresent(held -> held.sources.add(source));
	}

	/* The route of a writer this member relays. It stays the route while it takes no writes, and while it is the stream
	   an entry came in on, and the caller skips it for what it would send now, which waits in the embargo. A writer
	   past the most the member keeps goes to a member outside what the caller avoids. */
	synchronized Optional<Channel> successor(final WriterId writer, final Set<Channel> avoid) {
		return successor(Optional.ofNullable(draws.get(writer)).orElseGet(() -> hashed(writer)), avoid);
	}

	/* The same for a writer of this member's own, which is drawn the first time, among the members it does not
	   avoid, and never diffused */
	synchronized Optional<Channel> ownSuccessor(final WriterId writer, final Set<Channel> avoid) {
		Draw draw = draws.get(writer);

		if (draw == null && draws.size() < maxDraws) {
			draw = new Draw(true, false, Optional.empty());
			drawFirst(draw, avoid);
			draws.put(writer, draw);
		}

		return successor(draw == null ? hashed(writer) : draw, avoid);
	}

	/* The successor a writer moves to when its route refused an entry of it for want of room: the second, and the
	   one that refused gets nothing more of the writer. An author and a relay move alike, once for each writer,
	   whether a refusal or a round moves it, so a second one moves nothing, and a refusal from a stream that is not
	   the route moves nothing either. Without a second successor nothing moves. What a writer of this member's own
	   avoids is given by the caller. */
	synchronized Optional<Channel> refused(final WriterId writer, final Channel successor, final Set<Channel> avoid) {
		final Draw draw = draws.get(writer);

		if (draw == null || draw.moved) {
			return Optional.empty();
		}

		refresh(draw, avoid);
		return route(draw).filter(successor::equals).flatMap(route -> move(draw));
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
		return Optional.ofNullable(draws.get(writer)).map(draw -> List.copyOf(draw.successors)).orElse(List.of());
	}

	/* How many writers the member keeps a draw for, for a test */
	synchronized int draws() {
		return draws.size();
	}

	private Optional<Channel> successor(final Draw draw, final Set<Channel> avoid) {
		final List<Channel> current = members.get();

		if (draw.hash.isPresent()) {
			final List<Channel> open = current.stream().filter(member -> isThere(current, member)
				&& !avoid.contains(member)).distinct().toList();

			return open.isEmpty() ? Optional.empty()
				: Optional.of(open.get((int) Long.remainderUnsigned(draw.hash.get() >>> 1, open.size())));
		}

		refresh(draw, avoid);
		return route(draw);
	}

	private static Optional<Channel> route(final Draw draw) {
		return draw.successors.stream().findFirst();
	}

	/* Drops the route and answers the successor after it, or moves nothing without one */
	private static Optional<Channel> move(final Draw draw) {
		if (draw.successors.size() < SUCCESSORS) {
			return Optional.empty();
		}

		draw.successors.remove(0);
		draw.moved = true;
		return route(draw);
	}

	/* The successors a writer is drawn with the first time, as many as there are members to draw */
	private void drawFirst(final Draw draw, final Set<Channel> avoid) {
		final List<Channel> current = members.get();

		IntStream.range(0, SUCCESSORS).mapToObj(successor -> drawn(current, draw, avoid)).filter(Objects::nonNull)
			.forEach(draw.successors::add);
		draw.wanting = draw.successors.isEmpty();
	}

	/* Drops a successor that closed or left the group, or that a writer of this member's own avoids now, and once none
	   is left that way, draws one outside the draw and outside what the writer avoids, as long as the writer has had
	   fewer than REPLACEMENTS drawn so: for a writer of its own what the caller gives, and for one it relays the
	   streams its entries came in on. A writer left without one by a refusal has none drawn. */
	private void refresh(final Draw draw, final Set<Channel> avoid) {
		final List<Channel> current = members.get();

		draw.sources.removeIf(member -> !member.isActive());

		if (draw.successors.removeIf(successor -> isLost(draw, successor, current, avoid))) {
			draw.wanting = draw.successors.isEmpty();
		}

		if (draw.wanting && draw.replacements < REPLACEMENTS) {
			replace(draw, current, draw.own ? avoid : draw.sources);
		}
	}

	private void replace(final Draw draw, final List<Channel> current, final Set<Channel> avoid) {
		final Channel replacement = drawn(current, draw, avoid);

		if (replacement != null) {
			draw.successors.add(replacement);
			draw.replacements++;
			draw.wanting = false;
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
			&& !avoid.contains(member) && !draw.drawn.contains(identity.apply(member))).distinct().toList();

		if (candidates.isEmpty()) {
			return null;
		}

		final Channel drawn = candidates.get(random.nextInt(candidates.size()));

		draw.drawn.add(identity.apply(drawn));
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
