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
import java.time.Instant;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;

/* The signer is null when no known network key signed the proposal */
public record PendingSporkInfo(GridSpork.Type type, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timeStamp,
	@JsonFormat(shape = JsonFormat.Shape.STRING) Instant expires, String signer, String digest, ChunkData data) {

	public static PendingSporkInfo of(GridSpork spork) {
		return new PendingSporkInfo(spork.getType(), spork.getTimeStamp(),
			spork.getTimeStamp().plus(PendingSporks.LIFETIME), NetworkKey.signerOf(spork).orElse(null),
			PendingSporks.digestOf(spork), spork.getData()
		);
	}
}
