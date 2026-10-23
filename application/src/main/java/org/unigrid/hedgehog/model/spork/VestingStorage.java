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

import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.apache.commons.lang3.Validate;
import org.unigrid.hedgehog.model.Address;

@Data @ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class VestingStorage extends GridSpork implements Serializable {
	private static final long serialVersionUID = -799684885387679880L;

	public VestingStorage() {
		setType(Type.VESTING_STORAGE);

		final VestingStorage.SporkData data = new VestingStorage.SporkData();
		data.setVestingAddresses(new HashMap<>());
		setData(data);
	}

	@Data
	public static class SporkData implements ChunkData {
		private static final long serialVersionUID = 5831251856818111926L;

		/* Far above the addresses that vest on the network, and far below what the three-byte count on the wire
		   allows, so a peer cannot make a node hold millions of entries before a signature is checked */
		public static final int MAX_ENTRIES = 4096;

		private Map<Address, Vesting> vestingAddresses;

		@Data @Builder(toBuilder = true) @AllArgsConstructor @NoArgsConstructor
		public static class Vesting implements Serializable {
			private static final long serialVersionUID = 6642936482739543603L;

			private BigDecimal amount;

			private int block;

			private int cliff;

			@JsonFormat(shape = JsonFormat.Shape.STRING)
			private Duration duration;

			private int parts;

			private int percent;

			@JsonFormat(shape = JsonFormat.Shape.STRING)
			private Instant start;
		}

		public SporkData empty() {
			final SporkData data = new SporkData();

			data.setVestingAddresses(new HashMap<>());
			return data;
		}

		@Override
		public boolean amountsAreWithinBounds() {
			return Objects.isNull(vestingAddresses) || SporkAmount.areWithinBounds(vestingAddresses.values()
				.stream().filter(Objects::nonNull).map(Vesting::getAmount).toList());
		}

		public void validate() {
			Validate.isTrue(Objects.nonNull(vestingAddresses), "vestingAddresses is required");
			Validate.isTrue(vestingAddresses.size() <= MAX_ENTRIES, "vestingAddresses holds at most %d entries",
				MAX_ENTRIES);

			Validate.isTrue(vestingAddresses.keySet().stream().allMatch(Address::isWithinBounds),
				"Every address of vestingAddresses has to be at most %d bytes", Address.MAX_LENGTH);
		}
	}
}
