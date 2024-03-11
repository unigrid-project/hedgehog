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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;

@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class ValidatorSpork extends GridSpork implements Serializable {

	public ValidatorSpork() {
		setType(Type.VALIDATOR_SPORK);
		setFlags((short) (getFlags() | Flag.GOVERNED.getValue()));

		final ValidatorSpork.SporkData data = new ValidatorSpork.SporkData();
		data.setValidatorKeys(new ArrayList<>());
		setData(data);

	}

	@Data
	public static class SporkData implements ChunkData {

		private List<String> validatorKeys;

		@Override
		public SporkData empty() {
			final SporkData data = new SporkData();
			data.setValidatorKeys(new ArrayList<>());
			return data;
		}
	}
}