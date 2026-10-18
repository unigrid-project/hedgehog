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

package org.unigrid.hedgehog.s3.index;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.CRC32C;

/* Lengths and counts are unsigned LEB128 numbers, strings are UTF-8 behind their length, and maps go out sorted
   so the same record always encodes to the same bytes */
final class Codec {
	private static final int SEVEN_BITS = 0x7F;
	private static final int MORE = 0x80;

	@FunctionalInterface
	interface Writer<T> {
		void write(DataOutput output, T value) throws IOException;
	}

	@FunctionalInterface
	interface Reader<T> {
		T read(DataInput input) throws IOException;
	}

	@FunctionalInterface
	interface Encoder {
		void encode(DataOutput output) throws IOException;
	}

	private Codec() {
		/* Static helpers only */
	}

	static byte[] encode(final Encoder encoder) {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			encoder.encode(new DataOutputStream(bytes));
		} catch (IOException exception) {
			throw new IllegalStateException("Writing to memory cannot fail", exception);
		}

		return bytes.toByteArray();
	}

	static <T> T decode(final byte[] bytes, final Reader<T> reader) throws IOException {
		return reader.read(new DataInputStream(new ByteArrayInputStream(bytes)));
	}

	static int checksum(final byte[] bytes, final int length) {
		final CRC32C checksum = new CRC32C();

		checksum.update(bytes, 0, length);
		return (int) checksum.getValue();
	}

	/* Appends the CRC32C of the bytes as a big-endian int */
	static byte[] seal(final byte[] bytes) {
		return ByteBuffer.allocate(bytes.length + Integer.BYTES).put(bytes).putInt(checksum(bytes, bytes.length))
			.array();
	}

	static byte[] unseal(final byte[] sealed) throws IOException {
		final int length = sealed.length - Integer.BYTES;

		if (length < 0 || ByteBuffer.wrap(sealed, length, Integer.BYTES).getInt() != checksum(sealed, length)) {
			throw new CorruptIndexException("Checksum mismatch in an index block");
		}

		return Arrays.copyOf(sealed, length);
	}

	static void writeNumber(final DataOutput output, final long value) throws IOException {
		long rest = value;

		while ((rest & ~SEVEN_BITS) != 0) {
			output.writeByte((int) (rest & SEVEN_BITS) | MORE);
			rest >>>= 7;
		}

		output.writeByte((int) rest);
	}

	/* Only ever written for sizes, counts and offsets, so a negative number means damage */
	static long readNumber(final DataInput input) throws IOException {
		long value = 0;

		for (int shift = 0; shift < Long.SIZE; shift += 7) {
			final byte current = input.readByte();

			value |= (long) (current & SEVEN_BITS) << shift;

			if (current >= 0) {
				return value < 0 ? fail("A variable-length number overflows") : value;
			}
		}

		return fail("A variable-length number runs past ten bytes");
	}

	static int readInt(final DataInput input) throws IOException {
		final long value = readNumber(input);
		return value > Integer.MAX_VALUE ? fail("Number out of range: " + value) : (int) value;
	}

	/* A count of the elements that follow, each at least the given number of bytes long, checked against the bytes
	   left so a damaged count fails here instead of sizing an allocation */
	static int readCount(final DataInput input, final int minimumBytes) throws IOException {
		final long count = readNumber(input);
		final long remaining = input instanceof InputStream stream ? stream.available() : Integer.MAX_VALUE;

		return count > remaining / minimumBytes ? fail("A count of " + count + " overruns the input") : (int) count;
	}

	private static <T> T fail(final String message) throws CorruptIndexException {
		throw new CorruptIndexException(message);
	}

	static void writeBytes(final DataOutput output, final byte[] bytes) throws IOException {
		writeNumber(output, bytes.length);
		output.write(bytes);
	}

	static byte[] readBytes(final DataInput input) throws IOException {
		final byte[] bytes = new byte[readCount(input, 1)];

		input.readFully(bytes);
		return bytes;
	}

	static void writeString(final DataOutput output, final String value) throws IOException {
		writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
	}

	static String readString(final DataInput input) throws IOException {
		return new String(readBytes(input), StandardCharsets.UTF_8);
	}

	static void writeInstant(final DataOutput output, final Instant value) throws IOException {
		output.writeLong(value.getEpochSecond());
		output.writeInt(value.getNano());
	}

	static Instant readInstant(final DataInput input) throws IOException {
		final long seconds = input.readLong();
		final int nanos = input.readInt();

		try {
			return Instant.ofEpochSecond(seconds, nanos);
		} catch (DateTimeException | ArithmeticException exception) {
			throw new CorruptIndexException("An instant out of range", exception);
		}
	}

	static <E extends Enum<E>> E readEnum(final DataInput input, final Class<E> type) throws IOException {
		final String name = readString(input);

		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException exception) {
			throw new CorruptIndexException("Unknown " + type.getSimpleName() + ": " + name, exception);
		}
	}

	static <T> void writeNullable(final DataOutput output, final T value, final Writer<T> writer) throws IOException {
		output.writeBoolean(Objects.nonNull(value));

		if (Objects.nonNull(value)) {
			writer.write(output, value);
		}
	}

	static <T> T readNullable(final DataInput input, final Reader<T> reader) throws IOException {
		return input.readBoolean() ? reader.read(input) : null;
	}

	static <T> void writeList(final DataOutput output, final List<T> values, final Writer<T> writer)
		throws IOException {

		writeNumber(output, values.size());

		for (T value : values) {
			writer.write(output, value);
		}
	}

	static <T> List<T> readList(final DataInput input, final Reader<T> reader) throws IOException {
		final int count = readCount(input, 1);
		final List<T> values = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			values.add(reader.read(input));
		}

		return values;
	}

	static void writeMap(final DataOutput output, final Map<String, String> values) throws IOException {
		writeNumber(output, values.size());

		for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
			writeString(output, entry.getKey());
			writeString(output, entry.getValue());
		}
	}

	static Map<String, String> readMap(final DataInput input) throws IOException {
		final int count = readCount(input, 2);
		final Map<String, String> values = new HashMap<>();

		for (int i = 0; i < count; i++) {
			values.put(readString(input), readString(input));
		}

		return values;
	}
}
