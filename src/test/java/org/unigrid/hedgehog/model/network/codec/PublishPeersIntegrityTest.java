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
import java.net.UnknownHostException;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Positive;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static com.shazam.shazamcrest.matcher.Matchers.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import java.net.InetSocketAddress;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.packet.PublishPeers;

public class PublishPeersIntegrityTest extends BaseCodecTest<PublishPeers> {
	private String ip() {
		final int ip[] = new int[4];

		for (int i = 0; i < ip.length; i++) {
			ip[i] = Arbitraries.integers().between(0, 255).sample();
		}

		return String.format("%d.%d.%d.%d", ip[0], ip[1], ip[2], ip[3]);
	}

	@Provide
	public Arbitrary<PublishPeers> providePublishPeers(@ForAll @Positive byte nodes,
		@ForAll @IntRange(min = 4097, max = 65535) int port) throws UnknownHostException {

		final PublishPeers pp = PublishPeers.builder().build();

		for (int i = 0; i < nodes; i++) {
			final InetSocketAddress socketAddress = InetSocketAddress.createUnresolved(ip(), port);
			final Node node = Node.builder().address(socketAddress).build();
			pp.getNodes().add(node);
		}

		return Arbitraries.of(pp);
	}

	@Property
	@SneakyThrows
	public void shouldMatch(@ForAll("providePublishPeers") PublishPeers publishPeers,
		@Mocked ChannelHandlerContext context) {

		final PublishPeers resultingPublishPeers = encodeDecode(publishPeers,
			new PublishPeersEncoder(), new PublishPeersDecoder(), context
		);

		assertThat(resultingPublishPeers, sameBeanAs(publishPeers));
		assertThat(resultingPublishPeers, equalTo(publishPeers));
	}
}
