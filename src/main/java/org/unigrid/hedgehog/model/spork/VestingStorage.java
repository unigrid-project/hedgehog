/*
    Unigrid Hedgehog 
    Copyright © 2021-2022 The Unigrid Foundation

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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.unigrid.hedgehog.model.Address;

@Data @ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class VestingStorage extends GridSpork<VestingStorage.SporkData, VestingStorage.SporkData> {
	public VestingStorage() {
		setType(Type.VESTING_STORAGE);
	}

	@Data
	public static class SporkData implements IData {
		private Map<Address, Vesting> vestingAddresses;

		@Data @AllArgsConstructor @NoArgsConstructor
		public static class Vesting {
			private Instant start;
			private Duration duration;
			private int parts;
		}
	}
}
