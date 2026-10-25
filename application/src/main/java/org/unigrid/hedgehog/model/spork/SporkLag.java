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

package org.unigrid.hedgehog.model.spork;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BinaryOperator;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.crypto.CheckMeter;
import org.unigrid.hedgehog.model.crypto.ChecksSpentException;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

/**
* Remembers, per spork type, the newest time stamp the network keys have been seen to vouch for, whether this
* node accepted that spork or not. A stored spork older than that is out of date, and a node that knows it is
* should wait for the newer one rather than act on a policy the network has already moved past.
*
* <p>A spork is remembered only when it is signed by two different current network keys
* ({@link GridSpork#isDoublySigned()}). A proposal, which one key has signed and is waiting in
* {@link PendingSporks} for the second, changes nothing: the rules in force stay in force until the spork is
* co-signed, so a single key cannot hold the ledger back. The checks that compare a spork with the stored one
* (its log extends the stored log, it is newer) are left out on purpose: a doubly signed spork that fails them
* is a rival version of the history, and the node should wait for that to settle instead of acting on the
* stored spork. Nothing else is trusted, so a peer without a network key cannot make a node wait. Anyone can
* send a frame and a check costs a signature verification, so the check is made only for a spork newer than the
* stored one (an older one cannot make the node behind), and each check is paid for by the sending peer from its
* bucket for the type, in the pool of the peers it is among ({@link VerifySlots}). Inbound peers that flood forged
* sporks spend the quarter of the checks that inbound peers share, and a peer that this node dialed still has a
* version that moved on checked, so the node learns from it that it is behind; the sporks that were turned away are
* sent again.
*
* <p>What is remembered is bounded: a time stamp more than {@link #FUTURE_ALLOWANCE} ahead of this node's
* clock is recorded as that far ahead. A spork stays remembered until the stored spork catches up. The memory is
* not persisted: a restarted node learns it again from the sporks its peers send on every new stream and every
* few minutes.
*
* <p>The network threads write and any thread reads, so each map entry changes in one atomic update and no
* lock is held while a signature is verified.
*/
@ApplicationScoped
public class SporkLag {
	public static final Duration FUTURE_ALLOWANCE = Duration.ofMinutes(10);
	public static final Duration VERIFY_GAP = Duration.ofSeconds(1);

	/* The source of what is handed to this node over REST. No peer is named so: a peer is its address or its stream. */
	public static final String LOCAL = "local";

	private static final BinaryOperator<Instant> LATER = BinaryOperator.maxBy(Comparator.naturalOrder());

	/**
	* The peers that pay for the checks of what they send from a share of their own: those that this node dialed
	* itself, which no one else chooses, and every other peer, which anyone can open streams as from as many addresses
	* as they like. A peer is among those that this node dialed by the connection that it opened, never by what the
	* peer says of itself.
	*/
	public enum Pool {
		TRUSTED, OTHER
	}

	private final InstantSource clock;
	private final Map<Type, Instant> agreed = new ConcurrentHashMap<>();
	private final VerifySlots slots;

	public SporkLag() {
		this(InstantSource.system(), System::nanoTime);
	}

	/* A clock that a test moves on, which the seconds of the checks follow too */
	SporkLag(InstantSource clock) {
		this(clock, () -> TimeUnit.MILLISECONDS.toNanos(clock.millis()));
	}

	private SporkLag(InstantSource clock, LongSupplier nanos) {
		this.clock = clock;
		this.slots = new VerifySlots(nanos);
	}

	/**
	* Notes what a spork that came in tells of the network, as an inbound peer sends it. Verifying is the costly part
	* and anyone can send a frame, so a spork is checked only if it could make the node behind, which a spork no newer
	* than the stored one cannot, and if it would move the record.
	*/
	public void record(GridSpork spork, SporkDatabase db, String source) {
		record(spork, db, turnOf(spork.getType(), source));
	}

	/**
	* Notes what a spork that came in tells of the network, as {@link #record(GridSpork, SporkDatabase, String)} does,
	* with the turn its source was given for it, which pays for its checks.
	*/
	public void record(GridSpork spork, SporkDatabase db, Turn turn) {
		if (couldMakeBehind(spork, db)) {
			final Instant stamp = cappedAhead(spork.getTimeStamp());

			if (isLaterThan(agreed.get(spork.getType()), stamp) && turn.getAsBoolean()
				&& turn.charging(spork::isDoublySigned)) {

				agreed.merge(spork.getType(), stamp, LATER);
			}
		}
	}

