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

import java.nio.charset.StandardCharsets;
import java.util.function.IntUnaryOperator;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class Crc64NvmeTest {
	private static final int BLOCK = 4096;
	private static final long POLYNOMIAL = 0xAD93D23594C93659L;

	private static long crc(final byte[] data) {
		final Crc64Nvme crc = new Crc64Nvme();

		crc.update(data, 0, data.length);
		return crc.getValue();
	}

	private static byte[] block(final IntUnaryOperator pattern) {
		final byte[] block = new byte[BLOCK];

		for (int i = 0; i < BLOCK; i++) {
			block[i] = (byte) pattern.applyAsInt(i);
		}

		return block;
	}

	/* Straight from the definition, one bit at a time with the polynomial in its usual order */
	private static long reference(final byte[] data) {
		long crc = -1L;

		for (byte value : data) {
			crc ^= Long.reverse(value & 0xFFL);

			for (int bit = 0; bit < Byte.SIZE; bit++) {
				crc = crc < 0 ? (crc << 1) ^ POLYNOMIAL : crc << 1;
			}
		}

		return ~Long.reverse(crc);
	}

	@Example
	public void matchesTheCheckValueOfTheCatalogue() {
		assertThat(crc("123456789".getBytes(StandardCharsets.US_ASCII)), equalTo(0xAE8B14860A799888L));
	}

	@Example
	public void matchesTheTestCasesOfTheNvmeSpecification() {
		assertThat(crc(block(i -> 0)), equalTo(0x6482D367EB22B64EL));
		assertThat(crc(block(i -> 0xFF)), equalTo(0xC0DDBA7302ECA3ACL));
		assertThat(crc(block(i -> i)), equalTo(0x3E729F5F6750449CL));
		assertThat(crc(block(i -> 0xFF - i)), equalTo(0x9A2DF64B8E9E517EL));
	}

	@Property
	public void agreesWithTheBitwiseDefinitionHoweverTheDataIsSplit(@ForAll @Size(max = 300) byte[] data,
		@ForAll @IntRange(max = 300) int split) {

		final Crc64Nvme crc = new Crc64Nvme();
		final int head = Math.min(split, data.length);

		crc.update(data, 0, head);
		for (int i = head; i < data.length; i++) {
			crc.update(data[i]);
		}

		assertThat(crc.getValue(), equalTo(reference(data)));
	}

	@Property
	public void startsOverWhenReset(@ForAll byte[] first, @ForAll byte[] second) {
		final Crc64Nvme crc = new Crc64Nvme();

		crc.update(first, 0, first.length);
		crc.reset();
		crc.update(second, 0, second.length);

		assertThat(crc.getValue(), equalTo(crc(second)));
	}
}
