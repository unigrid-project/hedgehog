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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.ChannelHandlerContext;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import mockit.Mocked;
import mockit.Tested;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.constraints.Positive;
import net.jqwik.api.Property;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.server.BaseServerTest;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class PingChannelHandlerTest extends BaseServerTest {
	@Property(tries = 15)
	public void shoulBeAbleToPingNetwork(@ForAll @Positive byte pingsPerServer) throws Exception {
		final AtomicInteger actualInvocations = new AtomicInteger();
		int expectedInvocations = 0;

		for (TestServer server : servers) {
			final String host = server.getP2p().getHostName();
			final int port = server.getP2p().getPort();
			final P2PClient client = new P2PClient(host, port);

			for (int i = 0; i < pingsPerServer; i++) {
				client.send(Ping.builder().build()).addListener(outcome -> {
					assertThat(outcome.isSuccess(), equalTo(true));
					actualInvocations.incrementAndGet();
				});

				expectedInvocations++;
			}

			if (Objects.nonNull(client)) {
				client.close();
			}
		}

		await().untilAtomic(actualInvocations, is(expectedInvocations));
	}

	@Example
	@SneakyThrows
	public void shouldSetResponseFlagOnResponse(@Mocked ChannelHandlerContext context, @Tested PingChannelHandler handler) {
		final Ping ping = Ping.builder().build();
		assertThat(ping.getNanoTime(), not(0));
		assertThat(ping.isResponse(), is(false));

		handler.typedChannelRead(context, ping);
		assertThat(ping.isResponse(), is(true));
	}
}
