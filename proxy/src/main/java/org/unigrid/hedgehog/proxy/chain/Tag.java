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

import java.security.SecureRandom;

/* Names the session of a sealed entry for the two ends only: random on an opening, derived from the session's
   tag keys after it, so to anyone else every tag looks random */
public final class Tag extends FixedBytes {
	public static final int SIZE = 16;

	public Tag(byte[] bytes) {
		super(bytes, SIZE);
	}

	public static Tag random(SecureRandom random) {
		final byte[] bytes = new byte[SIZE];

		random.nextBytes(bytes);
		return new Tag(bytes);
	}
}
