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
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

	private InstantSource clock = InstantSource.system();
	private final Map<Type, GridSpork> proposals = new EnumMap<>(Type.class);

	public static String digestOf(GridSpork spork) {
		return DigestUtils.sha512Hex(spork.getSignable());
	}

	/**
	* Holds the proposal when it may replace the stored spork once co-signed and is newer than the one held
	* for its type. Returns whether it was taken, so only proposals new to this node travel further.
	*/
	public synchronized boolean offer(GridSpork spork, GridSpork stored) {
		final GridSpork held = proposals.get(spork.getType());

		if (isAlive(spork) && spork.isNewerThan(held) && spork.canBeProposedOver(stored)) {
			proposals.put(spork.getType(), spork);
			return true;
		}

		return false;
	}

	public synchronized List<GridSpork> list() {
		proposals.values().removeIf(spork -> !isAlive(spork));
		return new ArrayList<>(proposals.values());
	}

	public synchronized Optional<GridSpork> find(String digest) {
		return list().stream().filter(spork -> digestOf(spork).equals(digest)).findFirst();
	}

	public synchronized void remove(Type type) {
		proposals.remove(type);
	}

	public synchronized void retainProposalsOver(GridSpork stored) {
		final GridSpork held = proposals.get(stored.getType());

		if (Objects.nonNull(held) && !held.canBeProposedOver(stored)) {
			proposals.remove(stored.getType());
		}
	}

	private boolean isAlive(GridSpork spork) {
		return Duration.between(spork.getTimeStamp(), clock.instant()).abs().compareTo(LIFETIME) <= 0;
	}
}
