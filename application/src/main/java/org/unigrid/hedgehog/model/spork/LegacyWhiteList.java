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
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.commons.lang3.Validate;

/**
 * The legacy addresses the board approved for migration. An address counts as approved only once two board keys signed the
 * list. Every node of this release gets it, so any node can answer whether an address may migrate.
 */
@Data @ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class LegacyWhiteList extends GridSpork implements Serializable {
	private static final long serialVersionUID = 6602184467316251907L;

	/** Starts empty and governed, like the other board sporks. */
	public LegacyWhiteList() {
		setType(Type.LEGACY_WHITELIST);
		setFlags((short) (getFlags() | Flag.GOVERNED.getValue()));
		setData(new SporkData());
	}

	/**
	 * Each address as its hash160 in lower-case hex. The field name is unique among sporks, because Jackson tells spork
	 * data apart by field names.
	 */
	@Data
	public static class SporkData implements ChunkData {
		private static final long serialVersionUID = -1835746519360022853L;

		/** Room for the 3,296 funded snapshot addresses with margin; the wire's two-byte count would allow more. */
		public static final int MAX_ENTRIES = 4096;

		/** A hash160 is 20 bytes, so 40 hex characters. */
		public static final int HASH_BYTES = 20;

		/* Two hex characters to a byte */
		private static final int HEX_LENGTH = 2 * HASH_BYTES;
		private static final Pattern HASH = Pattern.compile("[0-9a-f]{" + HEX_LENGTH + "}");

		/* Hash160 as lower-case hex gives one spelling per address, so equal lists sign the same bytes */
		private Set<String> approvedLegacyAddresses = new TreeSet<>();

		/** What a node holds before the board signs any list. */
		@Override
		public SporkData empty() {
			return new SporkData();
		}

		/**
		 * Checks the form only. Whether an address is funded is checked by the board's command, since most nodes
		 * hold no snapshot.
		 */
		public void validate() {
			Validate.isTrue(Objects.nonNull(approvedLegacyAddresses), "approvedLegacyAddresses is required");
			Validate.isTrue(approvedLegacyAddresses.size() <= MAX_ENTRIES,
				"approvedLegacyAddresses holds at most %d entries", MAX_ENTRIES);

			Validate.isTrue(approvedLegacyAddresses.stream().allMatch(SporkData::isHash),
				"Every entry of approvedLegacyAddresses has to be %d lower-case hex characters", HEX_LENGTH);
		}

		/** One spelling per address, so equal lists always sign the same bytes. */
		private static boolean isHash(String address) {
			return Objects.nonNull(address) && HASH.matcher(address).matches();
		}
	}
}
