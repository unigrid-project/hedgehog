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
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/* What one peer may ask of this node. A peer has a bucket of tokens for each kind of message and a score that
   its misbehaviour raises and time lowers. One that runs dry too often, or reaches the score limit, is ignored
   for a while: the caller does no work for it. A bucket for each kind is also shared by all peers, so that
   changing address does not multiply the work. This protects the node and authenticates no one: a forwarder on
   the loopback address, which hands every connection to this node under an address of its own, gives each of them an
   id, buckets and a score of their own, and so multiplies what a client behind it may send.

   Peers are tracked in two maps. The active ones are in the order of their last activity, and a newcomer takes
   the room of the least recently active, so a flood of new peers costs a step each and never locks anyone out.
   An active peer that is dropped starts again with no score, but a penalised peer is not in that map: no flood
   of newcomers can free it. The penalised ones are in the order of their penalty, which is also the order it
   ends in, so what has ended is dropped from the head. Both maps are limited, and nothing is scanned.

   At the cap of penalised peers, `maxPeers / 2`, a new penalty frees the oldest one before its time. That is the
   price of a bounded map: to free one penalised address an attacker needs that many penalised ones at once.

   A peer that brought something new that a validator signed is vouched for, for a minute after the last such item,
   and may draw on a reserve of each kind once what all peers share of it is gone, which nobody else can empty: a
   flood from peers that bring nothing new cannot starve the traffic of the validators. A copy never draws on it, and
   a vouched peer takes of it no more than it earned by what it brought new. The vouched peers are a third map, in the
   order of their last vouch, which is the order the vouches end in, and as many as the penalised ones at most.

   Time is what the limiter has seen the clock add. A reading at or before the last one adds nothing, so a jump
   back creates no tokens, lowers no score and does not make a penalty longer; a jump forward is counted in full,
   up to the end of the range of a long. Tokens are counted in thousandths and the score in sixty-thousandths of
   a point, so that refills and decay by milliseconds are exact in whole numbers. Not thread-safe: the serial
   executor of the driver is the only caller. */
public final class PeerLimiter {
	private static final long TOKEN = 1000;
	private static final long POINT = 60_000;
	private static final long MILLIPOINT_PER_POINT = 1000;

	/* The one bucket that junk shares is kept under this kind, for it is the same for all */
	private static final Kind JUNK = Kind.CONSENSUS;

	/* A validator sends a heartbeat every ten seconds and ballots whenever the chain runs, so a peer that relays what
	   the validators sign is vouched for again well within this, and one that stops loses the reserve soon after */
	private static final long VOUCH_MILLIS = 60_000;

	/* Each new item that a peer brought earns it two tokens of the reserve, so an honest relay, whose items that are
	   not copies are new, earns more than it takes, while a peer that was first with a few items it overheard takes
	   no more than twice as many of anything else. A few heights of a busy network may be saved up, and no more. */
	private static final long CREDIT_PER_ITEM = 2;
	private static final long MAX_CREDIT = 100;

