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
   its misbehaviour raises and time lowers. One that runs dry too often, or reaches the score limit, is ignored
   for a while: the caller does no work for it. A bucket for each kind is also shared by all peers, so that
   changing address does not multiply the work. This protects the node and authenticates no one.

   Peers are tracked in two maps. The active ones are in the order of their last activity, and a newcomer takes
   the room of the least recently active, so a flood of new peers costs a step each and never locks anyone out.
   An active peer that is dropped starts again with no score, but a penalised peer is not in that map: no flood
   of newcomers can free it. The penalised ones are in the order of their penalty, which is also the order it
   ends in, so what has ended is dropped from the head. Both maps are limited, and nothing is scanned.

   At the cap of penalised peers, `maxPeers / 2`, a new penalty frees the oldest one before its time. That is the
   price of a bounded map: to free one penalised address an attacker needs that many penalised ones at once.

   Time is what the limiter has seen the clock add. A reading at or before the last one adds nothing, so a jump
   back creates no tokens, lowers no score and does not make a penalty longer; a jump forward is counted in full,
   up to the end of the range of a long. Tokens are counted in thousandths and the score in sixty-thousandths of
   a point, so that refills and decay by milliseconds are exact in whole numbers. Not thread-safe: the serial
   executor of the driver is the only caller. */
public final class PeerLimiter {
	private static final long TOKEN = 1000;
	private static final long POINT = 60_000;

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

