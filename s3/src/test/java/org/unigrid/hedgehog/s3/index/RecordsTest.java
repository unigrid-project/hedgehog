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

import java.io.IOException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;

public class RecordsTest {
	private static final String BUCKET = "bucket";
	private static final String KEY = "key";

	@Provide
	Arbitrary<Tuple2<Long, ObjectVersion>> versions() {
		return Arbitraries.longs().between(1, Long.MAX_VALUE).flatMap(sequence -> Arbitraries.of(Keys.id(sequence),
			ObjectVersion.NULL_VERSION).flatMap(id -> IndexArbitraries.versions(BUCKET, KEY, id)
			.map(version -> Tuple.of(sequence, version))));
	}

	@Provide
	Arbitrary<Bucket> buckets() {
		return IndexArbitraries.buckets(BUCKET);
	}

	@Provide
	Arbitrary<Tuple2<Long, Upload>> uploads() {
		return Arbitraries.longs().between(1, Long.MAX_VALUE).flatMap(sequence -> IndexArbitraries
			.uploads(BUCKET, KEY, Keys.id(sequence)).map(upload -> Tuple.of(sequence, upload)));
	}

	@Provide
	Arbitrary<Part> parts() {
		return IndexArbitraries.parts();
	}

	@Provide
	Arbitrary<byte[]> encodings() {
		return Combinators.combine(versions(), buckets(), uploads(), parts()).as((version, bucket, upload, part) ->
			new byte[][] {Records.version(version.get2()), Records.bucket(bucket), Records.upload(upload.get2()),
				Records.part(part)}).flatMap(Arbitraries::of);
	}

	@Property
	public void readsBackTheVersionsItWrites(@ForAll("versions") final Tuple2<Long, ObjectVersion> stored)
		throws IOException {

		final ObjectVersion version = stored.get2();

		assertThat(Records.version(BUCKET, KEY, stored.get1(), Records.version(version)), equalTo(version));
	}

	@Property
	public void readsBackTheBucketsItWrites(@ForAll("buckets") final Bucket bucket) throws IOException {
		assertThat(Records.bucket(BUCKET, Records.bucket(bucket)), equalTo(bucket));
	}

	@Property
	public void readsBackTheUploadsItWrites(@ForAll("uploads") final Tuple2<Long, Upload> stored) throws IOException {
		assertThat(Records.upload(BUCKET, KEY, stored.get1(), Records.upload(stored.get2())), equalTo(stored.get2()));
	}

	@Property
	public void readsBackThePartsItWrites(@ForAll("parts") final Part part) throws IOException {
		assertThat(Records.part(Records.part(part)), equalTo(part));
	}

	@Property(tries = 100)
	public void refusesAFormatItDoesNotKnow(@ForAll("encodings") final byte[] encoding,
		@ForAll @IntRange(min = Records.FORMAT + 1, max = Byte.MAX_VALUE) final int format) {

		encoding[0] = (byte) format;
		assertThrows(IOException.class, () -> Records.version(BUCKET, KEY, 1, encoding));
		assertThrows(IOException.class, () -> Records.bucket(BUCKET, encoding));
		assertThrows(IOException.class, () -> Records.upload(BUCKET, KEY, 1, encoding));
		assertThrows(IOException.class, () -> Records.part(encoding));
	}
}
