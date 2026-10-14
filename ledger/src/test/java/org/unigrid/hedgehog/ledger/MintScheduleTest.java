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

import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class MintScheduleTest {
	private static final MintSchedule SCHEDULE = new MintSchedule(3_172_666, 1_700_000_000_000L);

	@Example
	public void shouldMintOnlyAboveTheSnapshotTip() {
		assertThat(SCHEDULE.mintsOnLedger(3_172_666), is(false));
		assertThat(SCHEDULE.mintsOnLedger(1), is(false));
		assertThat(SCHEDULE.mintsOnLedger(3_172_667), is(true));
	}

	@Example
	public void shouldDueTheFirstHeightAboveTheTipOneIntervalAfterTheTip() {
		assertThat(SCHEDULE.dueTime(3_172_667), equalTo(1_700_000_060_000L));
		assertThat(SCHEDULE.dueTime(3_172_676), equalTo(1_700_000_000_000L + 10 * 60_000L));
	}

	@Example
	public void shouldRefuseAHeightThatDoesNotMintOnTheLedger() {
		assertThrows(IllegalArgumentException.class, () -> SCHEDULE.dueTime(3_172_666));
		assertThrows(IllegalArgumentException.class, () -> SCHEDULE.dueTime(0));
	}

	@Example
	public void shouldFailLoudlyInsteadOfWrapping() {
		assertThrows(ArithmeticException.class, () -> SCHEDULE.dueTime(Long.MAX_VALUE));
		assertThrows(ArithmeticException.class,
			() -> new MintSchedule(0, Long.MAX_VALUE - 1).dueTime(1));
	}

	@Example
	public void shouldRefuseANegativeTipOrTime() {
		assertThrows(IllegalArgumentException.class, () -> new MintSchedule(-1, 0));
		assertThrows(IllegalArgumentException.class, () -> new MintSchedule(0, -1));
	}
}
