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

package org.unigrid.hedgehog.model.spork;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;

@Data @ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class VestingStorage extends GridSpork implements Serializable {
	public VestingStorage() {
		setType(Type.VESTING_STORAGE);

		final VestingStorage.SporkData data = new VestingStorage.SporkData();
		data.setVestingAddresses(new HashMap<>());
		setData(data);
	}

	@Data
	public static class SporkData implements ChunkData {
		private Map<Address, Vesting> vestingAddresses;

		@Data @Builder @AllArgsConstructor @NoArgsConstructor
		public static class Vesting implements Serializable {
			private BigDecimal amount;

			private BigInteger block;

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
	}
}
