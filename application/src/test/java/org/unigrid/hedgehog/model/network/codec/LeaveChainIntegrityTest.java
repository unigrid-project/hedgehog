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
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import java.util.Arrays;
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
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class LeaveChainIntegrityTest extends BaseCodecTest<LeaveChain> {
	private static final int PAYLOAD_BYTES = ChainId.SIZE + WriterId.SIZE;

	@Provide
	public Arbitrary<ChainId> chainIds() {
		return ChainArbitraries.chainIds();
	}

	@Provide
	public Arbitrary<WriterId> writers() {
		return ChainArbitraries.writers();
	}

	@Property(tries = 30)
	public void leaveChain(@ForAll("chainIds") ChainId chainId, @ForAll("writers") WriterId writer,
		@Mocked ChannelHandlerContext context) throws Exception {

		final LeaveChain packet = LeaveChain.builder().chainId(chainId).writer(writer).build();

		assertThat(encodeDecode(packet, new LeaveChainEncoder(), new LeaveChainDecoder(), context), equalTo(packet));
	}

	@Property(tries = 30)
	public void decoderRefusesTruncatedIds(@ForAll @IntRange(max = PAYLOAD_BYTES - 1) int length,
		@Mocked ChannelHandlerContext context) {

		final ByteBuf in = Unpooled.buffer().writeZero(length);

		assertThrows(IndexOutOfBoundsException.class, () -> new LeaveChainDecoder().typedDecode(context, in));
	}

	@Example
	public void carriesItsPacketType() {
		assertThat(LeaveChain.builder().build().getType(), equalTo(Packet.Type.LEAVE_CHAIN));
		assertThat(Packet.Type.LEAVE_CHAIN.getValue(), equalTo((short) 5060));
		assertThat(Packet.Type.get((short) 5060), equalTo(Packet.Type.LEAVE_CHAIN));
		assertThat(new LeaveChainEncoder().getCodecType(), equalTo(Packet.Type.LEAVE_CHAIN));
		assertThat(new LeaveChainDecoder().getCodecType(), equalTo(Packet.Type.LEAVE_CHAIN));
	}

	@Example
	public void keepsTheWireLayoutOfDeployedNodes() throws Exception {
		final ByteBuf out = new LeaveChainEncoder().encode(null, LeaveChain.builder().chainId(filledChainId())
			.writer(filledWriter()).build()).orElseThrow();

		assertThat(out.readableBytes(), equalTo(64));
		assertThat(ByteBufUtil.getBytes(out, 0, ChainId.SIZE), equalTo(filledChainId().bytes()));
		assertThat(ByteBufUtil.getBytes(out, ChainId.SIZE, WriterId.SIZE), equalTo(filledWriter().bytes()));
	}

	@Example
	public void decoderReadsTheChainBeforeTheWriter(@Mocked ChannelHandlerContext context) throws Exception {
		final ByteBuf in = Unpooled.buffer().writeBytes(filledChainId().bytes()).writeBytes(filledWriter().bytes());
		final LeaveChain decoded = new LeaveChainDecoder().typedDecode(context, in).orElseThrow();

		assertThat(decoded.getChainId(), equalTo(filledChainId()));
		assertThat(decoded.getWriter(), equalTo(filledWriter()));
	}

	@Example
	public void decoderTakesExactlyBothIdsAndRefusesOneByteLess(@Mocked ChannelHandlerContext context)
		throws Exception {

		final ByteBuf exact = Unpooled.buffer().writeZero(PAYLOAD_BYTES);
		final ByteBuf oneShort = Unpooled.buffer().writeZero(PAYLOAD_BYTES - 1);

		assertThat(new LeaveChainDecoder().typedDecode(context, exact).isPresent(), equalTo(true));
		assertThat(exact.readableBytes(), equalTo(0));
		assertThrows(IndexOutOfBoundsException.class, () -> new LeaveChainDecoder().typedDecode(context, oneShort));
	}

	private static ChainId filledChainId() {
		final byte[] bytes = new byte[ChainId.SIZE];

		Arrays.fill(bytes, (byte) 1);
		return new ChainId(bytes);
	}

	private static WriterId filledWriter() {
		final byte[] bytes = new byte[WriterId.SIZE];

		Arrays.fill(bytes, (byte) 2);
		return new WriterId(bytes);
	}
}
