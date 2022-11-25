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
import net.jqwik.api.constraints.Positive;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static com.shazam.shazamcrest.matcher.Matchers.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.Ping;

public class PingIntegrityTest extends BaseCodecTest<Ping> {
	@Provide
	public Arbitrary<Ping> providePing(@ForAll boolean response, @ForAll @Positive int nanoTime) {
		final Ping ping = Ping.builder().nanoTime(nanoTime).response(response).build();
		ping.setType(Packet.Type.PING);
		return Arbitraries.of(ping);
	}

	@Property
	@SneakyThrows
	public void shouldMatch(@ForAll("providePing") Ping ping, @Mocked ChannelHandlerContext context) {
		final Ping resultingPing = encodeDecode(ping, new PingEncoder(), new PingDecoder(), context);

		assertThat(resultingPing, sameBeanAs(ping));
		assertThat(resultingPing, equalTo(ping));
	}
}
