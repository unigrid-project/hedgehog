/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
import org.unigrid.hedgehog.model.spork.StorageSporkTest;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.model.spork.StorageSporkTest.with;

public class StorageSporkIntegrityTest {
	private static final int ENCODED_SIZE = 40;

	@Provide
	public Arbitrary<SporkData> wireWideSporkData() {
		final Arbitrary<Integer> ints = Arbitraries.integers();
		final Arbitrary<Integer> unsignedShorts = Arbitraries.integers().between(0, 0xFFFF);
		final Arbitrary<Integer> unsignedBytes = Arbitraries.integers().between(0, 0xFF);

		return Builders.withBuilder(SporkData::new)
			.use(Arbitraries.longs()).in(with(SporkData::setMaxBytesPerNode))
			.use(ints).in(with(SporkData::setChunkSize))
			.use(ints).in(with(SporkData::setFragmentSize))
			.use(unsignedShorts).in(with(SporkData::setOuterParityPercent))
			.use(unsignedShorts).in(with(SporkData::setMaxOuterDataChunks))
			.use(unsignedShorts).in(with(SporkData::setInnerParityPercent))
			.use(unsignedShorts).in(with(SporkData::setMaxParityPercent))
			.use(ints).in(with(SporkData::setRepairIntervalMinutes))
			.use(unsignedShorts).in(with(SporkData::setTombstoneDays))
			.use(unsignedBytes).in(with(SporkData::setManifestCopies))
			.use(unsignedBytes).in(with(SporkData::setPlacementSlack))
			.use(unsignedBytes).in(with(SporkData::setRepairThresholdPercent))
			.use(unsignedBytes).in(with(SporkData::setExtraPoolPercent))
			.build();
	}

	@Provide
	public Arbitrary<SporkData> validatedSporkData() {
		return StorageSporkTest.sporkDataAcrossTheBounds().filter(StorageSporkTest::accepts);
	}

	@SneakyThrows
	private static void assertRoundTrip(SporkData data) {
		final ByteBuf buffer = Unpooled.buffer();

		new StorageSporkEncoder().encodeChunk(null, data, buffer);
		assertThat(buffer.readableBytes(), equalTo(ENCODED_SIZE));
		assertThat(new StorageSporkDecoder().decodeChunk(null, buffer).orElseThrow(), equalTo(data));
		assertThat(buffer.readableBytes(), equalTo(0));
	}

	@Property
	public void survivesARoundTripInAFixedSize(@ForAll("wireWideSporkData") SporkData data) {
		assertRoundTrip(data);
	}

	@Property
	public void survivesARoundTripWheneverValid(@ForAll("validatedSporkData") SporkData data) {
		assertRoundTrip(data);
	}
}
