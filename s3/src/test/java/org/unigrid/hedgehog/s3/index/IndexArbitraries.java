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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Builders;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;
import org.unigrid.hedgehog.s3.spi.BlobId;

public final class IndexArbitraries {
	private static final int MAX_TEXT = 8;
	private static final int MAX_ITEMS = 3;
	private static final long LATEST_SECOND = 4_000_000_000L;

	private IndexArbitraries() {
		/* Static helpers only */
	}

	/* Mostly a small alphabet so keys share prefixes and delimiters, with NUL, the edges of UTF-16 and surrogate
	   pairs mixed in because they are where code point order and naive orders part ways */
	public static Arbitrary<String> texts() {
		return texts(MAX_TEXT);
	}

	public static Arbitrary<String> texts(final int maxCodePoints) {
		final Arbitrary<Integer> codePoints = Arbitraries.frequencyOf(
			Tuple.of(12, Arbitraries.of((int) 'a', (int) 'b', (int) '/', (int) '.')),
			Tuple.of(1, Arbitraries.of(0, 0x7F, 0x80, 0xD7FF, 0xE000, 0xFFFF, 0x10000, 0x10FFFF)),
			Tuple.of(1, Arbitraries.integers().between(0, Character.MAX_CODE_POINT).filter(codePoint ->
				codePoint < Character.MIN_SURROGATE || codePoint > Character.MAX_SURROGATE))
		);

		return codePoints.list().ofMaxSize(maxCodePoints).map(list -> list.stream()
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString());
	}

	/* Few distinct bytes, the escape and terminator values among them, so batches keep hitting the same keys */
	public static Arbitrary<byte[]> rawKeys() {
		return Arbitraries.of((byte) 0x00, (byte) 0x01, (byte) 0x7F, (byte) 0x80, (byte) 0xFF).list().ofMaxSize(3)
			.map(list -> {
				final byte[] key = new byte[list.size()];

				for (int i = 0; i < key.length; i++) {
					key[i] = list.get(i);
				}

				return key;
			});
	}

	public static Arbitrary<Batch> batches() {
		final Arbitrary<byte[]> values = Arbitraries.bytes().array(byte[].class).ofMaxSize(MAX_TEXT).injectNull(0.3);

		return Combinators.combine(rawKeys(), values).as(Slot::new).list().ofMinSize(1).ofMaxSize(MAX_ITEMS)
			.map(slots -> {
				final Batch batch = new Batch();

				slots.forEach(slot -> batch.write(slot.key(), slot.value()));
				return batch;
			});
	}

	public static Arbitrary<Instant> instants() {
		return Combinators.combine(Arbitraries.longs().between(0, LATEST_SECOND),
			Arbitraries.integers().between(0, 999_999_999)).as(Instant::ofEpochSecond);
	}

	public static Arbitrary<Map<String, String>> maps() {
		return Arbitraries.maps(texts(), texts()).ofMaxSize(MAX_ITEMS);
	}

	/* Every blob gets a random id of its own the way the blob store hands them out, so only copies share blobs.
	   Edge cases and shrinking would turn the ids of unrelated blobs into the same one. */
	public static Arbitrary<BlobRef> slices() {
		final Arbitrary<Long> numbers = Arbitraries.longs().between(0, Long.MAX_VALUE / 2);
		final Arbitrary<BlobId> ids = Arbitraries.randomValue(random -> new BlobId(new UUID(random.nextLong(),
			random.nextLong()).toString()));

		return Combinators.combine(ids, numbers, numbers).as(BlobRef::new);
	}

	public static Arbitrary<Part> parts() {
		return Builders.withBuilder(Part::builder)
			.use(Arbitraries.integers().between(1, 10_000)).in(Part.PartBuilder::number)
			.use(slices().list().ofMaxSize(MAX_ITEMS)).in(Part.PartBuilder::slices)
			.use(texts().injectNull(0.2)).in(Part.PartBuilder::etag)
			.use(maps()).in(Part.PartBuilder::checksums)
			.use(instants().injectNull(0.2)).in(Part.PartBuilder::lastModified)
			.build(Part.PartBuilder::build);
	}

	public static Arbitrary<Retention> retentions() {
		return Combinators.combine(Arbitraries.of(RetentionMode.class), instants()).as(Retention::new);
	}

	/* The identity is left to the caller, which knows the bucket, key and version id the record is stored under */
	public static Arbitrary<ObjectVersion> versions(final String bucket, final String key, final String versionId) {
		return Builders.withBuilder(() -> ObjectVersion.builder().bucket(bucket).key(key).versionId(versionId))
			.use(Arbitraries.of(true, false)).in(ObjectVersion.ObjectVersionBuilder::deleteMarker)
			.use(instants()).in(ObjectVersion.ObjectVersionBuilder::lastModified)
			.use(texts().injectNull(0.2)).in(ObjectVersion.ObjectVersionBuilder::etag)
			.use(parts().list().ofMaxSize(MAX_ITEMS)).in(ObjectVersion.ObjectVersionBuilder::parts)
			.use(maps()).in(ObjectVersion.ObjectVersionBuilder::checksums)
			.use(maps()).in(ObjectVersion.ObjectVersionBuilder::headers)
			.use(maps()).in(ObjectVersion.ObjectVersionBuilder::metadata)
			.use(maps()).in(ObjectVersion.ObjectVersionBuilder::tags)
			.use(retentions().injectNull(0.5)).in(ObjectVersion.ObjectVersionBuilder::retention)
			.use(Arbitraries.of(true, false)).in(ObjectVersion.ObjectVersionBuilder::legalHold)
			.build(ObjectVersion.ObjectVersionBuilder::build);
	}

	public static Arbitrary<Bucket> buckets(final String name) {
		return Builders.withBuilder(() -> Bucket.builder().name(name))
			.use(instants()).in(Bucket.BucketBuilder::created)
			.use(texts().injectNull(0.3)).in(Bucket.BucketBuilder::location)
			.use(Arbitraries.of(Versioning.class)).in(Bucket.BucketBuilder::versioning)
			.use(Arbitraries.of(true, false)).in(Bucket.BucketBuilder::objectLock)
			.use(maps()).in(Bucket.BucketBuilder::tags)
			.use(maps()).in(Bucket.BucketBuilder::configurations)
			.build(Bucket.BucketBuilder::build);
	}

	public static Arbitrary<Upload> uploads(final String bucket, final String key, final String uploadId) {
		return Combinators.combine(versions(bucket, key, null).map(template -> template.toBuilder()
			.deleteMarker(false).parts(List.of()).build()), texts().injectNull(0.3), texts().injectNull(0.3))
			.as((template, algorithm, type) -> new Upload(uploadId, template, algorithm, type));
	}
}