	/* The turn of an inbound peer, or of what is handed to this node over REST */
	public Turn turnOf(Type type, String source) {
		return turnOf(type, source, Pool.OTHER);
	}

	public Turn turnOf(Type type, String source, Pool pool) {
		return new Turn(type, source, pool);
	}

	/**
	* The account of the source of one spork that came in, which pays for each check of its signatures as it is made,
	* from the bucket of the source for the type in its pool (VerifySlots). A spork whose source has nothing left is
	* not checked, one that fails a cheaper test first costs nothing, and a chain of board keys that costs more than
	* the source holds is checked as far as it pays, what verified of it being remembered for the next time it comes.
	* What a spork was charged is given back when it proves genuine, to the second it was charged in only.
	*/
	@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
	public final class Turn implements BooleanSupplier {
		private final Type type;
		@Getter private final String source;
		private final Pool pool;
		private int charged;
		private long second;
		private int chargedThatSecond;

		/* Whether the source can pay for a check, which is asked right before the first one. What is handed to this
		   node over REST is not limited: it comes from a holder of the REST token, not from anyone who can send a
		   frame. */
		@Override
		public boolean getAsBoolean() {
			return isLocal() || slots.hasLeft(type, pool, source);
		}

		/* The work, each of its checks paid for by the source; work that the source cannot pay for in full answers
		   false, and the spork is checked again when it comes again */
		public boolean charging(BooleanSupplier work) {
			try {
				return CheckMeter.charging(this::pay, work::getAsBoolean);
			} catch (ChecksSpentException ex) {
				return false;
			}
		}

		private boolean pay() {
			if (isLocal()) {
				return true;
			}

			final OptionalLong paidIn = slots.take(type, pool, source);

			paidIn.ifPresent(this::paidIn);
			return paidIn.isPresent();
		}

		private void paidIn(long check) {
			if (charged == 0 || check != second) {
				second = check;
				chargedThatSecond = 0;
			}

			charged++;
			chargedThatSecond++;
		}

		/* A spork that passed its checks was genuine, so its source gets back what it paid for the next one */
		public void giveBack() {
			if (charged > 0) {
				slots.giveBack(type, pool, source, charged, second, chargedThatSecond);
				charged = 0;
				chargedThatSecond = 0;
			}
		}

		private boolean isLocal() {
			return LOCAL.equals(source);
		}
	}

	/* What the bucket of an inbound source holds for the type */
	double left(Type type, String source) {
		return left(type, Pool.OTHER, source);
	}

	double left(Type type, Pool pool, String source) {
		return slots.left(type, pool, source);
	}

	private static boolean couldMakeBehind(GridSpork spork, SporkDatabase db) {
		return !spork.isPending() && isDatedAndKnown(spork) && spork.isNewerThan(db.get(spork.getType()))
			&& followsOnTheStoredChain(spork, db);
	}

	/* A chain of board keys that does not hold the stored one is no version the node could take, and judging that
	   costs no signature */
	private static boolean followsOnTheStoredChain(GridSpork spork, SporkDatabase db) {
		return !(spork instanceof BoardKeys boardKeys) || boardKeys.followsOn(db.get(Type.BOARD_KEYS));
	}

	/**
	* Whether a spork of the type, vouched for by the network keys, is newer than the stored one. A spork that
	* is equal to or older than the stored one is no lag. A type that is unset in the database counts as older
	* than any spork seen for it: the network has set it and this node has not got it yet, so it is behind.
	*/
	public boolean isBehind(Type type, SporkDatabase db) {
		return Optional.ofNullable(agreed.get(type))
			.map(seen -> isLaterThanStored(seen, db.get(type))).orElse(false);
	}

	private static boolean isDatedAndKnown(GridSpork spork) {
		return Objects.nonNull(spork.getTimeStamp()) && spork.getType() != Type.UNDEFINED;
	}

	private static boolean isLaterThan(Instant reference, Instant stamp) {
		return Objects.isNull(reference) || stamp.isAfter(reference);
	}

	private static boolean isLaterThanStored(Instant seen, GridSpork stored) {
		return Objects.isNull(stored) || isLaterThan(stored.getTimeStamp(), seen);
	}

	private Instant cappedAhead(Instant stamp) {
		final Instant ceiling = clock.instant().plus(FUTURE_ALLOWANCE);

		return stamp.isAfter(ceiling) ? ceiling : stamp;
	}
}
