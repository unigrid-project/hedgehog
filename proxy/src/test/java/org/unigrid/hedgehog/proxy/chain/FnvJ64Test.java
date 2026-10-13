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

package org.unigrid.hedgehog.proxy.chain;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class FnvJ64Test {
	/* The inputs of greenrobot's AbstractChecksumTest */
	private static final byte[] INPUT4 = { (byte) 0xcc, 0x24, 0x31, (byte) 0xc4 };
	private static final byte[] INPUT16 = { (byte) 0xe0, 0x4d, (byte) 0x9f, (byte) 0xcb, (byte) 0xd5, 0x6b,
		(byte) 0xb9, 0x53, 0x42, (byte) 0x87, 0x08, 0x36, 0x77, 0x23, 0x01, 0 };

	@Example
	public void shouldMatchTheKnownAnswersOfTheOriginal() {
		final FnvJ64 checksum = new FnvJ64();

		for (int i = 0; i < 4; i++) {
			checksum.update(0);
		}

		assertThat(checksum.getValue(), equalTo(-2788557096217532181L));
		assertThat(FnvJ64.hash(INPUT4), equalTo(5189117314893555947L));
		assertThat(FnvJ64.hash(INPUT16), equalTo(6178430581444874676L));
	}

	/* Values computed with the original FNVJ64 from greenrobot essentials 278bfa06^ */
	@Example
	public void shouldMatchTheOriginalAroundWordBoundaries() {
		final byte[] counting = new byte[17];

		for (int i = 0; i < counting.length; i++) {
			counting[i] = (byte) i;
		}

		assertThat(FnvJ64.hash(new byte[0]), equalTo(590684067820433389L));
		assertThat(FnvJ64.hash("hedgehog".getBytes(StandardCharsets.US_ASCII)), equalTo(6594327043365588960L));
		assertThat(FnvJ64.hash(counting), equalTo(-1364738195530125878L));
	}

	@Property(tries = 300)
	public void shouldHashChunksLikeTheWhole(@ForAll @Size(max = 80) byte[] bytes, @ForAll Random random) {
		final FnvJ64 checksum = new FnvJ64();
		int offset = 0;

		while (offset < bytes.length) {
			final int chunk = 1 + random.nextInt(bytes.length - offset);

			checksum.update(bytes, offset, chunk);
			offset += chunk;
		}

		assertThat(checksum.getValue(), equalTo(FnvJ64.hash(bytes)));
	}

	@Property(tries = 200)
	public void shouldHashSingleBytesLikeTheWhole(@ForAll @Size(max = 80) byte[] bytes) {
		final FnvJ64 checksum = new FnvJ64();

		for (byte b : bytes) {
			checksum.update(b);
		}

		assertThat(checksum.getValue(), equalTo(FnvJ64.hash(bytes)));
	}

	@Property(tries = 100)
	public void shouldStartOverAfterAReset(@ForAll byte[] before, @ForAll byte[] after) {
		final FnvJ64 checksum = new FnvJ64();

		checksum.update(before, 0, before.length);
		checksum.reset();
		checksum.update(after, 0, after.length);
		assertThat(checksum.getValue(), equalTo(FnvJ64.hash(after)));
	}

	@Property(tries = 100)
	public void shouldNotChangeWhenReadAgain(@ForAll byte[] bytes) {
		final FnvJ64 checksum = new FnvJ64();

		checksum.update(bytes, 0, bytes.length);
		assertThat(checksum.getValue(), equalTo(checksum.getValue()));
	}
}
