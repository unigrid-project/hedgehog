/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

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

import io.netty.channel.ChannelHandlerContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.ShortRange;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static com.shazam.shazamcrest.matcher.Matchers.*;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.MintSupply;

public class PublishSporkIntegrityTest extends BaseCodecTest<PublishSpork> {
	private ChunkData chunkData(GridSpork.Type gridSporkType) {
		switch (gridSporkType) {
			case MINT_STORAGE: {
				final MintStorage.SporkData data = new MintStorage.SporkData();
				final Map<Location, BigDecimal> mints = new HashMap<>();
				final int size = RandomUtils.nextInt(0, 50);

				for (int i = 0; i < size; i++) {
					final Address address = Address.builder()
						.wif(RandomStringUtils.randomAlphanumeric(40)).build();

					final Location location = Location.builder()
						.address(address).height(RandomUtils.nextInt()).build();

					mints.put(location, BigDecimal.valueOf(RandomUtils.nextInt()));
				}

				data.setMints(mints);
				return data;

			} case MINT_SUPPLY: {
				final MintSupply.SporkData data = new MintSupply.SporkData();
				data.setMaxSupply(BigDecimal.valueOf(RandomUtils.nextInt()));
				return data;

			} case VESTING_STORAGE: {
			}
		}

		return null;
	}

	@Provide
	public Arbitrary<?> providePublishSpork(@ForAll GridSpork.Type gridSporkType,
		@ForAll @ShortRange(min = 0, max = 3) short flags, @ForAll @Size(min = 50, max = 60) byte[] signature,
		@ForAll Instant time, @ForAll Instant previousTime) {

		try {
			final GridSpork gridSpork = GridSpork.create(GridSpork.Type.MINT_STORAGE);
			gridSpork.setTimeStamp(time);
			gridSpork.setPreviousTimeStamp(previousTime);
			//gridSpork.setSignatureData(signature);
			gridSpork.setData(chunkData(GridSpork.Type.MINT_STORAGE));

			return Arbitraries.of(PublishSpork.builder().gridSpork(gridSpork).build());

		} catch (IllegalArgumentException ex) {
			assertThat(gridSporkType, is(GridSpork.Type.UNDEFINED));
		}

		return Arbitraries.nothing();
	}

	@Property
	@SneakyThrows
	public void shouldMatch(@ForAll("providePublishSpork") PublishSpork publishSpork,
		@Mocked ChannelHandlerContext context) {

		if (Objects.nonNull(publishSpork)) {
			final PublishSpork resultingPublishSpork = encodeDecode(publishSpork,
				new PublishSporkEncoder(), new PublishSporkDecoder(), context
			);

			assertThat(resultingPublishSpork, sameBeanAs(publishSpork));
		}
	}
}
