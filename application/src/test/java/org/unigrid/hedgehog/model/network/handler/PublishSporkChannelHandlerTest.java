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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.ShortRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.domains.Domain;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.jqwik.NotNull;
import org.unigrid.hedgehog.jqwik.SuiteDomain;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSporkProvider;
import org.unigrid.hedgehog.server.TestServer;

public class PublishSporkChannelHandlerTest extends BaseHandlerTest<PublishSpork, PublishSporkChannelHandler> {
	private final GridSporkProvider gridSporkProvider = new GridSporkProvider();

	public PublishSporkChannelHandlerTest() {
		super(PublishSporkChannelHandler.class);
	}

	@BeforeProperty
	private void mockBeforePublishSpork() {
		new MockUp<GridSpork>() {
			@Mock public boolean isValidSignature() {
				return true;
			}
		};
	}

	@Provide(ignoreExceptions = IllegalArgumentException.class)
	public Arbitrary<GridSpork> provideGridSpork(@ForAll GridSpork.Type gridSporkType,
		@ForAll @ShortRange(min = 0, max = 3) short flags, @ForAll @Size(value = 60) byte[] signature,
		@ForAll Instant time, @ForAll Instant previousTime) {

		return gridSporkProvider.provide(gridSporkType, flags, signature, time, previousTime);
	}

	@Property(tries = 50)
	@Domain(SuiteDomain.class)
	public void shoulBeAbleToPublishSpork(@ForAll("provideTestServers") List<TestServer> servers,
		@ForAll("provideGridSpork") @NotNull GridSpork gridSpork) throws Exception {

		final AtomicInteger invocations = new AtomicInteger();
		int expectedInvocations = 0;

		setChannelCallback(Optional.of((ctx, spork) -> {
			/* Only count triggers on the server-side */
			if (RegisterQuicChannelInitializer.Type.SERVER.is(ctx.channel())) {
				invocations.incrementAndGet();
			}
		}));

		for (TestServer server : servers) {
			final String host = server.getP2p().getHostName();
			final int port = server.getP2p().getPort();
			final Connection connection = new P2PClient(host, port);
			final PublishSpork publishSpork = PublishSpork.builder().gridSpork(gridSpork).build();

			connection.send(publishSpork);
			expectedInvocations++;

			await().untilAtomic(invocations, is(expectedInvocations));
			connection.closeDirty();
		}

		await().untilAtomic(invocations, is(expectedInvocations));
	}
}
