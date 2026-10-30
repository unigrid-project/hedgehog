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
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.unigrid.hedgehog.proxy.chain.FnvJ64;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* Where this member passes the entries of the stem of one chain on, as Dandelion++ (Fanti et al., 2018) has it. The
   member draws once for each writer, the first time it takes an entry of the writer into the stem, and keeps the draw
   for the life of the writer on the chain: a writer it relays is diffused, its entries flooded, or relayed, with even
   odds, and one of its own is always relayed. A relayed writer has two successors drawn among the streams to the
   other members of the group, never a client's, outside what the writer avoids, and every entry of it goes to the
   first of them. Drawing once per writer, and not once in a while, keeps a member that watches who floods the entries
   of a writer first from ruling members out over time. A successor that closes or leaves the group is passed over for
   the other one. A writer for which each drawn successor is avoided or gone is routed to another member instead,
   which it keeps. A successor that refused an entry of a writer counts as gone for the writer but is never replaced
   that way, so refusals cannot walk the stem of a writer to members outside the draw. */
final class StemRouter {
	static final int SUCCESSORS = 2;

	/* What the member drew for one writer: whether it floods the entries of the writer it is passed, the successors
	   it passes the others on to, the first of them the route, and for a writer past the most it keeps, the hash that
	   stands in for the draw. The route is the successor the entries go to now, the barred one refused an entry of the
	   writer and gets none of it any more, and moved is set once a refusal moved the writer. */
	static final class Draw {
		private final boolean diffuses;
		private final List<Channel> successors;
		private final Optional<Long> hash;
		private Channel route;
		private Channel barred;
		private boolean moved;

		private Draw(final boolean diffuses, final List<Channel> successors, final Optional<Long> hash) {
			this.diffuses = diffuses;
			this.successors = successors;
			this.hash = hash;
		}

		boolean diffuses() {
			return diffuses;
		}
	}

	private final RandomGenerator random;
	private final Supplier<List<Channel>> members;
	private final Map<WriterId, Draw> draws = new HashMap<>();

	/* The most writers the member keeps a draw for, as many as the chain takes. A writer past them goes by a hash of
	   its id under a key drawn once for the chain, which keeps its mode and its successor as well, and a stream that
	   floods the stem with fresh writers makes the router hold no more. A refusal moves such a writer nowhere, as
	   there is no draw of it to move. */
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

		final boolean diffuses = random.nextBoolean();

