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

import java.util.Arrays;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class ManifestTest {
	@Property(tries = 100)
	public void roundTripsThroughPaddedPlaintext(@ForAll @LongRange(min = 0) long fileSize,
		@ForAll @IntRange(min = 1, max = 16) int copies) {

		final Manifest manifest = new Manifest(StorageFormat.current(), fileSize, copies, LayoutParametersTest.small());
		final byte[] padded = Arrays.copyOf(manifest.encode(), 1008);

		assertThat(Manifest.decode(padded), equalTo(manifest));
	}

	@Property
	public void rejectsUnknownFormats(@ForAll byte formatId) {
		Assume.that(Arrays.stream(StorageFormat.values()).noneMatch(format -> format.getId() == formatId));

		final byte[] encoded = new Manifest(StorageFormat.current(), 1, 1, LayoutParametersTest.small()).encode();
		encoded[0] = formatId;

		assertThrows(IllegalArgumentException.class, () -> Manifest.decode(encoded));
	}

	@Property
	public void rejectsLayoutsThatCannotBeStored(@ForAll @IntRange(min = 1, max = 1023) int fragmentSize) {
		final LayoutParameters layout = LayoutParametersTest.small().toBuilder().fragmentSize(fragmentSize).build();
		Assume.that(layout.getChunkSize() % fragmentSize != 0);

		final byte[] encoded = new Manifest(StorageFormat.current(), 1, 1, layout).encode();

		assertThrows(IllegalArgumentException.class, () -> Manifest.decode(encoded));
	}
}
