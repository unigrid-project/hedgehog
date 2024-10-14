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

package org.unigrid.hedgehog.service.storage;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;
import org.unigrid.hedgehog.model.spork.StorageSpork;

public final class StorageArbitraries {
	private StorageArbitraries() {
		/* Static helpers only */
	}

	public static Arbitrary<StorageSpork.SporkData> parameters() {
		return Combinators.combine(Arbitraries.of(32, 64, 128), Arbitraries.integers().between(2, 12),
			Arbitraries.integers().between(25, 100), Arbitraries.integers().between(0, 50),
			Arbitraries.integers().between(0, 100), Arbitraries.integers().between(1, 6))
			.as((fragmentSize, dataFragments, inner, extra, outer, stripeChunks) -> {
				final StorageSpork.SporkData data = StorageTestData.parameters();

				data.setFragmentSize(fragmentSize);
				data.setChunkSize(fragmentSize * dataFragments);
				data.setInnerParityPercent(inner);
				data.setMaxParityPercent(inner + extra);
				data.setOuterParityPercent(outer);
				data.setMaxOuterDataChunks(stripeChunks);
				return data;
			}).filter(StorageArbitraries::isValid);
	}

	public static Arbitrary<byte[]> files(final StorageSpork.SporkData parameters) {
		final int payload = parameters.layout().payloadSize();
		final int stripe = payload * parameters.getMaxOuterDataChunks();
		final Arbitrary<Integer> sizes = Arbitraries.frequencyOf(
			Tuple.of(1, Arbitraries.of(0, 1, payload - 1, payload, payload + 1, stripe, stripe + 1)),
			Tuple.of(3, Arbitraries.integers().between(0, 3 * stripe + 1)));

		return sizes.flatMap(size -> Arbitraries.bytes().array(byte[].class).ofSize(size));
	}

	private static boolean isValid(final StorageSpork.SporkData data) {
		try {
			data.validate();
			return true;
		} catch (IllegalArgumentException ex) {
			return false;
		}
	}
}
