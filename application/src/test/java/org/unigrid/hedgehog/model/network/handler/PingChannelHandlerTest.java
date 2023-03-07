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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import mockit.Mocked;
import mockit.Tested;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.constraints.ByteRange;
import net.jqwik.api.Property;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.model.network.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.model.network.schedule.PingSchedule;
import org.unigrid.hedgehog.server.TestServer;

public class PingChannelHandlerTest extends BaseHandlerTest<Ping, PingChannelHandler> {
	public PingChannelHandlerTest() {
		super(PingChannelHandler.class);
	}

	@Property(tries = 50)
	public void shoulBeAbleToPingNetwork(@ForAll("provideTestServers") List<TestServer> servers,
		@ForAll @ByteRange(min = 3, max = 5) byte pingsPerServer,
		@Mocked PingSchedule pingSchedule) throws Exception {

		final AtomicInteger invocations = new AtomicInteger();
		int expectedInvocations = 0;

		setChannelCallback(Optional.of((ctx, ping) -> {
			/* Only count triggers on the server-side  */
			if (RegisterQuicChannelInitializer.Type.SERVER.is(ctx.channel())) {
				invocations.incrementAndGet();
			}
		}));

		for (TestServer server : servers) {
			final String host = server.getP2p().getHostName();
			final int port = server.getP2p().getPort();
			final Connection connection = new P2PClient(host, port);

			for (int i = 0; i < pingsPerServer; i++) {
				connection.send(Ping.builder().build());
				expectedInvocations++;
			}

			await().untilAtomic(invocations, is(expectedInvocations));
			connection.closeDirty();
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
