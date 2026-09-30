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
import java.util.Arrays;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class ManifestTest {
	private static final int FILE_SIZE_OFFSET = 1;
	private static final int COPIES_OFFSET = FILE_SIZE_OFFSET + Long.BYTES;
	private static final int FRAGMENT_SIZE_OFFSET = COPIES_OFFSET + 1 + Integer.BYTES;

	private static byte[] encodedSmall() {
		return new Manifest(StorageFormat.current(), 1, 1, LayoutParametersTest.small()).encode();
	}

	@Provide
	public Arbitrary<LayoutParameters> layouts() {
		return LayoutParametersTest.validLayouts();
	}

	@Property(tries = 300)
	public void roundTripsThroughPaddedPlaintext(@ForAll @LongRange(min = 0) long fileSize,
		@ForAll @IntRange(min = 1, max = Manifest.MAX_COPIES) int copies, @ForAll("layouts") LayoutParameters layout) {

		final Manifest manifest = new Manifest(StorageFormat.current(), fileSize, copies, layout);
		final byte[] encoded = manifest.encode();

		assertThat(encoded.length, equalTo(Manifest.ENCODED_SIZE));
		assertThat(Manifest.decode(Arrays.copyOf(encoded, layout.payloadSize())), equalTo(manifest));
	}

	@Property
	public void encodesExactlyTheCopyCountsAByteCanHold(@ForAll @IntRange(min = -300, max = 600) int copies) {
		final Manifest manifest = new Manifest(StorageFormat.current(), 1, copies, LayoutParametersTest.small());

		if (copies >= 1 && copies <= Manifest.MAX_COPIES) {
			assertThat(Manifest.decode(manifest.encode()).getManifestCopies(), equalTo(copies));
		} else {
			assertThrows(IllegalArgumentException.class, manifest::encode);
		}
	}

	@Property
	public void decodesEveryCopyCountButZero(@ForAll byte copies) {
		final byte[] encoded = encodedSmall();
		encoded[COPIES_OFFSET] = copies;

		if (copies == 0) {
			assertThrows(IllegalArgumentException.class, () -> Manifest.decode(encoded));
		} else {
			assertThat(Manifest.decode(encoded).getManifestCopies(), equalTo(copies & 0xFF));
		}
	}

	@Property
	public void rejectsNegativeFileSizes(@ForAll @LongRange(min = Long.MIN_VALUE, max = -1) long fileSize) {
		final byte[] encoded = encodedSmall();
		ByteBuffer.wrap(encoded).putLong(FILE_SIZE_OFFSET, fileSize);

		assertThrows(IllegalArgumentException.class, () -> Manifest.decode(encoded));
		assertThrows(IllegalArgumentException.class, () ->
			new Manifest(StorageFormat.current(), fileSize, 1, LayoutParametersTest.small()).encode()
		);
	}

	@Property
	public void rejectsTruncatedPlaintext(@ForAll @IntRange(min = 0, max = Manifest.ENCODED_SIZE - 1) int length) {
		final byte[] truncated = Arrays.copyOf(encodedSmall(), length);

		assertThrows(IllegalArgumentException.class, () -> Manifest.decode(truncated));
	}

	@Property
	public void rejectsUnknownFormats(@ForAll byte formatId) {
		Assume.that(Arrays.stream(StorageFormat.values()).noneMatch(format -> format.getId() == formatId));

		final byte[] encoded = encodedSmall();
		encoded[0] = formatId;

		assertThrows(IllegalArgumentException.class, () -> Manifest.decode(encoded));
	}

	@Property
	public void rejectsLayoutsThatCannotBeStored(@ForAll @IntRange(min = 1, max = 1023) int fragmentSize) {
		final LayoutParameters layout = LayoutParametersTest.small().toBuilder().fragmentSize(fragmentSize).build();
		Assume.that(layout.getChunkSize() % fragmentSize != 0);

		final byte[] encoded = encodedSmall();
		ByteBuffer.wrap(encoded).putInt(FRAGMENT_SIZE_OFFSET, fragmentSize);

		assertThrows(IllegalArgumentException.class, () -> Manifest.decode(encoded));
		assertThrows(IllegalArgumentException.class, () -> new Manifest(StorageFormat.current(), 1, 1, layout).encode());
	}
}
