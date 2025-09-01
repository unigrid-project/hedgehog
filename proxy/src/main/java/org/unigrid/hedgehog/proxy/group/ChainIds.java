/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.proxy.group;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import org.unigrid.hedgehog.proxy.chain.ChainId;

/* SHA-256 over the 5 ASCII bytes "chain", the epoch as u64, the group as u32 and the index as u32, all big-endian */
public final class ChainIds {
	private static final byte[] LABEL = "chain".getBytes(StandardCharsets.US_ASCII);

	private ChainIds() {
		/* Static helpers only */
	}

	public static ChainId of(Epoch epoch, int group, int index) {
		if (group < 0 || index < 0) {
			throw new IllegalArgumentException("Groups and chains count from 0, found group " + group
				+ " and index " + index);
		}

		final byte[] position = ByteBuffer.allocate(2 * Integer.BYTES).putInt(group).putInt(index).array();

		return new ChainId(Sha256.digest(LABEL, epoch.bytes(), position));
	}

	public static List<ChainId> ofGroup(Epoch epoch, int group, int chainsPerGroup) {
		return IntStream.range(0, chainsPerGroup).mapToObj(index -> of(epoch, group, index)).toList();
	}
}
