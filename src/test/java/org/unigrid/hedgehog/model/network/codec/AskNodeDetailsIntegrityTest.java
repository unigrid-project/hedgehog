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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.List;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.model.network.packet.AskNodeDetails;

public class AskNodeDetailsIntegrityTest extends BaseMockedWeldTest {
	private String ip() {
		final int ip[] = new int[4];

		for (int i = 0; i < ip.length; i++) {
			ip[i] = Arbitraries.integers().between(0, 255).sample();
		}

		return String.format("%d.%d.%d.%d", ip[0], ip[1], ip[2], ip[3]);
	}

	@Provide
	public Arbitrary<AskNodeDetails> provideAskNodeDetails(@ForAll boolean protocol, @ForAll boolean version) {
		final AskNodeDetails askNodeDetails = new AskNodeDetails();

		askNodeDetails.setProtocol(protocol);
		askNodeDetails.setVersion(version);
		return Arbitraries.of(askNodeDetails);
	}

	@Property
	@SneakyThrows
	public void shouldMatch(@ForAll("provideAskNodeDetails") AskNodeDetails askNodeDetails,
		@Mocked ChannelHandlerContext context) {

		final List<Object> out = new ArrayList<>();
		final AskNodeDetailsDecoder decoder = new AskNodeDetailsDecoder();
		final AskNodeDetailsEncoder encoder = new AskNodeDetailsEncoder();
		final ByteBuf encodedData = Unpooled.buffer();

		encoder.encode(context, askNodeDetails, encodedData);
		decoder.decode(context, encodedData, out);
		assertThat(out.get(0), is(askNodeDetails));
	}
}
