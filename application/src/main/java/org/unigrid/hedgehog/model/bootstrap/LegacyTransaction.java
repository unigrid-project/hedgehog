/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.bootstrap;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value @Builder
public class LegacyTransaction {
	private byte[] id;
	private List<TransactionInput> inputs;
	private List<TransactionOutput> outputs;

	public boolean isCoinBase() {
		return !inputs.isEmpty() && inputs.get(0).getType() == InputType.COINBASE;
	}

	public boolean isCoinStake() {
		return outputs.size() >= 2 && outputs.get(0).getType() == OutputType.EMPTY && !isCoinBase();
	}
}
