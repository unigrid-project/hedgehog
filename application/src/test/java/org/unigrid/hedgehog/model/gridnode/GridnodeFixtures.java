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

package org.unigrid.hedgehog.model.gridnode;

import java.util.function.Consumer;
import org.unigrid.hedgehog.model.crypto.Signature;

public final class GridnodeFixtures {
	private GridnodeFixtures() { }

	public static Gridnode signed(Signature key, Gridnode.Status status, String host, long timestamp)
		throws Exception {

		final Gridnode gridnode = Gridnode.builder().id(key.getPublicKey()).status(status).hostName(host)
			.timestamp(timestamp).build();

		gridnode.setSignature(key.sign(GridnodeSignature.message(gridnode)));
		return gridnode;
	}

	public static Gridnode copyOf(Gridnode original, Consumer<Gridnode> change) {
		final Gridnode copy = Gridnode.builder().id(original.getId()).status(original.getStatus())
			.hostName(original.getHostName()).timestamp(original.getTimestamp())
			.signature(original.getSignature()).build();

		change.accept(copy);
		return copy;
	}
}
