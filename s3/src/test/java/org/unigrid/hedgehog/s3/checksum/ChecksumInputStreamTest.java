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

package org.unigrid.hedgehog.s3.checksum;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.jqwik.Expect;

public class ChecksumInputStreamTest {
	/* Reads one byte, a run of bytes or skips some, as the steps say, until the stream ends */
	private static void consume(final ChecksumInputStream stream, final List<Integer> steps) throws IOException {
		for (int i = 0; !stream.isEnded(); i++) {
			final int step = steps.isEmpty() ? 1 : steps.get(i % steps.size());

			switch (step % 3) {
				case 0 -> stream.read();
				case 1 -> stream.read(new byte[step + 1], 1, step);
				default -> stream.skip(step);
			}
		}
	}

	@Property
	public void digestsEverythingThatPassesThrough(@ForAll @Size(max = 5000) byte[] data,
		@ForAll Set<ChecksumAlgorithm> algorithms, @ForAll List<@IntRange(min = 1, max = 700) Integer> steps)
		throws Exception {

		final ChecksumInputStream stream = new ChecksumInputStream(new ByteArrayInputStream(data), algorithms,
			ended -> { }
		);

		consume(stream, steps);
		assertThat(stream.count(), equalTo((long) data.length));
		assertThat(stream.md5(), equalTo(MessageDigest.getInstance("MD5").digest(data)));

		for (ChecksumAlgorithm algorithm : algorithms) {
			assertThat(stream.digest(algorithm), equalTo(algorithm.digest().digest(data)));
		}
	}

	@Property
	public void checksOnceAtTheEnd(@ForAll byte[] data) throws IOException {
		final AtomicInteger checks = new AtomicInteger();
		final ChecksumInputStream stream = new ChecksumInputStream(new ByteArrayInputStream(data), Set.of(),
			ended -> assertThat(checks.incrementAndGet() == 1 && ended.count() == data.length, equalTo(true))
		);

		stream.readAllBytes();
		assertThat(stream.read(), equalTo(-1));
		assertThat(checks.get(), equalTo(1));
	}

	@Example
	public void keepsRefusingWhatTheEndCheckRefused() {
		final S3Exception mismatch = new S3Exception(S3ErrorCode.BadDigest);
		final ChecksumInputStream stream = new ChecksumInputStream(new ByteArrayInputStream(new byte[3]), Set.of(),
			ended -> {
				throw mismatch;
			}
		);

		assertThat(Expect.assertThrows(S3Exception.class, stream::readAllBytes), sameInstance(mismatch));
		assertThat(Expect.assertThrows(S3Exception.class, stream::read), sameInstance(mismatch));
	}
}
