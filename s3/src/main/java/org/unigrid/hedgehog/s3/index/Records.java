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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.unigrid.hedgehog.s3.spi.BlobId;

/* Every value opens with a format byte so its layout can change without rewriting the namespace. Names, keys and
   ids are left out because the index key of the record already holds them. */
final class Records {
	static final int FORMAT = 1;

	private Records() {
		/* Static helpers only */
	}

	private static void checkFormat(final DataInput input) throws IOException {
		final int format = input.readUnsignedByte();

		if (format != FORMAT) {
			throw new CorruptIndexException("Unknown record format " + format);
		}
	}

	static byte[] bucket(final Bucket bucket) {
		return Codec.encode(output -> {
			output.writeByte(FORMAT);
			Codec.writeInstant(output, bucket.created());
			Codec.writeNullable(output, bucket.location(), Codec::writeString);
			Codec.writeString(output, bucket.versioning().name());
			output.writeBoolean(bucket.objectLock());
			Codec.writeMap(output, bucket.tags());
			Codec.writeMap(output, bucket.configurations());
		});
	}

	static Bucket bucket(final String name, final byte[] value) throws IOException {
		return Codec.decode(value, input -> {
			checkFormat(input);

			return Bucket.builder().name(name).created(Codec.readInstant(input))
				.location(Codec.readNullable(input, Codec::readString))
				.versioning(Codec.readEnum(input, Versioning.class)).objectLock(input.readBoolean())
				.tags(Codec.readMap(input)).configurations(Codec.readMap(input)).build();
		});
	}

	static byte[] version(final ObjectVersion version) {
		return Codec.encode(output -> {
			output.writeByte(FORMAT);
			output.writeBoolean(version.isNullVersion());
			writeVersion(output, version);
		});
	}

	static ObjectVersion version(final String bucket, final String key, final long sequence, final byte[] value)
		throws IOException {

		return Codec.decode(value, input -> {
			checkFormat(input);

			final String id = input.readBoolean() ? ObjectVersion.NULL_VERSION : Keys.id(sequence);
			return readVersion(input, bucket, key, id);
		});
	}

	/* Reads the flag that follows the format byte without decoding the rest */
	static boolean isNullVersion(final byte[] value) {
		return value.length > 1 && value[1] != 0;
	}

	static byte[] upload(final Upload upload) {
		return Codec.encode(output -> {
			output.writeByte(FORMAT);
			Codec.writeNullable(output, upload.checksumAlgorithm(), Codec::writeString);
			Codec.writeNullable(output, upload.checksumType(), Codec::writeString);
			writeVersion(output, upload.template());
		});
	}

	static Upload upload(final String bucket, final String key, final long sequence, final byte[] value)
		throws IOException {

		return Codec.decode(value, input -> {
			checkFormat(input);

			final String algorithm = Codec.readNullable(input, Codec::readString);
			final String type = Codec.readNullable(input, Codec::readString);
			return new Upload(Keys.id(sequence), readVersion(input, bucket, key, null), algorithm, type);
		});
	}

	static byte[] part(final Part part) {
		return Codec.encode(output -> {
			output.writeByte(FORMAT);
			writePart(output, part);
		});
	}

	static Part part(final byte[] value) throws IOException {
		return Codec.decode(value, input -> {
			checkFormat(input);
			return readPart(input);
		});
	}

	private static void writeVersion(final DataOutput output, final ObjectVersion version) throws IOException {
		output.writeBoolean(version.deleteMarker());
		Codec.writeInstant(output, version.lastModified());
		Codec.writeNullable(output, version.etag(), Codec::writeString);
		Codec.writeList(output, version.parts(), Records::writePart);
		Codec.writeMap(output, version.checksums());
		Codec.writeMap(output, version.headers());
		Codec.writeMap(output, version.metadata());
		Codec.writeMap(output, version.tags());
		Codec.writeNullable(output, version.retention(), Records::writeRetention);
		output.writeBoolean(version.legalHold());
	}

	private static ObjectVersion readVersion(final DataInput input, final String bucket, final String key,
		final String versionId) throws IOException {

		return ObjectVersion.builder().bucket(bucket).key(key).versionId(versionId)
			.deleteMarker(input.readBoolean()).lastModified(Codec.readInstant(input))
			.etag(Codec.readNullable(input, Codec::readString)).parts(Codec.readList(input, Records::readPart))
			.checksums(Codec.readMap(input)).headers(Codec.readMap(input)).metadata(Codec.readMap(input))
			.tags(Codec.readMap(input)).retention(Codec.readNullable(input, Records::readRetention))
			.legalHold(input.readBoolean()).build();
	}

	private static void writePart(final DataOutput output, final Part part) throws IOException {
		Codec.writeNumber(output, part.number());
		Codec.writeList(output, part.slices(), Records::writeSlice);
		Codec.writeNullable(output, part.etag(), Codec::writeString);
		Codec.writeMap(output, part.checksums());
		Codec.writeNullable(output, part.lastModified(), Codec::writeInstant);
	}

	private static Part readPart(final DataInput input) throws IOException {
		return Part.builder().number(Codec.readInt(input)).slices(Codec.readList(input, Records::readSlice))
			.etag(Codec.readNullable(input, Codec::readString)).checksums(Codec.readMap(input))
			.lastModified(Codec.readNullable(input, Codec::readInstant)).build();
	}

	private static void writeSlice(final DataOutput output, final BlobRef slice) throws IOException {
		Codec.writeString(output, slice.id().value());
		Codec.writeNumber(output, slice.offset());
		Codec.writeNumber(output, slice.length());
	}

	private static BlobRef readSlice(final DataInput input) throws IOException {
		return new BlobRef(new BlobId(Codec.readString(input)), Codec.readNumber(input), Codec.readNumber(input));
	}

	private static void writeRetention(final DataOutput output, final Retention retention) throws IOException {
		Codec.writeString(output, retention.mode().name());
		Codec.writeInstant(output, retention.until());
	}

	private static Retention readRetention(final DataInput input) throws IOException {
		return new Retention(Codec.readEnum(input, RetentionMode.class), Codec.readInstant(input));
	}
}
