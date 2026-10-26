/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import mockit.Mocked;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class JoinChainIntegrityTest extends BaseCodecTest<JoinChain> {
	@Provide
	public Arbitrary<WriterId> writers() {
		return ChainArbitraries.writers();
	}

	@Provide
	public Arbitrary<ChainId> anyOrOneChain() {
		return ChainArbitraries.chainIds().injectNull(0.5);
	}

	@Property(tries = 30)
	public void joinChain(@ForAll long requestId, @ForAll("writers") WriterId writer,
		@ForAll("anyOrOneChain") ChainId chainId, @Mocked ChannelHandlerContext context) throws Exception {

		final JoinChain packet = JoinChain.builder().requestId(requestId).writer(writer).chainId(chainId).build();

		assertThat(encodeDecode(packet, new JoinChainEncoder(), new JoinChainDecoder(), context), equalTo(packet));
	}

	@Property(tries = 30)
	public void decoderRefusesPresenceFlagsOtherThanZeroOrOne(@ForAll long requestId,
		@ForAll @IntRange(min = 2, max = 0xFF) int flag, @Mocked ChannelHandlerContext context) {

		final ByteBuf in = Unpooled.buffer().writeLong(requestId).writeZero(WriterId.SIZE).writeByte(flag)
			.writeZero(ChainId.SIZE);

		assertThrows(IllegalArgumentException.class, () -> new JoinChainDecoder().typedDecode(context, in));
	}

	@Example
	public void carriesItsPacketType() {
		assertThat(JoinChain.builder().build().getType(), equalTo(Packet.Type.JOIN_CHAIN));
		assertThat(Packet.Type.JOIN_CHAIN.getValue(), equalTo((short) 5000));
	}

	@Example
	public void decoderRefusesTheBoundaryPresenceFlags(@Mocked ChannelHandlerContext context) {
		for (int flag : new int[] { 2, 0xFF }) {
			final ByteBuf in = Unpooled.buffer().writeLong(0).writeZero(WriterId.SIZE).writeByte(flag)
				.writeZero(ChainId.SIZE);

			assertThrows(IllegalArgumentException.class, () -> new JoinChainDecoder().typedDecode(context, in));
		}
	}
}
