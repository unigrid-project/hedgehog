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

package org.unigrid.hedgehog.ledger;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/* What one peer may ask of this node. A peer has a bucket of tokens for each kind of message and a score that
   its misbehaviour raises. One that runs dry too often, or reaches the score limit, is ignored for a while: the
   caller does no work for it. This protects the node and authenticates no one.

   Tokens are counted in thousandths, so that a refill by milliseconds is exact in whole numbers. The peers are
   kept in the order of their last activity and at most `maxPeers` of them are tracked, so a flood of new peers
   cannot grow memory. The clock is a reading in milliseconds that may jump either way; a jump back creates no
   tokens and never makes a penalty longer than `penaltyMillis`. Not thread-safe: the serial executor of the
   driver is the only caller. */
public final class PeerLimiter {
	private static final long TOKEN = 1000;

	public enum Kind {
		TRANSACTION, HEARTBEAT, CONSENSUS, STATUS, BLOCK_REQUEST, BLOCK_BATCH
	}

	public record Rate(int capacity, int refillPerSecond) {
		public Rate {
			if (capacity < 1 || refillPerSecond < 1) {
				throw new IllegalArgumentException("A bucket holds and refills at least 1 token, found "
					+ capacity + " and " + refillPerSecond);
			}
		}
	}

	public record Policy(Map<Kind, Rate> rates, int scoreLimit, long penaltyMillis, int maxPeers) {
		public static final Policy DEFAULT = new Policy(Map.of(Kind.TRANSACTION, new Rate(200, 50),
			Kind.HEARTBEAT, new Rate(20, 2), Kind.CONSENSUS, new Rate(2000, 500), Kind.STATUS, new Rate(10, 1),
			Kind.BLOCK_REQUEST, new Rate(20, 5), Kind.BLOCK_BATCH, new Rate(4, 2)), 100, 60_000, 10_000);

		public Policy {
			if (!rates.keySet().containsAll(EnumSet.allOf(Kind.class)) || scoreLimit < 1 || penaltyMillis < 1
				|| maxPeers < 1) {
				throw new IllegalArgumentException("A policy has a rate for every kind and limits above 0");
			}

			rates = Collections.unmodifiableMap(new EnumMap<>(rates));
		}
	}

	private static final class Bucket {
		private long millitokens;
		private long stamp;

		private Bucket(long millitokens, long stamp) {
			this.millitokens = millitokens;
			this.stamp = stamp;
		}
	}

	private static final class Peer {
		private final Map<Kind, Bucket> buckets = new EnumMap<>(Kind.class);
		private int score;
		private long penalisedUntil = Long.MIN_VALUE;
	}

	private final LongSupplier clock;
	private final Policy policy;
	private final Map<String, Peer> peers = new LinkedHashMap<>();

	public PeerLimiter(LongSupplier clock, Policy policy) {
		this.clock = clock;
		this.policy = policy;
	}

	public boolean allow(String id, Kind kind) {
		final long now = clock.getAsLong();
		final Peer peer = track(id, now);

		if (peer == null || penalised(peer, now)) {
			return false;
		}

		if (take(peer, kind, now)) {
			return true;
		}

		raise(peer, 1, now);
		return false;
	}

	public void misbehaved(String id, int weight) {
		if (weight < 1) {
			throw new IllegalArgumentException("A weight is at least 1, found " + weight);
		}

		final long now = clock.getAsLong();
		final Peer peer = track(id, now);

		if (peer != null && !penalised(peer, now)) {
			raise(peer, weight, now);
		}
	}

	public boolean isPenalised(String id) {
		final Peer peer = peers.get(id);
		return peer != null && penalised(peer, clock.getAsLong());
	}

	int tracked() {
		return peers.size();
	}

	/* The peer moves to the end of the order, and a new one takes the room of the least recently active peer that is
	   not penalised. If every tracked peer is penalised there is no room and the newcomer is not tracked, which
	   leaves it refused: a node under that flood does no work for strangers. */
	private Peer track(String id, long now) {
		final Peer known = peers.remove(id);

		if (known == null && !makeRoom(now)) {
			return null;
		}

		final Peer peer = Objects.requireNonNullElseGet(known, Peer::new);
		peers.put(id, peer);
		return peer;
	}

	private boolean makeRoom(long now) {
		if (peers.size() < policy.maxPeers()) {
			return true;
		}

		for (final Iterator<Peer> oldest = peers.values().iterator(); oldest.hasNext();) {
			if (!penalised(oldest.next(), now)) {
				oldest.remove();
				return true;
			}
		}

		return false;
	}

	/* A penalty never has more than `penaltyMillis` to run, so a clock set back cannot stretch it; one that has
	   ended is forgotten, so a clock set back later cannot bring it back. */
	private boolean penalised(Peer peer, long now) {
		final long until = Math.min(peer.penalisedUntil, saturatedSum(now, policy.penaltyMillis()));
		peer.penalisedUntil = now < until ? until : Long.MIN_VALUE;

		return now < until;
	}

	/* A peer starts again with full buckets and no score when its penalty ends, so both are cleared as it starts:
	   nothing changes while it lasts. */
	private void raise(Peer peer, int weight, long now) {
		final long total = (long) peer.score + weight;

		if (total >= policy.scoreLimit()) {
			peer.score = 0;
			peer.buckets.clear();
			peer.penalisedUntil = saturatedSum(now, policy.penaltyMillis());
		} else {
			peer.score = (int) total;
		}
	}

	private boolean take(Peer peer, Kind kind, long now) {
		final Rate rate = policy.rates().get(kind);
		final Bucket bucket = peer.buckets.computeIfAbsent(kind, key -> new Bucket(rate.capacity() * TOKEN, now));

		refill(bucket, rate, now);

		if (bucket.millitokens < TOKEN) {
			return false;
		}

		bucket.millitokens -= TOKEN;
		return true;
	}

	/* A reading at or before the last one adds nothing and becomes the new last one, so a clock that was set back
	   for good does not hold a bucket empty until it passes the old reading again. The elapsed time is compared as
	   unsigned: it may not fit a signed long when the clock runs from one end of its range to the other. Past the
	   time a bucket needs to fill, the product is never taken. */
	private static void refill(Bucket bucket, Rate rate, long now) {
		final long ceiling = rate.capacity() * TOKEN;

		if (now > bucket.stamp) {
			final long elapsed = now - bucket.stamp;
			final boolean filled = Long.compareUnsigned(elapsed, ceiling / rate.refillPerSecond() + 1) >= 0;

			bucket.millitokens = filled ? ceiling
				: Math.min(ceiling, bucket.millitokens + elapsed * rate.refillPerSecond());
		}

		bucket.stamp = now;
	}

	private static long saturatedSum(long reading, long span) {
		return reading > Long.MAX_VALUE - span ? Long.MAX_VALUE : reading + span;
	}
}
