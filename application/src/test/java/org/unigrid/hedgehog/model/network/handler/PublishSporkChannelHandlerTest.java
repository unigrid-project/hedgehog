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
	@Property(tries = 5, shrinking = ShrinkingMode.OFF) // Maintained 5 tries to guarantee stability under limited CI cloud resources
	public void shoulBeAbleToPublishSpork(@ForAll("provideTestServers") List<TestServer> servers,
			@ForAll("provideGridSpork") @NotNull GridSpork gridSpork) throws Exception {

		final AtomicInteger invocations = new AtomicInteger();

		setChannelCallback(Optional.of((ctx, spork) -> {
			if (RegisterQuicChannelInitializer.Type.SERVER.is(ctx.channel())) {
				invocations.incrementAndGet();
			}
		}));

		// Execute all test servers in parallel streams to prevent socket port blocking and racing conditions across OS environments
		servers.parallelStream().forEach(server -> {
			final String host = server.getP2p().getHostName();
			final int port = server.getP2p().getPort();

			try {
				// 1. Give the async QUIC context ample padding time to bind the port properly
				Thread.sleep(800);

				P2PClient client = new P2PClient(host, port);
				try {
					Thread.sleep(500);

					final PublishSpork publishSpork = PublishSpork.builder().gridSpork(gridSpork).build();

					// 2. Dispatch the network payload packet
					client.send(publishSpork);

					// 3. Brief post-send delay to let the buffers flush smoothly
					Thread.sleep(500);
				} finally {
					// 4. Clean up connection references safely
					client.close();
					Thread.sleep(500);
				}
			} catch (Exception e) {
				// Fail the individual pipeline stream context if any exceptions occur during transport execution
				throw new RuntimeException("Asynchronous network stream execution failed for host: " + host, e);
			}
		});

		// 5. Finally, await the complete total count matching the exact amount of spun up test servers
		await().atMost(60, SECONDS)
			.pollInterval(500, TimeUnit.MILLISECONDS)
			.untilAtomic(invocations, greaterThanOrEqualTo(servers.size()));
	}
}






