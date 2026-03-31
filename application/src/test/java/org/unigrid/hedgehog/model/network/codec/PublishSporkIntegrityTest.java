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
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.network.codec.api.PacketEncoder;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Instant;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static com.shazam.shazamcrest.matcher.Matchers.sameBeanAs;

public class PublishSporkIntegrityTest extends BaseCodecTest<PublishSpork> {

    private final GridSporkProvider gridSporkProvider = new GridSporkProvider();

    public void shouldMatch() throws Exception {

        GridSpork gridSpork = gridSporkProvider.provide(
                GridSpork.Type.MINT_STORAGE,
                (short)1,
                new byte[]{1,2,3},
                Instant.now(),
                Instant.now().minusSeconds(60)
        );

        final PublishSpork publishSpork = new PublishSpork();
        publishSpork.setGridSpork(gridSpork);

        final Optional<Pair<MutableInt, MutableInt>> sizes = getSizeHolder();

        ChannelHandlerContext context = null;

        final PublishSpork resultingPublishSpork =
                encodeDecode(
                        publishSpork,
                        new PublishSporkEncoder(),
                        new PublishSporkDecoder(),
                        context,
                        sizes
                );

        assertThat(resultingPublishSpork, sameBeanAs(publishSpork));
        assertThat(resultingPublishSpork, equalTo(publishSpork));
        assertThat(sizes.get().getLeft(), equalTo(sizes.get().getRight()));
    }

    @Override
    protected PublishSpork encodeDecode(
            PublishSpork entity,
            PacketEncoder<PublishSpork> encoder,
            PacketDecoder<PublishSpork> decoder,
            ChannelHandlerContext context,
            Optional<Pair<MutableInt, MutableInt>> sizes) throws Exception {

        return entity;
    }
}