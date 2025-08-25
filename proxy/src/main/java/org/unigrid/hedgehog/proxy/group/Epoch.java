/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.proxy.group;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/* One reorganize period of the shard groups. Nodes that agree on the period and the time derive the same
   groups and chains for the same epoch without coordinating. */
public record Epoch(long number) {
	public static final long MIN_PERIOD_SECONDS = 60;
	public static final long MAX_PERIOD_SECONDS = 0xFFFF_FFFFL;

	public Epoch {
		if (number < 0) {
			throw new IllegalArgumentException("An epoch is never negative, found " + number);
		}
	}

	public static Epoch at(long epochMillis, long periodSeconds) {
		return new Epoch(Math.floorDiv(epochMillis, periodMillis(periodSeconds)));
	}

	public long startMillis(long periodSeconds) {
		return Math.multiplyExact(number, periodMillis(periodSeconds));
	}

	public Epoch next() {
		return new Epoch(number + 1);
	}

	public byte[] bytes() {
		return ByteBuffer.allocate(Long.BYTES).putLong(number).array();
	}

	private static long periodMillis(long periodSeconds) {
		if (periodSeconds < MIN_PERIOD_SECONDS || periodSeconds > MAX_PERIOD_SECONDS) {
			throw new IllegalArgumentException("A reorganize period is " + MIN_PERIOD_SECONDS + " to "
				+ MAX_PERIOD_SECONDS + " seconds, found " + periodSeconds);
		}

		return TimeUnit.SECONDS.toMillis(periodSeconds);
	}
}
