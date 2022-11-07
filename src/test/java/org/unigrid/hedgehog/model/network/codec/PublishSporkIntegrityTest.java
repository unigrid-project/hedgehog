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
import java.time.Instant;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;

public class PublishSporkIntegrityTest extends BaseCodecTest<PublishSpork> {
	@Provide
	public Arbitrary<?> providePublishSpork(@ForAll GridSpork.Type gridSporkType, @ForAll short flags,
		/*@ForAll @NotEmpty byte[] signature,*/ @ForAll Instant previousTime) {

		try {
			final GridSpork gridSpork = GridSpork.create(GridSpork.Type.MINT_STORAGE);

			gridSpork.setFlags(flags);
			gridSpork.setTimeStamp(Instant.now());
			gridSpork.setPreviousTimeStamp(previousTime);
			//gridSpork.setSignatureData(signature);
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

		if (publishSpork != null) {
			System.out.println(publishSpork.getGridSpork().getType());
			final PublishSpork resultingPublishSpork = encodeDecode(publishSpork,
				new PublishSporkEncoder(), new PublishSporkDecoder(), context
			);

			assertThat(resultingPublishSpork, is(publishSpork));
		}
	}
}
