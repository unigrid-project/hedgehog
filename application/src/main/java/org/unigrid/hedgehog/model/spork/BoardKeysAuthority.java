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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.crypto.ActiveKeys;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

/**
* The board keys spork as a node takes it, from a peer or over REST. A version is verified, which is the costly part,
* before the lock is taken; under the lock the chain is judged again against what is stored by then, as another
* version may have come in meanwhile, and the version is stored, its chain installed when it is longer than the one
* the node goes by, and the proposals that keys it switched off signed are dropped. A stored spork that a key it
* switched off signed counts for nothing from then on ({@link GridSpork#isCounting()}).
*
* <p>Two chains that grow apart at the same change are a fork, which only a release with a new root or an import of
* the chain of the board on each node mends. A node keeps the chain it holds and says so once for each chain it is
* offered that two of its keys signed.
*/
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BoardKeysAuthority {
	private static final Object LOCK = new Object();
	private static final int MAX_FORKS_REPORTED = 64;
	private static final Set<String> FORKS = ConcurrentHashMap.newKeySet();
	private static final String FORKED = "The chain of board keys of the spork {} is a fork of the chain this node"
		+ " holds, which it keeps; a release with a new root, or an import of the chain of the board on each node,"
		+ " mends a fork";

	/* Whether the version was stored, and so should travel further. The source of the version is asked before the
	   first signature is checked, as GridSpork.canReplace(GridSpork, BooleanSupplier) does. */
	public static boolean accept(BoardKeys spork, SporkDatabase database, PendingSporks pendingSporks,
		BooleanSupplier mayVerify) {

		return mayAccept(spork, database, mayVerify) && commit(spork, database, pendingSporks);
	}

	/* Whether the version may take the place of the stored one, which is what checks cost and what the source of a
	   version that came from a peer pays for */
	public static boolean mayAccept(BoardKeys spork, SporkDatabase database, BooleanSupplier mayVerify) {
		if (!spork.canReplace(database.get(Type.BOARD_KEYS), mayVerify)) {
			reportFork(spork, database.get(Type.BOARD_KEYS), mayVerify);
			return false;
		}

		return true;
	}

	/**
	* Stores a version that {@link #mayAccept} took, goes by its chain and drops the proposals that it outdates, which is
	* work of the node that no source pays for, so that none whose checks run out can leave it half done. Under the
	* lock the version is judged again, by what costs no signature, against what is stored by then. Returns whether it
	* was stored.
	*/
	public static boolean commit(BoardKeys spork, SporkDatabase database, PendingSporks pendingSporks) {
		synchronized (LOCK) {
			if (!spork.followsOn(database.get(Type.BOARD_KEYS))) {
				return false;
			}

			database.set(spork);
			install(spork);
		}

		pendingSporks.retainProposalsOver(Type.BOARD_KEYS, () -> database.get(Type.BOARD_KEYS));
		pendingSporks.retainProposalsOfActiveKeys();
		return true;
	}

	/* Whether the chain of the spork is now the one the node goes by, which it holds as it is from then on */
	public static boolean install(BoardKeys spork) {
		final Optional<ActiveKeys> keys = spork.chainKeys().filter(NetworkKey::install);

		keys.ifPresent(installed -> BoardKeyChain.pin(spork.changes(), installed));
		return keys.isPresent();
	}

	/**
	* Goes by the chain that the release ships ({@link BoardKeyChain#shipped()}) before anything else, so that a node,
	* a command line or a util command that holds no chain goes by that one and never by the keys built into the
	* release alone. A release whose chain does not read, or does not hold from its own root, is broken, and nothing
	* starts from it.
	*/
	public static void installTheRelease() {
		final List<KeyChange> shipped = BoardKeyChain.shipped();

		if (!shipped.isEmpty()) {
			try {
				final ActiveKeys keys = BoardKeyChain.verify(shipped);

				BoardKeyChain.pinTheShipped(keys);
				NetworkKey.install(keys);
			} catch (IllegalArgumentException ex) {
				throw new IllegalStateException("The chain of board keys that this release ships does not"
					+ " hold from its root: " + ex.getMessage(), ex);
			}
		}
	}

	/* Checked last, on the turn of the source, so that only a chain that two keys signed is told of */
	private static void reportFork(BoardKeys spork, GridSpork stored, BooleanSupplier mayVerify) {
		if (isFork(spork, stored) && FORKS.size() < MAX_FORKS_REPORTED && mayVerify.getAsBoolean()
			&& spork.isDoublySigned()) {

			final String digest = PendingSporks.digestOf(spork);

			if (FORKS.add(digest)) {
				log.atWarn().addArgument(digest).log(FORKED);
			}
		}
	}

	private static boolean isFork(BoardKeys spork, GridSpork stored) {
		return stored instanceof BoardKeys held && !spork.holdsTheChainOf(held) && !held.holdsTheChainOf(spork);
	}
}