	public enum Kind {
		TRANSACTION, HEARTBEAT, CONSENSUS, STATUS, BLOCK_REQUEST
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
	   penalised ones at most half of that. A refusal because a peer used up its own bucket costs it
	   `refusalCostMillipoints` thousandths of a point of score; a policy made without it costs a whole point. */
	public record Policy(Map<Kind, Rate> rates, Map<Kind, Rate> global, int scoreLimit, int scoreDecayPerMinute,
		long penaltyMillis, int maxPeers, int refusalCostMillipoints) {
		private static final int POINT_IN_MILLIPOINTS = 1000;
		private static final int REFUSAL_OF_A_NETWORK = 250;
		private static final int MARGIN = 2;
		private static final int BURST_SECONDS = 4;
		private static final int HEARTBEAT_SECONDS = 10;
		private static final int BLOCKS_PER_SECOND = 5;
		private static final int COPIES = 9;
		private static final int JUNK_PERCENT = 5;
		private static final int RESERVE_DIVISOR = 4;

		public static final Policy DEFAULT = new Policy(
			Map.of(Kind.TRANSACTION, new Rate(200, 50), Kind.HEARTBEAT, new Rate(20, 2),
				Kind.CONSENSUS, new Rate(2000, 500), Kind.STATUS, new Rate(10, 1),
				Kind.BLOCK_REQUEST, new Rate(40, 10)),
			Map.of(Kind.TRANSACTION, new Rate(2000, 500), Kind.HEARTBEAT, new Rate(500, 20),
				Kind.CONSENSUS, new Rate(20_000, 5000), Kind.STATUS, new Rate(200, 20),
				Kind.BLOCK_REQUEST, new Rate(200, 100)),
			100, 10, 60_000, 10_000);

		@SuppressWarnings("checkstyle:ParameterNumber")
		public Policy(Map<Kind, Rate> rates, Map<Kind, Rate> global, int scoreLimit, int scoreDecayPerMinute,
			long penaltyMillis, int maxPeers) {
			this(rates, global, scoreLimit, scoreDecayPerMinute, penaltyMillis, maxPeers, POINT_IN_MILLIPOINTS);
		}

		public Policy {
			if (!covers(rates) || !covers(global)) {
				throw new IllegalArgumentException("A policy has a rate for each kind, per peer and shared");
			}

			if (!sensible(scoreLimit, scoreDecayPerMinute, penaltyMillis, maxPeers)
				|| refusalCostMillipoints < 1) {
				throw new IllegalArgumentException("A policy has a score limit, a penalty and a refusal"
					+ " cost above 0, no negative decay, and room for two peers");
			}

			rates = Collections.unmodifiableMap(new EnumMap<>(rates));
			global = Collections.unmodifiableMap(new EnumMap<>(global));
		}

		/* The limits of a network of `validators` validators that pass on `transactionsPerSecond` transactions a
		   second. An honest neighbour relays each new item once, so what it sends is the rate of the items, and each
		   rate here is twice that, so that items which arrive together do not make two honest nodes refuse and ban
		   each other. None is below the one of the defaults, and a burst holds four seconds of it.
		   - A heartbeat: each validator sends one in ten seconds, so a neighbour relays `validators` of them in
		     that time. The refill is twice their rate and the burst twice their number, held by no floor.
		   - A transaction: the refill is twice the rate of the network.
		   - A message of the engine: each height brings a proposal, a prevote and a precommit of every validator
		     and a decision, at no more than five heights a second, and the refill is twice that.
		   - A status is a peer's own and is not relayed, so what a peer sends does not grow with the validators
		     and its limit is the default, as it is for a block request.
		   The limits shared by all peers are for what the node hears of an item from every peer that relays it. A
		   node relays to at most eight others, so an item reaches it in nine copies at most, and the shared rate
		   is nine times the rate of the items, twice. A copy of a message of the engine that the node holds takes
		   nothing of it (see allowCopy), which leaves that rate to spare. A status is heard from each of the other
		   validators. */
		public static Policy forNetwork(int validators, int transactionsPerSecond) {
			if (validators < 1 || transactionsPerSecond < 1) {
				throw new IllegalArgumentException("A network has a validator and a transaction a second,"
					+ " found " + validators + " and " + transactionsPerSecond);
			}

			final long heartbeats = (long) MARGIN * validators / HEARTBEAT_SECONDS;
			final long messages = (long) (2 * validators + 2) * BLOCKS_PER_SECOND;
			final long transactions = transactionsPerSecond;
			final Map<Kind, Rate> own = new EnumMap<>(DEFAULT.rates());
			final Map<Kind, Rate> shared = new EnumMap<>(DEFAULT.global());
			final long heard = (long) MARGIN * COPIES;

			own.put(Kind.HEARTBEAT, new Rate(MARGIN * validators,
				(int) Math.max(own.get(Kind.HEARTBEAT).refillPerSecond(), heartbeats)));
			own.put(Kind.TRANSACTION, burstable(own.get(Kind.TRANSACTION), MARGIN * transactions));
			own.put(Kind.CONSENSUS, burstable(own.get(Kind.CONSENSUS), MARGIN * messages));
			shared.put(Kind.HEARTBEAT, burstable(shared.get(Kind.HEARTBEAT), COPIES * heartbeats));
			shared.put(Kind.TRANSACTION, burstable(shared.get(Kind.TRANSACTION), heard * transactions));
			shared.put(Kind.CONSENSUS, burstable(shared.get(Kind.CONSENSUS), heard * messages));
			shared.put(Kind.STATUS, burstable(shared.get(Kind.STATUS), (long) MARGIN * (validators - 1)));

			return new Policy(own, shared, DEFAULT.scoreLimit(), DEFAULT.scoreDecayPerMinute(),
				DEFAULT.penaltyMillis(), DEFAULT.maxPeers(), REFUSAL_OF_A_NETWORK);
		}

		/* What all the items that are junk may take together, whatever their kind: a twentieth of what is shared
		   for the messages of the engine, which is where the junk that costs the most comes from */
		public Rate junk() {
			final Rate engine = global.get(Kind.CONSENSUS);

			return new Rate(Math.max(1, engine.capacity() * JUNK_PERCENT / 100),
				Math.max(1, engine.refillPerSecond() * JUNK_PERCENT / 100));
		}

		/* What the peers that are vouched for may still take of a kind once all that the peers share of it is gone:
		   a quarter of that more. The shared rate is made for nine copies of every item, twice over, and the new
		   items of the engine alone take an eighteenth of it, so a quarter is several times what they need. */
		public Rate reserve(Kind kind) {
			final Rate everyone = global.get(kind);

			return new Rate(Math.max(1, everyone.capacity() / RESERVE_DIVISOR),
				Math.max(1, everyone.refillPerSecond() / RESERVE_DIVISOR));
		}

		/* The refill asked for, or the one of the floor if that is more, and room for four seconds of it */
		private static Rate burstable(Rate floor, long refill) {
			final int rate = Math.toIntExact(Math.max(floor.refillPerSecond(), refill));

			return new Rate(Math.max(floor.capacity(), Math.multiplyExact(BURST_SECONDS, rate)), rate);
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

	/* Until when a peer is vouched for, and how many tokens of the reserve it may still take */
	private record Vouch(long until, long credit) {
	}

	private final LongSupplier clock;
	private final Policy policy;
	private final Map<String, Peer> active = new LinkedHashMap<>();
	private final Map<String, Long> penalised;
	private final Map<Kind, Bucket> shared = new EnumMap<>(Kind.class);
	private final Map<Kind, Bucket> junk = new EnumMap<>(Kind.class);
	private final Map<Kind, Bucket> reserve = new EnumMap<>(Kind.class);
	private final Map<String, Vouch> vouched = new LinkedHashMap<>();
	private long epoch;
	private long lastReading;
	private long time;

	public PeerLimiter(LongSupplier clock, Policy policy) {
		this(clock, policy, new LinkedHashMap<>());
	}

	/* The map of the penalties is given, in the order of their end, so that a test can see how it is walked */
	PeerLimiter(LongSupplier clock, Policy policy, Map<String, Long> penalised) {
		this.clock = clock;
		this.policy = policy;
		this.penalised = penalised;
		this.lastReading = clock.getAsLong();
	}

	/* A refusal because the peer has used up its own bucket costs it score and takes nothing from the shared one.
	   A refusal because the shared bucket is empty costs it neither: it took no more than it may. */
	public boolean allow(String id, Kind kind) {
		return allow(id, kind, true);
	}

	/* What the door could not take for plausible takes its token from the one bucket that all of it shares, a small
	   one, and not from the bucket of its kind: it cannot empty what the plausible items of that kind need. It costs
	   the sender a token of its own bucket whether that small bucket has room or not, so that a sender of junk
	   that outruns its own rate is refused for it and scored, and the peers that send junk at a rate of their own
	   are only refused. */
	public boolean allow(String id, Kind kind, boolean plausible) {
		final long now = advance();

		return ownToken(id, kind, now).map(own -> take(id, kind, plausible, own, now)).orElse(false);
	}

	private boolean take(String id, Kind kind, boolean plausible, Bucket own, long now) {
		if (spend(own, everyone(kind, plausible, now), plausible)) {
			return true;
		}

		return plausible && fromReserve(id, kind, own, now);
	}

	private boolean fromReserve(String id, Kind kind, Bucket own, long now) {
		expire(vouched, Vouch::until, now);

		final Vouch vouch = vouched.get(id);
		final boolean earned = vouch != null && vouch.credit() > 0;

		if (!earned || !spend(own, refilled(reserve, kind, policy.reserve(kind), now), true)) {
			return false;
		}

		vouched.put(id, new Vouch(vouch.until(), vouch.credit() - 1));
		return true;
	}

	private Bucket everyone(Kind kind, boolean plausible, long now) {
		return plausible ? refilled(shared, kind, policy.global().get(kind), now)
			: refilled(junk, JUNK, policy.junk(), now);
	}

	/* A copy of an item that the node holds already costs its sender a token of its own bucket, and a refusal for it
	   is scored as any other, but it takes nothing that all peers share: the node does no work for it, and the copies
	   that every neighbour relays of one item must not empty what the new items need */
	public boolean allowCopy(String id, Kind kind) {
		return ownToken(id, kind, advance()).map(own -> {
			own.millitokens -= TOKEN;
			return true;
		}).orElse(false);
	}

	/* The bucket of a peer that is not penalised, when it holds a token; running dry costs the peer score */
	private Optional<Bucket> ownToken(String id, Kind kind, long now) {
		if (penalised(id, now)) {
			return Optional.empty();
		}

		final Peer peer = track(id, now);
		final Bucket own = refilled(peer.buckets, kind, policy.rates().get(kind), now);

		if (own.millitokens < TOKEN) {
			raise(id, peer, policy.refusalCostMillipoints() * (POINT / MILLIPOINT_PER_POINT), now);
			return Optional.empty();
		}

		return Optional.of(own);
	}

	private static boolean spend(Bucket own, Bucket everyone, boolean plausible) {
		final boolean room = everyone.millitokens >= TOKEN;

		if (room || !plausible) {
			own.millitokens -= TOKEN;
		}

		if (room) {
			everyone.millitokens -= TOKEN;
		}

		return room;
	}

	/* The peer brought something new that a validator signed, which earns it tokens of the reserve. A peer that is
	   penalised is not vouched for, and while as many peers are vouched for as may be, a new one is not: a newcomer
	   must not push out the peers that keep bringing what is new. */
	public void vouch(String id) {
		final long now = advance();

		if (penalised(id, now)) {
			return;
		}

		expire(vouched, Vouch::until, now);

		final Vouch before = vouched.remove(id);

		if (before != null || vouched.size() < policy.maxPeers() / 2) {
			final long credit = Math.min(MAX_CREDIT, (before == null ? 0 : before.credit()) + CREDIT_PER_ITEM);

			vouched.put(id, new Vouch(saturatedSum(now, VOUCH_MILLIS), credit));
		}
	}

	public void misbehaved(String id, int weight) {
		if (weight < 1) {
			throw new IllegalArgumentException("A weight is at least 1, found " + weight);
		}

		final long now = advance();

		if (!penalised(id, now)) {
			raise(id, track(id, now), weight * POINT, now);
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

	/* The ids that are penalised now, at most half of the peers that are kept, as a copy that the caller may keep */
	public Set<String> penalisedIds() {
		expirePenalties(advance());
		return Set.copyOf(penalised.keySet());
	}

	/* A number that changes whenever a peer is penalised or a penalty ends, so that a caller that keeps the ids can
	   tell when to ask for them again */
	public long penaltyEpoch() {
		expirePenalties(advance());
		return epoch;
	}

	int tracked() {
		return active.size() + penalised.size();
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
		expirePenalties(now);
		return penalised.containsKey(id);
	}

	private void expirePenalties(long now) {
		epoch += expire(penalised, Long::longValue, now);
	}

	/* Penalties last the same time, and so do vouches, and time never runs back, so the first one is the first to
	   end. How many ended. */
	private static <T> int expire(Map<String, T> endings, ToLongFunction<T> end, long now) {
		final Iterator<T> ends = endings.values().iterator();
		int ended = 0;

		while (ends.hasNext()) {
			if (end.applyAsLong(ends.next()) > now) {
				return ended;
			}

			ends.remove();
			ended++;
		}

		return ended;
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

		eldest.next();
		eldest.remove();
	}

	private void raise(String id, Peer peer, long units, long now) {
		final long total = decayed(peer, now) + units;

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
		vouched.remove(id);

		if (penalised.size() >= policy.maxPeers() / 2) {
			dropEldest(penalised);
		}

		penalised.put(id, saturatedSum(now, policy.penaltyMillis()));
		epoch++;
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
