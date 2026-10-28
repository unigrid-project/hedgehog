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

package org.unigrid.hedgehog.proxy.chain;

import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;

/* The sizes a sealed entry is padded to: 1 KiB, 4 KiB and the largest entry of the chain, each only where it
   does not exceed that largest entry. An opening, an answer and a short message all fill the smallest class,
   so the size of an entry does not tell them apart. */
public final class SizeClasses {
	private static final int SMALL = 1024;
	private static final int MEDIUM = 4096;

	private SizeClasses() {
		/* Static helpers only */
	}

	public static List<Integer> of(int maxEntryBytes) {
		return IntStream.of(SMALL, MEDIUM, maxEntryBytes).filter(size -> size <= maxEntryBytes).sorted().distinct()
			.boxed().toList();
	}

	/* The smallest class that holds an entry of the given size, none when it exceeds the largest entry */
	public static OptionalInt fit(int entryBytes, int maxEntryBytes) {
		return of(maxEntryBytes).stream().mapToInt(Integer::intValue).filter(size -> size >= entryBytes).findFirst();
	}

	/* The first of the classes, found without listing them, as admission asks for it with every entry */
	public static int smallest(int maxEntryBytes) {
		return Math.min(SMALL, maxEntryBytes);
	}

	public static boolean isClass(int entryBytes, int maxEntryBytes) {
		return of(maxEntryBytes).contains(entryBytes);
	}
}
