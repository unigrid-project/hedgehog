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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.OptionalLong;
import org.unigrid.hedgehog.s3.spi.BlobId;

/* Index keys compare as unsigned bytes, so each encodes its fields in the order S3 lists them: a kind byte, then
   texts as UTF-8 with every NUL escaped as 00 FF and a 00 01 terminator, which keeps "a" before "a\0" before "ab",
   then numbers big-endian. Versions store their sequence inverted to list the newest first. A text without its
   terminator is a byte prefix of every key whose text starts with it, which is how prefix listings seek. */
final class Keys {
	static final byte BUCKET = 1;
	static final byte VERSION = 2;
	static final byte UPLOAD = 3;
	static final byte PART = 4;
	static final byte REFERENCE = 5;
	static final byte GARBAGE = 6;

	private static final int ESCAPE = 0xFF;
	private static final int TERMINATOR = 0x01;
	private static final int ID_LENGTH = 16;
	private static final HexFormat HEX = HexFormat.of();

	record Parsed(String bucket, String key, long sequence) {
	}

	private Keys() {
		/* Static helpers only */
	}

	static byte[] buckets() {
		return new byte[] {BUCKET};
	}

	static byte[] bucket(final String name) {
		return new Builder(BUCKET).text(name).bytes();
	}

	static byte[] objects(final byte kind, final String bucket, final String prefix) {
		return new Builder(kind).text(bucket).raw(prefix).bytes();
	}

	static byte[] object(final byte kind, final String bucket, final String key) {
		return new Builder(kind).text(bucket).text(key).bytes();
	}

	static byte[] version(final String bucket, final String key, final long sequence) {
		return new Builder(VERSION).text(bucket).text(key).number(Long.MAX_VALUE - sequence).bytes();
	}

	static byte[] upload(final String bucket, final String key, final long sequence) {
		return new Builder(UPLOAD).text(bucket).text(key).number(sequence).bytes();
	}

	static byte[] parts(final long upload) {
		return new Builder(PART).number(upload).bytes();
	}

	static byte[] part(final long upload, final int number) {
		return new Builder(PART).number(upload).number(number).bytes();
	}

	static byte[] reference(final BlobId blob) {
		return new Builder(REFERENCE).text(blob.value()).bytes();
	}

	static byte[] garbage() {
		return new byte[] {GARBAGE};
	}

	static byte[] garbage(final BlobId blob) {
		return new Builder(GARBAGE).text(blob.value()).bytes();
	}

	static BlobId garbageBlob(final byte[] key) {
		return new BlobId(new Cursor(key).text());
	}

	/* Parses version and upload keys, whose trailing number is the sequence they were written at */
	static Parsed parse(final byte[] key) {
		final Cursor cursor = new Cursor(key);
		final String bucket = cursor.text();
		final String name = cursor.text();
		final long number = cursor.number();

		return new Parsed(bucket, name, key[0] == VERSION ? Long.MAX_VALUE - number : number);
	}

	/* The first key after every key that starts with the prefix, or null when no such key exists */
	static byte[] end(final byte[] prefix) {
		for (int i = prefix.length - 1; i >= 0; i--) {
			if (prefix[i] != (byte) ESCAPE) {
				final byte[] end = Arrays.copyOf(prefix, i + 1);

				end[i]++;
				return end;
			}
		}

		return null;
	}

	/* The first key after the given one */
	static byte[] after(final byte[] key) {
		return Arrays.copyOf(key, key.length + 1);
	}

	static String id(final long sequence) {
		return HEX.toHexDigits(sequence);
	}

	static OptionalLong sequence(final String id) {
		if (id.length() != ID_LENGTH || !id.chars().allMatch(HexFormat::isHexDigit)) {
			return OptionalLong.empty();
		}

		final long sequence = HexFormat.fromHexDigitsToLong(id);
		return id.equals(id(sequence)) ? OptionalLong.of(sequence) : OptionalLong.empty();
	}

	/* Code point order, which is the UTF-8 byte order S3 lists keys in */
	static int compare(final String first, final String second) {
		final int length = Math.min(first.length(), second.length());

		for (int i = 0; i < length; i++) {
			if (first.charAt(i) != second.charAt(i)) {
				return Integer.compare(order(first.charAt(i)), order(second.charAt(i)));
			}
		}

		return Integer.compare(first.length(), second.length());
	}

	/* UTF-16 sorts U+E000 to U+FFFF above the surrogates while code point order puts them below */
	private static int order(final char unit) {
		if (Character.isSurrogate(unit)) {
			return unit + 0x2000;
		}

		return unit >= 0xE000 ? unit - 0x800 : unit;
	}

	private static final class Builder {
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		Builder(final byte kind) {
			bytes.write(kind);
		}

		Builder raw(final String text) {
			for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
				bytes.write(value);

				if (value == 0) {
					bytes.write(ESCAPE);
				}
			}

			return this;
		}

		Builder text(final String text) {
			raw(text);
			bytes.write(0);
			bytes.write(TERMINATOR);
			return this;
		}

		Builder number(final long number) {
			bytes.writeBytes(ByteBuffer.allocate(Long.BYTES).putLong(number).array());
			return this;
		}

		Builder number(final int number) {
			bytes.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(number).array());
			return this;
		}

		byte[] bytes() {
			return bytes.toByteArray();
		}
	}

	private static final class Cursor {
		private final byte[] key;
		private int position = 1;

		Cursor(final byte[] key) {
			this.key = key;
		}

		String text() {
			final ByteArrayOutputStream text = new ByteArrayOutputStream();

			while (key[position] != 0 || key[position + 1] != TERMINATOR) {
				text.write(key[position]);
				position += key[position] == 0 ? 2 : 1;
			}

			position += 2;
			return text.toString(StandardCharsets.UTF_8);
		}

		long number() {
			return ByteBuffer.wrap(key, position, Long.BYTES).getLong();
		}
	}
}
