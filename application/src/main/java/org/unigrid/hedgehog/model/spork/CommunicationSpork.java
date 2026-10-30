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

import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.commons.lang3.Validate;

@Data @ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class CommunicationSpork extends GridSpork implements Serializable {
	private static final long serialVersionUID = 7312680458195526371L;

	public CommunicationSpork() {
		setType(Type.COMMUNICATION);
		setFlags((short) (getFlags() | Flag.GOVERNED.getValue()));
		setData(new CommunicationSpork.SporkData());
	}

	@Data
	public static class SporkData implements ChunkData {
		/* The spork database file holds the serialised class, so its version must not change when members are
		   added */
		private static final long serialVersionUID = 1L;

		/* Bounded by their wire fields, so the spork a signer validated is the one its peers decode */
		private static final int MAX_UNSIGNED_SHORT = 0xFFFF;
		private static final long MAX_UNSIGNED_INT = 0xFFFF_FFFFL;

		private static final long MIN_REORGANIZE_PERIOD_SECONDS = 60;
		private static final long MIN_ENTRY_BYTES = 1024;
		private static final long MAX_ENTRY_BYTES = 1024 * 1024;

		/* An exit keeps room for what it relays, at least one of the largest entries, beside its own entries, so a
		   chain of fewer than four of them can leave it no room for one of its own and stall its sessions */
		private static final long MIN_LARGEST_ENTRIES = 4;

		/* The reserve of a chain, a sixteenth of it, holds two of the smallest entries from here on, so the half of
		   it that relayed entries may take still holds one */
		private static final long MIN_CHAIN_CAPACITY_BYTES = 32 * 1024;

		private int groupSize = 8;
		private long reorganizePeriodSeconds = 3600;
		private int chainsPerGroup = 4;
		private int clientsPerChain = 16;
		private long chainCapacityBytes = 32L * 1024 * 1024;
		private int retentionSeconds = 30;
		private long maxEntryBytes = 16L * 1024;
		private int sessionIdleSeconds = 300;
		private int communicationFlags = CommunicationFlag.of(CommunicationFlag.CONNECT, CommunicationFlag.UDP);

		@Override
		public SporkData empty() {
			return new SporkData();
		}

		public boolean allows(CommunicationFlag flag) {
			return (communicationFlags & flag.getValue()) != 0;
		}

		/* Callers relay the message, so it states the refused value and nothing else */
		private static void requireBetween(long minimum, long maximum, long value, String requirement) {
			Validate.inclusiveBetween(minimum, maximum, value, requirement + ", not " + value);
		}

		private static void requireShortCount(int value, String name) {
			requireBetween(1, MAX_UNSIGNED_SHORT, value, name + " must be 1-65535");
		}

		public void validate() {
			requireShortCount(groupSize, "groupSize");
			requireShortCount(chainsPerGroup, "chainsPerGroup");
			requireShortCount(clientsPerChain, "clientsPerChain");
			requireShortCount(retentionSeconds, "retentionSeconds");
			requireShortCount(sessionIdleSeconds, "sessionIdleSeconds");

			requireBetween(MIN_REORGANIZE_PERIOD_SECONDS, MAX_UNSIGNED_INT, reorganizePeriodSeconds,
				"reorganizePeriodSeconds must be 60-4294967295");
			requireBetween(MIN_ENTRY_BYTES, MAX_ENTRY_BYTES, maxEntryBytes,
				"maxEntryBytes must be 1024-1048576");
			requireBetween(MIN_LARGEST_ENTRIES * maxEntryBytes, MAX_UNSIGNED_INT, chainCapacityBytes,
				"chainCapacityBytes must hold four of the largest entries and be at most 4294967295");
			requireBetween(MIN_CHAIN_CAPACITY_BYTES, MAX_UNSIGNED_INT, chainCapacityBytes,
				"chainCapacityBytes must be at least 32768");
			Validate.isTrue(CommunicationFlag.areKnown(communicationFlags),
				"communicationFlags holds unknown flags: 0x" + Integer.toHexString(communicationFlags));
		}
	}
}
