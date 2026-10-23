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
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
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
* stored one (an older one cannot make the node behind) and by {@link VerifySlots}: one check a second for each
* type and sending peer ({@link #VERIFY_GAP}), and no more than {@link VerifySlots#CEILING} a second for a type
* whoever sends. A flood of forged sporks from one peer takes only the slots of that peer, and the sporks that
* were turned away are sent again.
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
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class SporkLag {
	public static final Duration FUTURE_ALLOWANCE = Duration.ofMinutes(10);
	public static final Duration VERIFY_GAP = Duration.ofSeconds(1);

	/* The source of what is handed to this node over REST. No peer is named so: a peer is its address or its stream. */
	public static final String LOCAL = "local";

	private static final BinaryOperator<Instant> LATER = BinaryOperator.maxBy(Comparator.naturalOrder());

	private InstantSource clock = InstantSource.system();
	private final Map<Type, Instant> agreed = new ConcurrentHashMap<>();
	private final VerifySlots slots = new VerifySlots(() -> clock.instant());

	/**
	* Notes what a spork that came in tells of the network. Verifying is the costly part and anyone can send a frame,
	* so a spork is checked only if it could make the node behind, which a spork no newer than the stored one cannot,
	* and if it would move the record. The source is the peer that sent it: it may have one spork of a type checked
	* a second, so a peer that floods forged sporks holds up no other peer, and one that is turned away for coming
	* too soon is not recorded: a later one will be, as every peer sends the sporks again and again.
	*/
	public void record(GridSpork spork, SporkDatabase db, String source) {
		record(spork, db, turnOf(spork.getType(), source));
	}

	/**
	* Notes what a spork that came in tells of the network, as {@link #record(GridSpork, SporkDatabase, String)} does,
	* with the turn its source was given for it.
	*/
	public void record(GridSpork spork, SporkDatabase db, Turn turn) {
		if (!spork.isPending() && couldMakeBehind(spork, db)) {
			final Instant stamp = cappedAhead(spork.getTimeStamp());

			if (isLaterThan(agreed.get(spork.getType()), stamp) && turn.getAsBoolean()
				&& spork.isDoublySigned()) {

				agreed.merge(spork.getType(), stamp, LATER);
			}
		}
	}

	public Turn turnOf(Type type, String source) {
		return new Turn(type, source, () -> 1);
	}

	/* A spork of the board keys costs a check for each approval of the changes of its chain that are not remembered,
	   and the head one more, as any other spork does */
	public Turn turnOf(GridSpork spork, String source) {
		return new Turn(spork.getType(), source, () -> spork instanceof BoardKeys boardKeys
			? 1 + boardKeys.uncheckedApprovals() : 1);
	}

	/**
	* The right of one spork that came in to have its signatures checked. It is taken from the slots of its source
	* when the first check is about to be made and is kept for every later check of the same spork, so a spork costs
	* its source one turn at most, charged by the checks it can cost, and a spork that fails a cheaper test first
	* costs none.
	*/
	@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
	public final class Turn implements BooleanSupplier {
		private final Type type;
		@Getter private final String source;
		private final IntSupplier checks;
		private Boolean given;

		/* What is handed to this node over REST is not limited: it comes from a holder of the REST token, not from
		   anyone who can send a frame */
		@Override
		public boolean getAsBoolean() {
			if (Objects.isNull(given)) {
				given = LOCAL.equals(source) || slots.take(type, source, checks.getAsInt());
			}

			return given;
		}

		/* A spork that passed its checks was genuine, so its source keeps its turn for the next one */
		public void giveBack() {
			if (Boolean.TRUE.equals(given) && !LOCAL.equals(source)) {
				slots.giveBack(type, source);
			}
		}
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
