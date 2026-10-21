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

@Data @ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class LedgerBlacklist extends GridSpork implements Serializable {
	private static final long serialVersionUID = -4195271863554421057L;

	public LedgerBlacklist() {
		setType(Type.LEDGER_BLACKLIST);
		setFlags((short) (getFlags() | Flag.GOVERNED.getValue()));
		setData(new SporkData());
	}

	@Data
	public static class SporkData implements ChunkData {
		private static final long serialVersionUID = 7238109642551376833L;

		/* Far below what the two-byte count on the wire allows, so every frame and every node's list stay small */
		public static final int MAX_ENTRIES = 4096;
		public static final int HASH_BYTES = 20;

		private static final Pattern HASH = Pattern.compile("[0-9a-f]{" + 2 * HASH_BYTES + "}");

		/* Hash160 as lower-case hex gives one spelling per address, so equal lists sign the same bytes */
		private Set<String> legacyAddresses = new TreeSet<>();

		@Override
		public SporkData empty() {
			return new SporkData();
		}

		public void validate() {
			Validate.isTrue(Objects.nonNull(legacyAddresses), "legacyAddresses is required");
			Validate.isTrue(legacyAddresses.size() <= MAX_ENTRIES, "legacyAddresses holds at most %d entries",
				MAX_ENTRIES);

			Validate.isTrue(legacyAddresses.stream().allMatch(SporkData::isHash),
				"Every entry of legacyAddresses has to be %d lower-case hex characters", 2 * HASH_BYTES);
		}

		private static boolean isHash(String address) {
			return Objects.nonNull(address) && HASH.matcher(address).matches();
		}
	}
}
