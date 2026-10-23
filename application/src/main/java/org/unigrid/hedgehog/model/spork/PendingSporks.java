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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

/**
* Holds sporks signed by one network key while they wait for a second key to co-sign them. They live in
* memory only, one per spork type, and every node drops them once their time stamp is further than
* {@link #LIFETIME} away, so an abandoned proposal disappears from the whole network at the same time.
*/
@ApplicationScoped
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class PendingSporks {
	public static final Duration LIFETIME = Duration.ofMinutes(60);

	/* The newest proposal of a type wins, so one stamped far ahead would hold off every honest proposal until its
	   time came; a proposal is taken no further ahead than clocks that are set right drift apart */
	public static final Duration LEEWAY = Duration.ofMinutes(2);

	private InstantSource clock = InstantSource.system();
	private final Map<Type, GridSpork> proposals = new EnumMap<>(Type.class);

	public static String digestOf(GridSpork spork) {
		return DigestUtils.sha512Hex(spork.getSignable());
	}

	/**
	* Holds the proposal when it may replace the stored spork once co-signed and is newer than the one held
	* for its type. Returns whether it was taken, so only proposals new to this node travel further.
	*/
	public boolean offer(GridSpork spork, GridSpork stored) {
		return offer(spork, stored, () -> true);
	}

	/**
	* Holds the proposal as {@link #offer(GridSpork, GridSpork)} does, asking {@code mayVerify} right before its
	* signature is checked, as {@link GridSpork#isProposalOver(GridSpork, BooleanSupplier)} does. The signature is
	* checked without the lock, so that a spork that costs many checks holds up no other thread, and whether the
	* proposal may take the place of the one held is asked again under the lock.
	*/
	public boolean offer(GridSpork spork, GridSpork stored, BooleanSupplier mayVerify) {
		if (!isAlive(spork) || isAheadOfTime(spork) || !mayTakeThePlaceOfTheHeld(spork)
			|| !spork.isProposalOver(stored, mayVerify)) {
			return false;
		}

		synchronized (this) {
			if (mayTakeThePlaceOfTheHeld(spork)) {
				proposals.put(spork.getType(), spork);
				return true;
			}
		}

		return false;
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
		final GridSpork held = heldOf(stored.getType());

		if (Objects.nonNull(held) && !held.isProposalOver(stored)) {
			drop(held);
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
