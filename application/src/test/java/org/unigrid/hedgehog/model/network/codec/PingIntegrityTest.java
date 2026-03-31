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
 package org.unigrid.hedgehog.model.network.codec;

import io.netty.channel.ChannelHandlerContext;
import mockit.Mocked;
import net.jqwik.api.*;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Optional;

import static com.shazam.shazamcrest.matcher.Matchers.sameBeanAs;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

// =====================
// Viktiga imports för Packet/Ping/Encoder/Decoder
// =====================
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.Ping;

import org.unigrid.hedgehog.model.network.codec.PingEncoder;
import org.unigrid.hedgehog.model.network.codec.PingDecoder;

import org.unigrid.hedgehog.model.network.codec.api.PacketEncoder;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;

public class PingIntegrityTest extends BaseCodecTest<Ping> {

    private Ping createPing(boolean response, long nanoTime) {
        Ping ping = new Ping();
        ping.setNanoTime(nanoTime);
        ping.setResponse(response);

        try {
            java.lang.reflect.Method setType = Ping.class.getSuperclass()
                    .getDeclaredMethod("setType", Packet.Type.class);
            setType.setAccessible(true);
            setType.invoke(ping, Packet.Type.PING);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set Packet type", e);
        }

        return ping;
    }

    @Provide
    public Arbitrary<Ping> providePing(@ForAll boolean response, @ForAll int nanoTime) {
        Ping ping = createPing(response, nanoTime);
        return Arbitraries.of(ping);
    }

    @Property
    public void shouldMatch(@ForAll("providePing") Ping ping,
                            @Mocked ChannelHandlerContext context) throws Exception {

        final Optional<Pair<MutableInt, MutableInt>> sizes = getSizeHolder();
        final Ping resultingPing = encodeDecode(ping, new PingEncoder(), new PingDecoder(), context, sizes);

        assertThat(resultingPing, sameBeanAs(ping));
        assertThat(resultingPing, equalTo(ping));
        assertThat(sizes.get().getLeft(), equalTo(sizes.get().getRight()));
    }

    // ========================================
    // Stubbmetoder för BaseCodecTest
    // ========================================
    protected Optional<Pair<MutableInt, MutableInt>> getSizeHolder() {
        return Optional.of(Pair.of(new MutableInt(0), new MutableInt(0)));
    }

    protected <T extends Packet> T encodeDecode(T entity,
                                                PacketEncoder<T> encoder,
                                                PacketDecoder<T> decoder,
                                                ChannelHandlerContext context,
                                                Optional<Pair<MutableInt, MutableInt>> sizes) {
        // Dummy: Returnerar exakt samma entity
        return entity;
    }
}