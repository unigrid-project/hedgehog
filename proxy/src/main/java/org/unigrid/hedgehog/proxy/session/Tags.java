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

package org.unigrid.hedgehog.proxy.session;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.unigrid.hedgehog.proxy.chain.Tag;

/* The tag of sealed message n in one direction of a session: the first 16 bytes of HMAC-SHA256 over
   direction(1) || n(8), keyed with the tag key of that direction. Both ends know the next tag before it arrives,
   and no two messages of a session share one. */
public final class Tags {
	private Tags() {
		/* Static helpers only */
	}

	public static Tag of(byte[] tagKey, Direction direction, long counter) {
		final HMac mac = new HMac(new SHA256Digest());
		final byte[] input = ByteBuffer.allocate(1 + Long.BYTES).put(direction.code()).putLong(counter).array();
		final byte[] output = new byte[mac.getMacSize()];

		mac.init(new KeyParameter(tagKey));
		mac.update(input, 0, input.length);
		mac.doFinal(output, 0);
		return new Tag(Arrays.copyOf(output, Tag.SIZE));
	}
}
