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

package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Builders;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.model.network.codec.chunk.StorageSporkDecoder;
import org.unigrid.hedgehog.model.network.codec.chunk.StorageSporkEncoder;
import org.unigrid.hedgehog.model.spork.StorageSpork.SporkData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class StorageSporkIntegrityTest {
	private static final int ENCODED_SIZE = 40;

	private static <T> BiFunction<SporkData, T, SporkData> set(BiConsumer<SporkData, T> setter) {
		return (data, value) -> {
			setter.accept(data, value);
			return data;
		};
	}

	@Provide
	public Arbitrary<SporkData> sporkData() {
		final Arbitrary<Integer> ints = Arbitraries.integers();
		final Arbitrary<Integer> unsignedShorts = Arbitraries.integers().between(0, 0xFFFF);
		final Arbitrary<Integer> unsignedBytes = Arbitraries.integers().between(0, 0xFF);

		return Builders.withBuilder(SporkData::new)
			.use(Arbitraries.longs()).in(set(SporkData::setMaxBytesPerNode))
			.use(ints).in(set(SporkData::setChunkSize))
			.use(ints).in(set(SporkData::setFragmentSize))
			.use(unsignedShorts).in(set(SporkData::setOuterParityPercent))
			.use(unsignedShorts).in(set(SporkData::setMaxOuterDataChunks))
			.use(unsignedShorts).in(set(SporkData::setInnerParityPercent))
			.use(unsignedShorts).in(set(SporkData::setMaxParityPercent))
			.use(ints).in(set(SporkData::setRepairIntervalMinutes))
			.use(unsignedShorts).in(set(SporkData::setTombstoneDays))
			.use(unsignedBytes).in(set(SporkData::setManifestCopies))
			.use(unsignedBytes).in(set(SporkData::setPlacementSlack))
			.use(unsignedBytes).in(set(SporkData::setRepairThresholdPercent))
			.use(unsignedBytes).in(set(SporkData::setExtraPoolPercent))
			.build();
	}

	@SneakyThrows
	@Property
	public void survivesARoundTripInAFixedSize(@ForAll("sporkData") SporkData data) {
		final ByteBuf buffer = Unpooled.buffer();

		new StorageSporkEncoder().encodeChunk(null, data, buffer);
		assertThat(buffer.readableBytes(), equalTo(ENCODED_SIZE));
		assertThat(new StorageSporkDecoder().decodeChunk(null, buffer).orElseThrow(), equalTo(data));
		assertThat(buffer.readableBytes(), equalTo(0));
	}
}
