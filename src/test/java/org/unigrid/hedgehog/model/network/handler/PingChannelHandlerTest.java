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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import mockit.Mocked;
import mockit.Tested;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.constraints.ByteRange;
import net.jqwik.api.Property;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.network.packet.Ping;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.server.BaseServerChannelTest;

public class PingChannelHandlerTest extends BaseServerChannelTest<Ping, PingChannelHandler> {
	public PingChannelHandlerTest() {
		super(PingChannelHandler.class);
	}

	@Property
	public void shoulBeAbleToPingNetwork(@ForAll @ByteRange(min = 2, max = 5) byte pingsPerServer) throws Exception {
		final AtomicInteger invocations = new AtomicInteger();
		int expectedInvocations = 0;

		setChannelCallback(Optional.of((ctx, ping) -> {
			/* Only count triggers on the server-side  */
			if (RegisterQuicChannelHandler.Type.SERVER.is(ctx.channel())) {
				invocations.incrementAndGet();
			}
		}));

		for (TestServer server : servers) {
			final String host = server.getP2p().getHostName();
			final int port = server.getP2p().getPort();
			final P2PClient client = new P2PClient(host, port);

			for (int i = 0; i < pingsPerServer; i++) {
				client.send(Ping.builder().build());
				expectedInvocations++;
			}

			await().untilAtomic(invocations, is(expectedInvocations));
			client.close();
		}

		await().untilAtomic(invocations, is(expectedInvocations));
	}

	@Example
	@SneakyThrows
	public void shouldSetResponseFlagOnResponse(@Mocked ChannelHandlerContext context,
		@Tested PingChannelHandler handler) {

		final Ping ping = Ping.builder().build();
		assertThat(ping.getNanoTime(), not(0));
		assertThat(ping.isResponse(), is(false));

		handler.typedChannelRead(context, ping);
		assertThat(ping.isResponse(), is(true));
	}
}
