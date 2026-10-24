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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.commons.codec.digest.DigestUtils;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

/**
* Holds sporks signed by one network key while they wait for a second key to co-sign them. They live in
* memory only, one per spork type, and every node drops them once their time stamp is further than
* {@link #LIFETIME} away, so an abandoned proposal disappears from the whole network at the same time.
*
* <p>Every proposal that one active key makes is genuine, and every node checks each one it is sent, so a node takes
* no more than {@link #MOST_PROPOSALS_A_MINUTE} proposals of a type from one key in a minute of its monotonic clock,
* and sends no more on: one key holds no node to checking proposals without end.
*/
@ApplicationScoped
public class PendingSporks {
	public static final Duration LIFETIME = Duration.ofMinutes(60);

	/* The newest proposal of a type wins, so one stamped far ahead would hold off every honest proposal until its
	   time came; a proposal is taken no further ahead than clocks that are set right drift apart */
	public static final Duration LEEWAY = Duration.ofMinutes(2);

	/* A board member who proposes, finds a mistake and proposes again does so a few times a minute at most */
	public static final int MOST_PROPOSALS_A_MINUTE = 12;

	private static final long NANOS_PER_MINUTE = Duration.ofMinutes(1).toNanos();

	private record Taken(long minute, int proposals) {
	}

	private final InstantSource clock;
	private final LongSupplier nanos;
	private final Map<Type, GridSpork> proposals = new EnumMap<>(Type.class);
	private final Map<Type, Map<String, Taken>> takenBySigner = new EnumMap<>(Type.class);

	public PendingSporks() {
		this(InstantSource.system(), System::nanoTime);
	}

	/* A clock that a test moves on, which the minutes of the proposals taken follow too */
	PendingSporks(InstantSource clock) {
		this(clock, () -> TimeUnit.MILLISECONDS.toNanos(clock.millis()));
	}

	private PendingSporks(InstantSource clock, LongSupplier nanos) {
		this.clock = clock;
		this.nanos = nanos;
	}

	public static String digestOf(GridSpork spork) {
		return DigestUtils.sha512Hex(spork.getSignable());
	}

	/**
	* Holds the proposal when it may replace the stored spork once co-signed and is newer than the one held
	* for its type. Returns whether it was taken, so only proposals new to this node travel further.
	*/
	public boolean offer(GridSpork spork, GridSpork stored) {
		return offer(spork, () -> stored, () -> true);
	}

	/**
	* Holds the proposal as {@link #offer(GridSpork, GridSpork)} does, asking {@code mayVerify} right before its
	* signature is checked, as {@link GridSpork#isProposalOver(GridSpork, BooleanSupplier)} does.
	*/
	public boolean offer(GridSpork spork, GridSpork stored, BooleanSupplier mayVerify) {
		return offer(spork, () -> stored, mayVerify);
	}

	/**
	* Holds the proposal as {@link #offer(GridSpork, GridSpork, BooleanSupplier)} does, over the spork that the node
	* stores, which {@code stored} reads. The signature is checked without the lock, so that a spork that costs many
	* checks holds up no other thread. Under the lock the proposal is held only if neither the keys that the node goes
	* by nor the stored spork changed meanwhile: either change is followed by a cleanup ({@link
	* #retainProposalsOfActiveKeys()}, {@link #retainProposalsOver(GridSpork)}) that judged the proposals held then,
	* and a proposal checked against what was there before must not come in after it. It is turned down as one that
	* failed its check, and comes again from the peers that hold it.
	*/
	public boolean offer(GridSpork spork, Supplier<GridSpork> stored, BooleanSupplier mayVerify) {
		return offer(spork, stored, mayVerify, true);
	}

	/**
	* Holds a proposal that is handed to this node over REST as {@link #offer(GridSpork, Supplier, BooleanSupplier)}
	* holds one from a peer, however many proposals of the type its key made in the minute: it comes from a holder of
	* the REST token, whose checks are its own, and the peers that it is sent on to hold it to the count.
	*/
	public boolean offerOfThisMachine(GridSpork spork, Supplier<GridSpork> stored) {
		return offer(spork, stored, () -> true, false);
	}

	private boolean offer(GridSpork spork, Supplier<GridSpork> stored, BooleanSupplier mayVerify, boolean isCounted) {
		final long generation = NetworkKey.getGeneration();
		final GridSpork over = stored.get();

		if (!mayBeHeld(spork, over, mayVerify)) {
			return false;
		}

		/* Named for nothing, as the signature was checked just now */
		final Optional<String> signer = spork.signerOfHead(spork.getSignature());

		synchronized (this) {
			if (isUnchanged(generation, over, stored) && mayTakeThePlaceOfTheHeld(spork)
				&& signer.filter(key -> !isCounted || mayTakeOneMoreOf(spork.getType(), key)).isPresent()) {

				proposals.put(spork.getType(), spork);
				tookOneMoreOf(spork.getType(), signer.get());
				return true;
			}
		}

		return false;
	}

