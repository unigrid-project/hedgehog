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

package org.unigrid.hedgehog.proxy.group;

import java.util.concurrent.TimeUnit;
import org.unigrid.hedgehog.proxy.chain.ChainLimits;

/* The communication spork as the proxy reads it. Names, Java types and bounds follow the spork's data, whose
   wire widths cap every value: u16 fields are ints and u32 fields are longs. */
public record ProxySettings(int groupSize, long reorganizePeriodSeconds, int chainsPerGroup, int clientsPerChain,
	long chainCapacityBytes, int retentionSeconds, long maxEntryBytes, int sessionIdleSeconds, int flags) {

	public static final int CONNECT = 0x01;
	public static final int UDP = 0x02;
	public static final ProxySettings DEFAULTS = new ProxySettings(8, 3600, 4, 16, 32L * 1024 * 1024, 30, 16 * 1024,
		300, CONNECT | UDP);

	private static final int KNOWN_FLAGS = CONNECT | UDP;
	private static final int MAX_UNSIGNED_SHORT = 0xFFFF;
	private static final long MAX_UNSIGNED_INT = 0xFFFF_FFFFL;
	private static final long MIN_ENTRY_BYTES = 1024;
	private static final long MAX_ENTRY_BYTES = 1024 * 1024;

	public ProxySettings {
		requireBetween("groupSize", groupSize, 1, MAX_UNSIGNED_SHORT);
		requireBetween("reorganizePeriodSeconds", reorganizePeriodSeconds, Epoch.MIN_PERIOD_SECONDS,
			Epoch.MAX_PERIOD_SECONDS);
		requireBetween("chainsPerGroup", chainsPerGroup, 1, MAX_UNSIGNED_SHORT);
		requireBetween("clientsPerChain", clientsPerChain, 1, MAX_UNSIGNED_SHORT);
		requireBetween("maxEntryBytes", maxEntryBytes, MIN_ENTRY_BYTES, MAX_ENTRY_BYTES);
		requireBetween("chainCapacityBytes", chainCapacityBytes, maxEntryBytes, MAX_UNSIGNED_INT);
		requireBetween("retentionSeconds", retentionSeconds, 1, MAX_UNSIGNED_SHORT);
		requireBetween("sessionIdleSeconds", sessionIdleSeconds, 1, MAX_UNSIGNED_SHORT);

		if ((flags & ~KNOWN_FLAGS) != 0) {
			throw new IllegalArgumentException("flags has unknown bits set: " + Integer.toHexString(flags));
		}
	}

	public boolean connectAllowed() {
		return (flags & CONNECT) != 0;
	}

	public boolean udpAllowed() {
		return (flags & UDP) != 0;
	}

	public ChainLimits chainLimits() {
		return new ChainLimits(chainCapacityBytes, retentionSeconds, Math.toIntExact(maxEntryBytes));
	}

	public Epoch epochAt(long epochMillis) {
		return Epoch.at(epochMillis, reorganizePeriodSeconds);
	}

	public long sessionIdleMillis() {
		return TimeUnit.SECONDS.toMillis(sessionIdleSeconds);
	}

	private static void requireBetween(String name, long value, long min, long max) {
		if (value < min || value > max) {
			throw new IllegalArgumentException(name + " must be " + min + " to " + max + ", found " + value);
		}
	}
}
