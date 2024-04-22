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

package org.unigrid.hedgehog.model.storage;

import java.nio.ByteBuffer;
import lombok.Value;

@Value
public class Manifest {
	public static final int ENCODED_SIZE = 2 + Long.BYTES + 2 * Integer.BYTES + 4 * Short.BYTES;

	private final StorageFormat format;
	private final long fileSize;
	private final int manifestCopies;
	private final LayoutParameters layout;

	public byte[] encode() {
		return ByteBuffer.allocate(ENCODED_SIZE).put(format.getId()).putLong(fileSize).put((byte) manifestCopies)
			.putInt(layout.getChunkSize()).putInt(layout.getFragmentSize())
			.putShort((short) layout.getOuterParityPercent()).putShort((short) layout.getMaxOuterDataChunks())
			.putShort((short) layout.getInnerParityPercent()).putShort((short) layout.getMaxParityPercent())
			.array();
	}

	public static Manifest decode(byte[] plaintext) {
		final ByteBuffer buffer = ByteBuffer.wrap(plaintext);
		final StorageFormat format = StorageFormat.of(buffer.get() & 0xFF);
		final long fileSize = buffer.getLong();
		final int manifestCopies = buffer.get() & 0xFF;
		final LayoutParameters layout = LayoutParameters.builder().chunkSize(buffer.getInt())
			.fragmentSize(buffer.getInt()).outerParityPercent(Short.toUnsignedInt(buffer.getShort()))
			.maxOuterDataChunks(Short.toUnsignedInt(buffer.getShort()))
			.innerParityPercent(Short.toUnsignedInt(buffer.getShort()))
			.maxParityPercent(Short.toUnsignedInt(buffer.getShort())).build();

		layout.validate();
		return new Manifest(format, fileSize, manifestCopies, layout);
	}
}
