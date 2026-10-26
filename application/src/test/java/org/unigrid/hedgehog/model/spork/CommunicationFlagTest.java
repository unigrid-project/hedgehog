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

import java.util.Set;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.model.spork.CommunicationFlag.CONNECT;
import static org.unigrid.hedgehog.model.spork.CommunicationFlag.UDP;

public class CommunicationFlagTest {
	@Example
	public void givesEveryFlagItsOwnBit() {
		assertThat(CommunicationFlag.of(), equalTo(0));
		assertThat(CommunicationFlag.of(CONNECT), equalTo(0x01));
		assertThat(CommunicationFlag.of(UDP), equalTo(0x02));
		assertThat(CommunicationFlag.of(CONNECT, UDP), equalTo(0x03));
	}

	@Example
	public void knowsEveryValueMadeOfItsFlags() {
		for (int bits : new int[] {0, 1, 2, 3}) {
			assertThat("bits " + bits, CommunicationFlag.areKnown(bits), is(true));
		}
	}

	@Example
	public void refusesEveryValueHoldingAnUnknownBit() {
		final int[] unknown = {-1, 4, 0x07, 0x8000, 0xFFFF, 0x10000, 0x10001, 0x10003,
			Integer.MIN_VALUE, Integer.MAX_VALUE};

		for (int bits : unknown) {
			assertThat("bits " + bits, CommunicationFlag.areKnown(bits), is(false));
		}
	}

	@Property
	public void knowsEveryCombinationOfItsFlags(@ForAll Set<CommunicationFlag> flags) {
		final int bits = CommunicationFlag.of(flags.toArray(CommunicationFlag[]::new));

		assertThat(CommunicationFlag.areKnown(bits), is(true));
	}

	@Property
	public void refusesEveryOtherBit(@ForAll @IntRange(min = 2, max = 31) int bit) {
		assertThat(CommunicationFlag.areKnown(1 << bit), is(false));
	}
}
