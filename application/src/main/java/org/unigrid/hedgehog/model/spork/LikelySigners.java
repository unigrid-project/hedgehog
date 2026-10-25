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

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

/**
* The keys that signed the heads of a type that verified last, the latest first: those of the stored version, which
* were checked when it was taken, and the one of the proposal held, which was checked when it came. The next version
* of a type is most likely signed by them, so a signature is tried against those keys first, and an honest version
* costs a check for each of its signatures. Only a signature that verifies names a key here, so what anyone else sends
* changes nothing of the order.
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class LikelySigners {
	private static final int REMEMBERED = BoardKeyChain.MAX_KEYS;
	private static final Map<Type, List<String>> LATEST = new EnumMap<>(Type.class);

	/* The keys given, those that signed a head of the type last coming first */
	static synchronized List<String> ordered(Type type, Collection<String> keys) {
		final List<String> latest = LATEST.getOrDefault(type, List.of());

		return Stream.concat(latest.stream().filter(keys::contains),
			keys.stream().filter(key -> !latest.contains(key))).toList();
	}

	static synchronized void signed(Type type, String key) {
		final List<String> latest = new ArrayList<>(LATEST.getOrDefault(type, List.of()));

		latest.remove(key);
		latest.addFirst(key);
		LATEST.put(type, List.copyOf(latest.subList(0, Math.min(latest.size(), REMEMBERED))));
	}

	/* A process that starts over has checked no head, as each test of a process has not */
	static synchronized void forget() {
		LATEST.clear();
	}
}
