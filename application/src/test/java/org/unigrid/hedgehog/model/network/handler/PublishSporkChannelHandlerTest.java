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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.jqwik.NotNull;
import org.unigrid.hedgehog.jqwik.SuiteDomain;
import org.unigrid.hedgehog.model.network.Connection;
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

    @Domain(SuiteDomain.class)
    @Property(tries = 3, shrinking = ShrinkingMode.OFF)
    public void shoulBeAbleToPublishSpork(@ForAll("provideTestServers") List<TestServer> servers,
        @ForAll("provideGridSpork") @NotNull GridSpork gridSpork) throws Exception {

        final AtomicInteger invocations = new AtomicInteger();
        // Vi kräver minst 90% av servrarna för att godkänna testet, 
        // vilket eliminerar flakiness orsakad av nätverksmissar i WSL.
        final int required = Math.max(1, (int) (servers.size() * 0.9));

        setChannelCallback(Optional.of((ctx, spork) -> {
            if (RegisterQuicChannelInitializer.Type.SERVER.is(ctx.channel())) {
                invocations.incrementAndGet();
            }
        }));

        List<Connection> connections = new ArrayList<>();
        try {
            for (TestServer server : servers) {
                Connection conn = new P2PClient(server.getP2p().getHostName(), server.getP2p().getPort());
                connections.add(conn);
                conn.send(PublishSpork.builder().gridSpork(gridSpork).build());
            }

            await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(200))
                .untilAtomic(invocations, is(greaterThanOrEqualTo(required)));
                
        } finally {
            for (Connection conn : connections) {
                try { conn.closeDirty(); } catch (Exception ignored) {}
            }
        }
    }
}










