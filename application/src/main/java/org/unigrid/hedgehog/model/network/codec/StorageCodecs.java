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

package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.storage.GroupId;

public final class StorageCodecs {
	public static final int MAX_GROUPS_PER_PACKET = 4096;
	public static final int MAX_INDEX = 0xFF;
	private static final String TOO_MANY_GROUPS = "At most " + MAX_GROUPS_PER_PACKET + " groups fit in one packet";

	private StorageCodecs() {
		/* Static helpers only */
	}

	public static void writeBytes(final ByteBuf out, final byte[] bytes) {
		out.writeInt(bytes.length);
		out.writeBytes(bytes);
	}

	public static void writeBytes(final ByteBuf out, final byte[] bytes, final int size) {
		requireSize(bytes.length, size);
		writeBytes(out, bytes);
	}

	public static byte[] readBytes(final ByteBuf in) {
		final int length = in.readInt();

		if (length < 0 || length > Network.MAX_DATA_SIZE) {
			throw new IllegalArgumentException("Invalid byte array length " + length);
		}

		return copyOut(in, length);
	}

	public static byte[] readBytes(final ByteBuf in, final int size) {
		final int length = in.readInt();

		requireSize(length, size);
		return copyOut(in, length);
	}

	private static void requireSize(final int length, final int size) {
		if (length != size) {
			throw new IllegalArgumentException("Expected " + size + " bytes but found " + length);
		}
	}

	/* Slicing first makes a forged length fail on the bytes actually present, before anything is allocated */
	private static byte[] copyOut(final ByteBuf in, final int length) {
		return ByteBufUtil.getBytes(in.readSlice(length));
	}

	public static void writeIndex(final ByteBuf out, final int index) {
		if (index < 0 || index > MAX_INDEX) {
			throw new IllegalArgumentException("Fragment index " + index + " is outside 0.." + MAX_INDEX);
		}

		out.writeByte(index);
	}

	public static void writeGroupId(final ByteBuf out, final GroupId groupId) {
		out.writeBytes(groupId.bytes());
	}

	public static GroupId readGroupId(final ByteBuf in) {
		final byte[] id = new byte[GroupId.SIZE];
		in.readBytes(id);
		return GroupId.of(id);
	}

	public static void writeCount(final ByteBuf out, final int count) {
		if (count > MAX_GROUPS_PER_PACKET) {
			throw new IllegalArgumentException(TOO_MANY_GROUPS);
		}

		out.writeShort(count);
	}

	public static int readCount(final ByteBuf in) {
		final int count = in.readUnsignedShort();

		if (count > MAX_GROUPS_PER_PACKET) {
			throw new IllegalArgumentException(TOO_MANY_GROUPS);
		}

		return count;
	}

	public static void writeGroupIds(final ByteBuf out, final List<GroupId> groupIds) {
		writeCount(out, groupIds.size());
		groupIds.forEach(id -> writeGroupId(out, id));
	}

	public static List<GroupId> readGroupIds(final ByteBuf in) {
		return IntStream.range(0, readCount(in)).mapToObj(i -> readGroupId(in)).collect(Collectors.toList());
	}
}
