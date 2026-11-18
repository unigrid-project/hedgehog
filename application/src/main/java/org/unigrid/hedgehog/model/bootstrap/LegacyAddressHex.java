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

package org.unigrid.hedgehog.model.bootstrap;

import java.util.HexFormat;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * A legacy address as the sporks spell it: its hash160 in lower-case hex, one spelling per address, so equal lists
 * always sign the same bytes. Kept beside LegacyAddress rather than in the sporks, as the bootstrap package already
 * reads the sporks and the sporks must not read it back.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LegacyAddressHex {
	private static final HexFormat HEX = HexFormat.of();

	/**
	 * The hash of an address in hex. Anything that is no legacy address throws an IllegalArgumentException saying
	 * why.
	 */
	public static String hashOf(String address) {
		return HEX.formatHex(LegacyAddress.decode(address));
	}

	/** The address a hash in hex stands for, to show it the way people know it. */
	public static String addressOf(String hash) {
		return LegacyAddress.encode(HEX.parseHex(hash));
	}
}
