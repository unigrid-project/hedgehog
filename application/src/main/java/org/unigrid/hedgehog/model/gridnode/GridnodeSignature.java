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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.unigrid.hedgehog.model.crypto.Signature;

public final class GridnodeSignature {
	public static final Duration MAX_AGE = Duration.ofMinutes(30);
	public static final Duration MAX_SKEW = Duration.ofMinutes(10);

	private static final byte[] CONTEXT = "hh-gridnode-v1".getBytes(StandardCharsets.US_ASCII);

	private GridnodeSignature() { }

	public static byte[] message(Gridnode gridnode) {
		final byte[] id = gridnode.getId().getBytes(StandardCharsets.UTF_8);
		final byte[] host = gridnode.getHostName().getBytes(StandardCharsets.UTF_8);

		return ByteBuffer.allocate(CONTEXT.length + Short.BYTES + id.length + Byte.BYTES + Short.BYTES + host.length
			+ Long.BYTES)
			.put(CONTEXT).putShort((short) id.length).put(id).put(gridnode.getStatus().getValue())
			.putShort((short) host.length).put(host).putLong(gridnode.getTimestamp())
			.array();
	}

	public static boolean isExpired(Gridnode gridnode, long nowMillis) {
		return gridnode.getTimestamp() <= nowMillis - MAX_AGE.toMillis();
	}

	public static boolean isFresh(Gridnode gridnode, long nowMillis) {
		return !isExpired(gridnode, nowMillis) && gridnode.getTimestamp() <= nowMillis + MAX_SKEW.toMillis();
	}

	public static boolean verifies(Gridnode gridnode) {
		return Objects.nonNull(gridnode.getSignature())
			&& Signature.isSignedBy(gridnode.getId(), message(gridnode), gridnode.getSignature());
	}
}
