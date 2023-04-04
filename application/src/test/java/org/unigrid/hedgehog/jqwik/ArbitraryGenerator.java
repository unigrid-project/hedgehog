/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.jqwik;

import net.jqwik.api.Arbitraries;

public class ArbitraryGenerator {
	public static String ip() {
		final int ip[] = new int[3];

		for (int i = 0; i < ip.length; i++) {
			ip[i] = Arbitraries.integers().between(1, 255).sample();
		}

		return String.format("127.%d.%d.%d", ip[0], ip[1], ip[2]);
	}

	public static String version(int parts) {
		assert parts > 0 : "Parts has to be at least one";

		String vs = "";

		for (int i = 0; i < parts; i++) {
			vs = vs.concat(i == 0 ? "" : ".");
			vs = vs.concat(Arbitraries.integers().between(0, 999).sample().toString());
		}

		return vs;
	}

	public static String version() {
		return version(3);
	}
}
