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

package org.unigrid.hedgehog.proxy;

import java.util.Arrays;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.SizeClasses;

/* A chain takes only entries of its size classes, so every payload a test posts is padded to one */
public final class Padding {
	private Padding() {
		/* Static helpers only */
	}

	/* The payload followed by zeros up to the payload of the smallest class of the chain that holds it */
	public static byte[] padded(byte[] payload, int maxEntryBytes) {
		final int entryBytes = SizeClasses.fit(Entry.OVERHEAD + payload.length, maxEntryBytes)
			.orElseThrow(() -> new IllegalArgumentException("A payload of " + payload.length
				+ " bytes fits no entry of at most " + maxEntryBytes + " bytes"));

		return Arrays.copyOf(payload, entryBytes - Entry.OVERHEAD);
	}
}
