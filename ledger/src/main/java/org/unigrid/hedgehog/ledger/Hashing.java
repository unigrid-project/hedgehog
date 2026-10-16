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

package org.unigrid.hedgehog.ledger;

/* Puts peers in an order that depends on a seed, so that nobody can choose an id to be first without knowing it. The
   order is the same each time for one seed, and does not depend on the run: nothing here reads a hash code. */
final class Hashing {
	private static final int FIRST_SHIFT = 30;
	private static final int SECOND_SHIFT = 27;
	private static final int THIRD_SHIFT = 31;
	private static final long FIRST_FACTOR = 0xbf58476d1ce4e5b9L;
	private static final long SECOND_FACTOR = 0x94d049bb133111ebL;

	private Hashing() {
		/* Static helpers only */
	}

	/* A number for the id that depends on every character of it and on the seed */
	static long rank(long seed, String id) {
		long hash = seed;

		for (int n = 0; n < id.length(); n++) {
			hash = mixed(hash ^ id.charAt(n));
		}

		return hash;
	}

	/* The finishing step of splitmix64: a change in any bit of the input changes about half of the bits of the output */
	private static long mixed(long value) {
		final long first = (value ^ (value >>> FIRST_SHIFT)) * FIRST_FACTOR;
		final long second = (first ^ (first >>> SECOND_SHIFT)) * SECOND_FACTOR;

		return second ^ (second >>> THIRD_SHIFT);
	}
}