		return new Draw(diffuses, diffuses ? List.of() : drawn(Set.of(source)), Optional.empty());
	}

	/* Keeps the draw of a writer an entry of which was taken into the stem, unless it stands for one past the most
	   the member keeps, or that many are kept already */
	synchronized void keep(final WriterId writer, final Draw draw) {
		if (draw.hash.isEmpty() && draws.size() < maxDraws) {
			draws.putIfAbsent(writer, draw);
		}
	}

	/* The successor the entries of a writer this member relays go to, or the other one while that one is avoided or
	   gone, and another member while both are avoided; none while both are gone. What is avoided is checked on every
	   call, whatever the writer was routed to before. */
	synchronized Optional<Channel> successor(final WriterId writer, final Set<Channel> avoid) {
		return successor(Optional.ofNullable(draws.get(writer)).orElseGet(() -> hashed(writer)), avoid);
	}

	/* The same for a writer of this member's own, which is drawn the first time, among the members it does not
	   avoid, and never diffused */
	synchronized Optional<Channel> ownSuccessor(final WriterId writer, final Set<Channel> avoid) {
		Draw draw = draws.get(writer);

		if (draw == null && draws.size() < maxDraws) {
			draw = new Draw(false, drawn(avoid), Optional.empty());
			draws.put(writer, draw);
		}

		return successor(draw == null ? hashed(writer) : draw, avoid);
	}

	/* The successor a writer moves to when the one it is routed to refused an entry of it for want of room: the
	   other drawn successor, if it is there and not among what the caller avoids now, and the one that refused gets
	   nothing more of the writer. An author and a relay move alike, once for each writer, so a second refusal moves
	   nothing, and a refusal from a stream the writer is not routed to moves nothing either. Without another
	   successor nothing moves, and the writer stays on its route. */
	synchronized Optional<Channel> refused(final WriterId writer, final Channel successor, final Set<Channel> avoid) {
		final Draw draw = draws.get(writer);

		if (draw == null || draw.moved || draw.route != successor) {
			return Optional.empty();
		}

		draw.barred = successor;

		final List<Channel> open = open(draw, avoid);

		if (open.isEmpty()) {
			draw.barred = null;
			return Optional.empty();
		}

		draw.moved = true;
		draw.route = open.get(0);
		return Optional.of(draw.route);
	}

	/* Whether a writer drawn now would find a successor outside what is avoided */
	synchronized boolean hasSuccessor(final Set<Channel> avoid) {
		final List<Channel> current = members.get();

		return current.stream().anyMatch(member -> isOpen(current, member, avoid, null));
	}

	/* The successors drawn for a writer, for a test */
	synchronized List<Channel> drawn(final WriterId writer) {
		return Optional.ofNullable(draws.get(writer)).map(draw -> draw.successors).orElse(List.of());
	}

	/* How many writers the member keeps a draw for, for a test */
	synchronized int draws() {
		return draws.size();
	}

	private Optional<Channel> successor(final Draw draw, final Set<Channel> avoid) {
		final List<Channel> open = open(draw, avoid);

		if (open.isEmpty()) {
			return Optional.empty();
		}

		if (draw.hash.isPresent()) {
			return Optional.of(open.get((int) Long.remainderUnsigned(draw.hash.get() >>> 1, open.size())));
		}

		if (draw.route == null || !open.contains(draw.route)) {
			final boolean drawn = draw.successors.contains(open.get(0));

			draw.route = drawn ? open.get(0) : open.get(random.nextInt(open.size()));
		}

		return Optional.of(draw.route);
	}

	/* The successors of the writer that are still there and neither avoided nor barred, or while none of them is
	   barred and each is avoided or gone, the members outside the draw that are not avoided. A writer past the most
	   the member keeps has every member that is not avoided. */
	private List<Channel> open(final Draw draw, final Set<Channel> avoid) {
		final List<Channel> current = members.get();
		final List<Channel> open = draw.successors.stream()
			.filter(successor -> isOpen(current, successor, avoid, draw.barred)).toList();

		if (!open.isEmpty() || draw.barred != null) {
			return open;
		}

		return current.stream().filter(member -> isOpen(current, member, avoid, null)
			&& !draw.successors.contains(member)).distinct().toList();
	}

	/* A stream that takes no writes is passed over while it does not, as what it was handed would pile up in memory,
	   so an exit whose only successor stopped reading answers no opening */
	private static boolean isOpen(final List<Channel> current, final Channel stream, final Set<Channel> avoid,
		final Channel barred) {

		return current.contains(stream) && stream.isActive() && stream.isWritable() && !avoid.contains(stream)
			&& stream != barred;
	}

	/* The successors of a writer drawn now, among the members it does not avoid */
	private List<Channel> drawn(final Set<Channel> avoid) {
		final List<Channel> current = members.get();
		final List<Channel> candidates = new ArrayList<>(current.stream()
			.filter(member -> isOpen(current, member, avoid, null)).distinct().toList());

		Collections.shuffle(candidates, random);
		return List.copyOf(candidates.subList(0, Math.min(SUCCESSORS, candidates.size())));
	}

	/* The draw of a writer past the most the member keeps: the lowest bit of the hash is its mode, and the others pick
	   its successor among the members it does not avoid */
	private Draw hashed(final WriterId writer) {
		final long hash = keyed(key, writer);

		return new Draw((hash & 1) == 1, List.of(), Optional.of(hash));
	}

	/* A hash of the id of a writer under the key, mixed as SplitMix64 mixes its state */
	private static long keyed(final long key, final WriterId writer) {
		long mixed = key ^ FnvJ64.hash(writer.bytes());

		mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
		mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
		return mixed ^ (mixed >>> 31);
	}
}
