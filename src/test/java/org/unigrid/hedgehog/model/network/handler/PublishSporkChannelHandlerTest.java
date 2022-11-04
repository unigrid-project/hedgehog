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
import java.time.Instant;
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
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;

public class PublishSporkChannelHandlerTest extends BaseServerTest {
	@Example
	public void shoulBeAbleToPingNetwork() throws Exception {
		final AtomicInteger actualInvocations = new AtomicInteger();
		//int expectedInvocations = 0;

		for (TestServer server : servers) {
			final String host = server.getP2p().getHostName();
			final int port = server.getP2p().getPort();
			final P2PClient client = new P2PClient(host, port);
			final GridSpork gridSpork = GridSpork.create(GridSpork.Type.MINT_STORAGE);
			final PublishSpork publishSpork = PublishSpork.builder().gridSpork(gridSpork).build();

			gridSpork.setTimeStamp(Instant.now());
			gridSpork.setPreviousTimeStamp(Instant.now());

			client.send(publishSpork).addListener(outcome -> {
				/*assertThat(outcome.isSuccess(), equalTo(true));
				actualInvocations.incrementAndGet();*/
				System.out.println("outcome1: " + outcome);
				System.out.println("outcome2: " + outcome.get());
			});

			Thread.sleep(500);

			if (Objects.nonNull(client)) {
				client.close();
			}
		}

		//await().untilAtomic(actualInvocations, is(expectedInvocations));
	}
}