	/* `rates` is what each peer gets and `global` what all peers share. Active peers number at most `maxPeers` and
	   penalised ones at most half of that. */
	public record Policy(Map<Kind, Rate> rates, Map<Kind, Rate> global, int scoreLimit, int scoreDecayPerMinute,
		long penaltyMillis, int maxPeers) {
		public static final Policy DEFAULT = new Policy(
			Map.of(Kind.TRANSACTION, new Rate(200, 50), Kind.HEARTBEAT, new Rate(20, 2),
				Kind.CONSENSUS, new Rate(2000, 500), Kind.STATUS, new Rate(10, 1),
				Kind.BLOCK_REQUEST, new Rate(20, 5), Kind.BLOCK_BATCH, new Rate(4, 2)),
			Map.of(Kind.TRANSACTION, new Rate(2000, 500), Kind.HEARTBEAT, new Rate(500, 20),
				Kind.CONSENSUS, new Rate(20_000, 5000), Kind.STATUS, new Rate(200, 20),
				Kind.BLOCK_REQUEST, new Rate(100, 50), Kind.BLOCK_BATCH, new Rate(50, 20)),
			100, 10, 60_000, 10_000);

		public Policy {
			if (!covers(rates) || !covers(global)) {
				throw new IllegalArgumentException("A policy has a rate for each kind, per peer and shared");
			}

			if (!sensible(scoreLimit, scoreDecayPerMinute, penaltyMillis, maxPeers)) {
				throw new IllegalArgumentException("A policy has a score limit and a penalty above 0, no"
					+ " negative decay, and room for two peers");
			}

			rates = Collections.unmodifiableMap(new EnumMap<>(rates));
			global = Collections.unmodifiableMap(new EnumMap<>(global));
		}

		private static boolean covers(Map<Kind, Rate> rates) {
			return rates.keySet().containsAll(EnumSet.allOf(Kind.class));
		}

		private static boolean sensible(int scoreLimit, int decay, long penaltyMillis, int maxPeers) {
			return scoreLimit >= 1 && decay >= 0 && penaltyMillis >= 1 && maxPeers >= 2;
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
		private long score;
		private long scoredAt;

		private Peer(long scoredAt) {
			this.scoredAt = scoredAt;
		}
	}

	private final LongSupplier clock;
	private final Policy policy;
	private final Map<String, Peer> active = new LinkedHashMap<>();
	private final Map<String, Long> penalised = new LinkedHashMap<>();
	private final Map<Kind, Bucket> shared = new EnumMap<>(Kind.class);
	private long lastReading;
	private long time;
	private long inspected;

	public PeerLimiter(LongSupplier clock, Policy policy) {
		this.clock = clock;
		this.policy = policy;
		this.lastReading = clock.getAsLong();
	}

	/* A refusal because the peer has used up its own bucket costs it score and takes nothing from the shared one.
	   A refusal because the shared bucket is empty costs it neither: it took no more than it may. */
	public boolean allow(String id, Kind kind) {
		final long now = advance();

		if (penalised(id, now)) {
			return false;
		}

		final Peer peer = track(id, now);
		final Bucket own = refilled(peer.buckets, kind, policy.rates().get(kind), now);
		final Bucket everyone = refilled(shared, kind, policy.global().get(kind), now);

		if (own.millitokens < TOKEN) {
			raise(id, peer, 1, now);
			return false;
		}

		if (everyone.millitokens < TOKEN) {
			return false;
		}

		own.millitokens -= TOKEN;
		everyone.millitokens -= TOKEN;
		return true;
	}

	public void misbehaved(String id, int weight) {
		if (weight < 1) {
			throw new IllegalArgumentException("A weight is at least 1, found " + weight);
		}

		final long now = advance();

		if (!penalised(id, now)) {
			raise(id, track(id, now), weight, now);
		}
	}

	/* The peer is ignored for the whole penalty at once, whatever its score: for what needs no score to judge, such
	   as an item that made the node fail. A peer that is penalised already keeps the end of the penalty it has. */
	public void ban(String id) {
		final long now = advance();

		if (!penalised(id, now)) {
			penalise(id, now);
		}
	}

	/* False for a peer that is not tracked: one whose penalty ended, one that was dropped to make room for a newer
	   penalty, and one never seen. Nothing is held against a peer once it is forgotten. */
	public boolean isPenalised(String id) {
		return penalised(id, advance());
	}

	int tracked() {
		return active.size() + penalised.size();
	}

	long inspected() {
		return inspected;
	}

	private long advance() {
		final long reading = clock.getAsLong();

		if (reading > lastReading) {
			final long gap = reading - lastReading;
			time = saturatedSum(time, gap < 0 ? Long.MAX_VALUE : gap);
		}

		lastReading = reading;
		return time;
	}

	private boolean penalised(String id, long now) {
		expire(now);
		return penalised.containsKey(id);
	}

	/* Penalties last the same time and time never runs back, so the first one is the first to end */
	private void expire(long now) {
		final Iterator<Long> ends = penalised.values().iterator();

		while (ends.hasNext()) {
			inspected++;

			if (ends.next() > now) {
				return;
			}

			ends.remove();
		}
	}

	private Peer track(String id, long now) {
		final Peer known = active.remove(id);

		if (known == null && active.size() >= policy.maxPeers()) {
			dropEldest(active);
		}

		final Peer peer = Objects.requireNonNullElseGet(known, () -> new Peer(now));
		active.put(id, peer);
		return peer;
	}

	private void dropEldest(Map<String, ?> entries) {
		final Iterator<String> eldest = entries.keySet().iterator();

		inspected++;
		eldest.next();
		eldest.remove();
	}

	private void raise(String id, Peer peer, int weight, long now) {
		final long total = decayed(peer, now) + weight * POINT;

		if (total >= policy.scoreLimit() * POINT) {
			penalise(id, now);
		} else {
			peer.score = total;
			peer.scoredAt = now;
		}
	}

	/* A peer starts again with full buckets and no score when its penalty ends, and a peer that is not tracked
	   does, so nothing of it is kept: nothing changes while the penalty lasts. */
	private void penalise(String id, long now) {
		active.remove(id);

		if (penalised.size() >= policy.maxPeers() / 2) {
			dropEldest(penalised);
		}

		penalised.put(id, saturatedSum(now, policy.penaltyMillis()));
	}

	/* The score never goes below 0. Past the time it takes to reach 0 the product is never taken. */
	private long decayed(Peer peer, long now) {
		final long decay = policy.scoreDecayPerMinute();
		final long elapsed = now - peer.scoredAt;

		if (decay == 0) {
			return peer.score;
		}

		return elapsed > peer.score / decay ? 0 : peer.score - elapsed * decay;
	}

	/* Past the time a bucket needs to fill, the product is never taken */
	private static Bucket refilled(Map<Kind, Bucket> buckets, Kind kind, Rate rate, long now) {
		final long ceiling = rate.capacity() * TOKEN;
		final Bucket bucket = buckets.computeIfAbsent(kind, key -> new Bucket(ceiling, now));
		final long elapsed = now - bucket.stamp;

		bucket.millitokens = elapsed > ceiling / rate.refillPerSecond() ? ceiling
			: Math.min(ceiling, bucket.millitokens + elapsed * rate.refillPerSecond());
		bucket.stamp = now;
		return bucket;
	}

	private static long saturatedSum(long reading, long span) {
		return reading > Long.MAX_VALUE - span ? Long.MAX_VALUE : reading + span;
	}
}