	private boolean mayTakeOneMoreOf(Type type, String signer) {
		return takenOf(type, signer, minuteNow()).proposals() < MOST_PROPOSALS_A_MINUTE;
	}

	/* What was taken in a minute that has passed is forgotten, so what is held stays within the keys of the board */
	private void tookOneMoreOf(Type type, String signer) {
		final long minute = minuteNow();
		final Map<String, Taken> taken = takenBySigner.computeIfAbsent(type, key -> new HashMap<>());

		taken.put(signer, new Taken(minute, takenOf(type, signer, minute).proposals() + 1));
		taken.values().removeIf(earlier -> earlier.minute() != minute);
	}

	private Taken takenOf(Type type, String signer, long minute) {
		final Taken taken = takenBySigner.getOrDefault(type, Map.of()).get(signer);

		return Objects.isNull(taken) || taken.minute() != minute ? new Taken(minute, 0) : taken;
	}

	private long minuteNow() {
		return Math.floorDiv(nanos.getAsLong(), NANOS_PER_MINUTE);
	}

	private static boolean isUnchanged(long generation, GridSpork over, Supplier<GridSpork> stored) {
		return NetworkKey.getGeneration() == generation && stored.get() == over;
	}

	private boolean mayBeHeld(GridSpork spork, GridSpork stored, BooleanSupplier mayVerify) {
		return isAlive(spork) && !isAheadOfTime(spork) && mayTakeThePlaceOfTheHeld(spork)
			&& spork.isProposalOver(stored, mayVerify);
	}

	private synchronized boolean mayTakeThePlaceOfTheHeld(GridSpork spork) {
		final GridSpork held = proposals.get(spork.getType());

		return spork.isNewerThan(held) && !holdsLessThan(spork, held);
	}

	public synchronized List<GridSpork> list() {
		proposals.values().removeIf(spork -> !isAlive(spork));
		return new ArrayList<>(proposals.values());
	}

	public synchronized Optional<GridSpork> find(String digest) {
		return list().stream().filter(spork -> digestOf(spork).equals(digest)).findFirst();
	}

	public synchronized Optional<GridSpork> proposalOf(Type type) {
		return list().stream().filter(spork -> spork.getType() == type).findFirst();
	}

	public synchronized void remove(Type type) {
		proposals.remove(type);
	}

	/* Judged without the lock, as an offer is, and dropped only if it is still the proposal held */
	public void retainProposalsOver(GridSpork stored) {
		retainProposalsOver(stored.getType(), () -> stored);
	}

	/**
	* Drops the proposal of the type that is no proposal over the spork stored by now, which {@code stored} reads. A
	* node drops the proposals that a version outdates with no lock held that another thread waits for to store a
	* version of its own, so the proposal is judged by what is stored when the cleanup comes, and dropped only if that
	* is still stored and the proposal still held: one over a version that another thread stored meanwhile is a
	* proposal over the version stored.
	*/
	public void retainProposalsOver(Type type, Supplier<GridSpork> stored) {
		final GridSpork over = stored.get();
		final GridSpork held = heldOf(type);

		if (Objects.nonNull(held) && !held.isProposalOver(over)) {
			synchronized (this) {
				if (stored.get() == over) {
					proposals.remove(type, held);
				}
			}
		}
	}

	/* A proposal that a key signed which the board switched off can no longer be co-signed into a spork that counts */
	public void retainProposalsOfActiveKeys() {
		heldProposals().stream().filter(spork -> !spork.isValidSignature()).forEach(this::drop);
	}

	private synchronized GridSpork heldOf(Type type) {
		return proposals.get(type);
	}

	private synchronized List<GridSpork> heldProposals() {
		return new ArrayList<>(proposals.values());
	}

	private synchronized void drop(GridSpork held) {
		proposals.remove(held.getType(), held);
	}

	/* A change of the board keys waits for its co-signature, and one stolen key would push it out with a renewal of the
	   chain as it is, or with a chain that holds less, if the newer proposal won whatever it held */
	private static boolean holdsLessThan(GridSpork spork, GridSpork held) {
		return spork instanceof BoardKeys proposal && held instanceof BoardKeys waiting
			&& proposal.chainLength() < waiting.chainLength();
	}

	/* Of a proposal as of a co-signed version: the newest wins, so one stamped further ahead than clocks drift apart
	   would hold off every version the board makes until its time came */
	public boolean isAheadOfTime(GridSpork spork) {
		return isAheadOf(clock.instant(), spork);
	}

	public static boolean isAheadOf(Instant now, GridSpork spork) {
		return Objects.nonNull(spork.getTimeStamp()) && spork.getTimeStamp().isAfter(now.plus(LEEWAY));
	}

	private boolean isAlive(GridSpork spork) {
		return Duration.between(spork.getTimeStamp(), clock.instant()).abs().compareTo(LIFETIME) <= 0;
	}
}
