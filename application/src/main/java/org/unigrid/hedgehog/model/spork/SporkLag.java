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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BinaryOperator;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

/**
* Remembers, per spork type, the newest time stamp the network keys have been seen to vouch for, whether this
* node accepted that spork or not. A stored spork older than that is out of date, and a node that knows it is
* should wait for the newer one rather than act on a policy the network has already moved past.
*
* <p>A spork is remembered only when the signature check that acceptance relies on passes: signed by two
* different current network keys ({@link GridSpork#isDoublySigned()}), or, as a proposal, signed once by a
* current network key ({@link GridSpork#isValidSignature()}) while {@link PendingSporks} would still hold it.
* The checks that compare a spork with the stored one (its log extends the stored log, it is newer) are left
* out on purpose: a validly signed spork that fails them is a rival version of the history, and the node
* should wait for that to settle instead of acting on the stored spork. Nothing else is trusted, so a peer
* without a network key cannot make a node wait. Anyone can send a frame and a check costs a signature
* verification, so the check is made only for a spork newer than the stored one (an older one cannot make the node
* behind) and by {@link VerifySlots}: one check a second for each type and sending peer ({@link #VERIFY_GAP}), and
* no more than {@link VerifySlots#CEILING} a second for a type whoever sends. A flood of forged sporks from one
* peer takes only the slots of that peer, and the sporks that were turned away are sent again.
*
* <p>What is remembered is bounded: a time stamp more than {@link #FUTURE_ALLOWANCE} ahead of this node's
* clock is recorded as that far ahead, and a proposal only counts for as long as {@link PendingSporks#LIFETIME}
* lets the node hold it, so a replayed old proposal cannot hold a node back. A spork signed by two keys stays
* remembered until the stored spork catches up. The memory is not persisted: a restarted node learns it again
* from the sporks its peers send on every new stream and every few minutes.
*
* <p>The network threads write and any thread reads, so each map entry changes in one atomic update and no
* lock is held while a signature is verified.
*/
@ApplicationScoped
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class SporkLag {
	public static final Duration FUTURE_ALLOWANCE = Duration.ofMinutes(10);
	public static final Duration VERIFY_GAP = Duration.ofSeconds(1);

	/* The source of what this node proposes itself. No peer is named so: a peer is its address or its stream. */
	public static final String LOCAL = "local";

	private static final BinaryOperator<Instant> LATER = BinaryOperator.maxBy(Comparator.naturalOrder());

	private InstantSource clock = InstantSource.system();
	private final Map<Type, Instant> agreed = new ConcurrentHashMap<>();
	private final Map<Type, Instant> proposed = new ConcurrentHashMap<>();
	private final VerifySlots slots = new VerifySlots(() -> clock.instant());

	/**
	* Notes what a spork that came in tells of the network. Verifying is the costly part and anyone can send a frame,
	* so a spork is checked only if it could make the node behind, which a spork no newer than the stored one cannot,
	* and if it would move the record. The source is the peer that sent it: it may have one spork of a type checked
	* a second, so a peer that floods forged sporks holds up no other peer, and one that is turned away for coming
	* too soon is not recorded: a later one will be, as every peer sends the sporks again and again.
	*/
	public void record(GridSpork spork, SporkDatabase db, String source) {
		if (couldMakeBehind(spork, db)) {
			final Map<Type, Instant> seen = spork.isPending() ? proposed : agreed;
			final Instant stamp = cappedAhead(spork.getTimeStamp());

			if (isLaterThan(seen.get(spork.getType()), stamp) && mayVerify(spork.getType(), source)
				&& isVouchedFor(spork)) {

				seen.merge(spork.getType(), stamp, LATER);
			}
		}
	}

	/* What this node proposes itself is not limited: it is asked for by a holder of the REST token and a network key,
	   not by anyone who can send a frame, and a proposal that is turned away is not sent again by anyone */
	private boolean mayVerify(Type type, String source) {
		return LOCAL.equals(source) || slots.take(type, source);
	}

	private static boolean couldMakeBehind(GridSpork spork, SporkDatabase db) {
		return isDatedAndKnown(spork) && spork.isNewerThan(db.get(spork.getType()));
	}

	/**
	* Whether a spork of the type, vouched for by the network keys, is newer than the stored one. A spork that
	* is equal to or older than the stored one is no lag. A type that is unset in the database counts as older
	* than any spork seen for it: the network has set it and this node has not got it yet, so it is behind.
	*/
	public boolean isBehind(Type type, SporkDatabase db) {
		return newestSeen(type).map(seen -> isLaterThanStored(seen, db.get(type))).orElse(false);
	}

	private Optional<Instant> newestSeen(Type type) {
		final Optional<Instant> proposal = Optional.ofNullable(proposed.get(type)).filter(this::isHeld);

		return Stream.of(Optional.ofNullable(agreed.get(type)), proposal).flatMap(Optional::stream)
			.max(Comparator.naturalOrder());
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

	private boolean isVouchedFor(GridSpork spork) {
		if (spork.isPending()) {
			return isAlive(spork.getTimeStamp()) && spork.isValidSignature();
		}

		return spork.isDoublySigned();
	}

	private Instant cappedAhead(Instant stamp) {
		final Instant ceiling = clock.instant().plus(FUTURE_ALLOWANCE);

		return stamp.isAfter(ceiling) ? ceiling : stamp;
	}

	private boolean isAlive(Instant stamp) {
		return Duration.between(stamp, clock.instant()).abs().compareTo(PendingSporks.LIFETIME) <= 0;
	}

	private boolean isHeld(Instant stamp) {
		return !stamp.plus(PendingSporks.LIFETIME).isBefore(clock.instant());
	}
}
