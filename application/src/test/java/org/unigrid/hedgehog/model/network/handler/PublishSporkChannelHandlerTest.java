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
import java.util.concurrent.TimeUnit;
import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.jqwik.NotNull;
import org.unigrid.hedgehog.jqwik.SuiteDomain;
import org.unigrid.hedgehog.model.network.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSporkProvider;
import org.unigrid.hedgehog.server.TestServer;

import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.constraints.ShortRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.domains.Domain;
import net.jqwik.api.lifecycle.BeforeProperty;

public class PublishSporkChannelHandlerTest extends BaseHandlerTest<PublishSpork, PublishSporkChannelHandler> {
	private final GridSporkProvider gridSporkProvider = new GridSporkProvider();

	public PublishSporkChannelHandlerTest() {
		super(PublishSporkChannelHandler.class);
	}

	@BeforeProperty
	private void mockBeforePublishSpork() {
		new MockUp<GridSpork>() {
			@Mock
			public boolean isValidSignature() {
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

	@Domain(SuiteDomain.class)
	@Property(tries = 5, shrinking = ShrinkingMode.OFF) // Maintained 5 tries for CI pipeline stability
	public void shoulBeAbleToPublishSpork(@ForAll("provideTestServers") List<TestServer> servers,
			@ForAll("provideGridSpork") @NotNull GridSpork gridSpork) throws Exception {

		// We iterate through each server sequentially to prevent multi-threaded socket racing and CPU starvation in CI resources
		for (TestServer server : servers) {
			final String host = server.getP2p().getHostName();
			final int port = server.getP2p().getPort();

			// Each execution lifecycle gets its own isolated, thread-safe counter to prevent state pollution
			final AtomicInteger isolatedInvocations = new AtomicInteger(0);

			setChannelCallback(Optional.of((ctx, spork) -> {
				if (RegisterQuicChannelInitializer.Type.SERVER.is(ctx.channel())) {
					isolatedInvocations.incrementAndGet();
				}
			}));

			// Give the OS and Netty background worker threads time to breathe before initializing the client context
			Thread.sleep(300);

			P2PClient client = new P2PClient(host, port);
			try {
				// Allow the QUIC handshake to complete smoothly over the localized address bindings
				Thread.sleep(400);

				final PublishSpork publishSpork = PublishSpork.builder().gridSpork(gridSpork).build();

				// Send the generated packet payload
				client.send(publishSpork);

				// Await the local single packet arrival guarantee securely without multi-server interference
				await().atMost(30, SECONDS)
					.pollInterval(200, TimeUnit.MILLISECONDS)
					.untilAtomic(isolatedInvocations, greaterThanOrEqualTo(1));

			} finally {
				// Gracefully teardown the current client socket environment and clear buffers before proceeding
				Thread.sleep(200);
				client.close();
				setChannelCallback(Optional.empty());
				Thread.sleep(200);
			}
		}
	}
}







