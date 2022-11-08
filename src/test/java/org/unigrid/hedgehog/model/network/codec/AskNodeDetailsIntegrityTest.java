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
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static com.shazam.shazamcrest.matcher.Matchers.*;
import org.unigrid.hedgehog.model.network.packet.AskNodeDetails;
import org.unigrid.hedgehog.model.network.packet.Packet;

public class AskNodeDetailsIntegrityTest extends BaseCodecTest<AskNodeDetails> {
	@Provide
	public Arbitrary<AskNodeDetails> provideAskNodeDetails(@ForAll boolean protocol, @ForAll boolean version) {
		final AskNodeDetails askNodeDetails = AskNodeDetails.builder().protocol(protocol).version(version).build();
		askNodeDetails.setType(Packet.Type.ASK_NODE_DETAILS);
		return Arbitraries.of(askNodeDetails);
	}

	@Property
	@SneakyThrows
	public void shouldMatch(@ForAll("provideAskNodeDetails") AskNodeDetails askNodeDetails,
		@Mocked ChannelHandlerContext context) {

		final AskNodeDetails resultingAskNodeDetails = encodeDecode(askNodeDetails,
			new AskNodeDetailsEncoder(), new AskNodeDetailsDecoder(), context
		);

		assertThat(resultingAskNodeDetails, sameBeanAs(askNodeDetails));
	}
}
