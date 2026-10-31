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

package org.unigrid.hedgehog.service.storage;

import java.security.SecureRandom;
import java.util.Random;

/* Production code takes a SecureRandom, which cannot be seeded. This one replays from a seed, so a failing
   sample draws the same fingerprints and group ids again when jqwik reruns it with the reported seed. */
public class SeededSecureRandom extends SecureRandom {
	private final transient Random source;

	public SeededSecureRandom(final long seed) {
		this.source = new Random(seed);
	}

	/* Takes its seed from a random the test already owns, so the draws stay apart from that random's own stream */
	public static SeededSecureRandom from(final Random random) {
		return new SeededSecureRandom(random.nextLong());
	}

	@Override
	public void nextBytes(final byte[] bytes) {
		source.nextBytes(bytes);
	}
}
