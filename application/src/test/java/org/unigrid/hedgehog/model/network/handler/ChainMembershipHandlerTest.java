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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.model.network.codec.ChainArbitraries;
import org.unigrid.hedgehog.model.network.codec.JoinReplyEncoder;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.service.chain.ChainHost;
import org.unigrid.hedgehog.service.chain.ChainService;

public class ChainMembershipHandlerTest extends BaseMockedWeldTest {
	private record Call(Channel peer, Packet packet) {
		/* One packet a handler handed to the chain service, with the stream it arrived on */
	}

	private final List<Call> calls = new CopyOnWriteArrayList<>();
	private Function<JoinChain, JoinReply> joinAnswer;
	private RuntimeException failure;

	/* Starts the container the handlers resolve the service from */
	@Inject
	private ChainService chainService;

	@Provide
	public Arbitrary<WriterId> writers() {
		return ChainArbitraries.writers();
	}

	@Provide
	public Arbitrary<ChainId> chainIds() {
		return ChainArbitraries.chainIds();
	}

	@Provide
	public Arbitrary<ChainId> anyOrOneChain() {
		return ChainArbitraries.chainIds().injectNull(0.5);
	}

	@BeforeProperty
	private void recordMembership() {
		new MockUp<ChainHost>() {
			@Mock public JoinReply join(Channel peer, JoinChain request) {
				calls.add(new Call(peer, request));
				failWhenAsked();
				return joinAnswer.apply(request);
			}

			@Mock public void leave(Channel peer, LeaveChain leave) {
				calls.add(new Call(peer, leave));
				failWhenAsked();
			}
		};
	}

	@BeforeTry
	private void forgetEarlierTries() {
		calls.clear();
		failure = null;
		joinAnswer = request -> reply(request.getRequestId(), JoinReply.Status.FULL);
	}

	private void failWhenAsked() {
		if (failure != null) {
			throw failure;
		}
	}

	private static JoinReply reply(final long requestId, final JoinReply.Status status) {
		return JoinReply.builder().requestId(requestId).status(status).build();
	}

	private static JoinChain joinOf(final long requestId, final WriterId writer) {
		return JoinChain.builder().requestId(requestId).writer(writer).build();
	}

	private static byte[] filled(final int size, final int value) {
		final byte[] bytes = new byte[size];

		Arrays.fill(bytes, (byte) value);
		return bytes;
	}

	/* A stream that has more written to it than it takes, and so takes no more writes until it is flushed; answers
	   what it holds */
	private static Object unwritable(final EmbeddedChannel channel) {
		final Object held = "Not flushed";

		channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1, 2));
		channel.write(held);
		assertThat(channel.isWritable(), is(false));
		return held;
	}

	@Property(tries = 10)
	public void writesNoReplyToAStreamThatTakesNoWrites(@ForAll long requestId, @ForAll("writers") WriterId writer) {
		final EmbeddedChannel channel = new EmbeddedChannel(new JoinChainChannelHandler());
		final Object held = unwritable(channel);
		final JoinChain request = joinOf(requestId, writer);

		channel.writeInbound(request);

		assertThat(calls, contains(new Call(channel, request)));
		assertThat(channel.outboundMessages(), empty());
		assertThat(channel.isOpen(), is(true));

		channel.flush();
		assertThat(channel.readOutbound(), sameInstance(held));
		assertThat(channel.outboundMessages(), empty());

		channel.writeInbound(request);
		assertThat(channel.readOutbound(), equalTo(reply(requestId, JoinReply.Status.FULL)));
		assertThat(channel.outboundMessages(), empty());
	}

	@Property(tries = 10)
	public void answersAJoinWithTheReplyOfTheService(@ForAll long requestId, @ForAll("writers") WriterId writer,
		@ForAll("anyOrOneChain") ChainId chainId) {

		final EmbeddedChannel channel = new EmbeddedChannel(new JoinChainChannelHandler());
		final JoinChain request = JoinChain.builder().requestId(requestId).writer(writer).chainId(chainId).build();

		channel.writeInbound(request);

		assertThat(calls, contains(new Call(channel, request)));
		assertThat(channel.readOutbound(), equalTo(JoinReply.builder().requestId(requestId)
			.status(JoinReply.Status.FULL).build()));
		assertThat(channel.outboundMessages(), empty());
	}

	@Example
	public void writesTheJoinedReplyOfTheServiceUnchanged() {
		final ChainId chain = new ChainId(filled(ChainId.SIZE, 1));
		final WriterId writer = new WriterId(filled(WriterId.SIZE, 2));
		final JoinReply joined = JoinReply.builder().requestId(Long.MIN_VALUE).status(JoinReply.Status.JOINED)
			.chainId(chain).heads(Map.of(writer, new Head(Long.MAX_VALUE, -1))).build();
		final EmbeddedChannel channel = new EmbeddedChannel(new JoinChainChannelHandler());

		joinAnswer = request -> joined;
		channel.writeInbound(joinOf(Long.MIN_VALUE, writer));

		assertThat(channel.readOutbound(), sameInstance(joined));
		assertThat(channel.outboundMessages(), empty());
	}

	@Property(tries = 10)
	public void answersEachStreamWithItsOwnJoin(@ForAll long first, @ForAll long second,
		@ForAll("writers") WriterId writer) {

		Assume.that(first != second);

		final JoinChainChannelHandler shared = new JoinChainChannelHandler();
		final EmbeddedChannel one = new EmbeddedChannel(shared);
		final EmbeddedChannel other = new EmbeddedChannel(shared);
		final JoinChain toOne = joinOf(first, writer);
		final JoinChain toOther = joinOf(second, writer);

		other.writeInbound(toOther);
		one.writeInbound(toOne);

		assertThat(calls, contains(new Call(other, toOther), new Call(one, toOne)));
		assertThat(one.readOutbound(), equalTo(reply(first, JoinReply.Status.FULL)));
		assertThat(one.outboundMessages(), empty());
		assertThat(other.readOutbound(), equalTo(reply(second, JoinReply.Status.FULL)));
		assertThat(other.outboundMessages(), empty());
	}

	@Property(tries = 10)
	public void closesTheStreamWhenTheServiceFailsOnAJoin(@ForAll long requestId, @ForAll("writers") WriterId writer) {
		final EmbeddedChannel channel = new EmbeddedChannel(new JoinChainChannelHandler());

		failure = new IllegalStateException("The chain service failed");
		channel.writeInbound(joinOf(requestId, writer));

		assertThat(channel.isOpen(), is(false));
		assertThat(channel.outboundMessages(), empty());
	}

	@Property(tries = 10)
	public void closesTheStreamWhenTheReplyCannotBeEncoded(@ForAll long requestId, @ForAll("writers") WriterId writer) {
		final EmbeddedChannel channel = new EmbeddedChannel(new JoinReplyEncoder(), new JoinChainChannelHandler());

		joinAnswer = request -> reply(request.getRequestId(), JoinReply.Status.JOINED);
		channel.writeInbound(joinOf(requestId, writer));

		assertThat(channel.isOpen(), is(false));
		assertThat(channel.outboundMessages(), empty());
	}

	@Property(tries = 10)
	public void keepsTheStreamOpenWhenTheReplyIsEncoded(@ForAll long requestId, @ForAll("writers") WriterId writer) {
		final EmbeddedChannel channel = new EmbeddedChannel(new JoinReplyEncoder(), new JoinChainChannelHandler());

		channel.writeInbound(joinOf(requestId, writer));

		final ByteBuf encoded = channel.readOutbound();

		assertThat(channel.isOpen(), is(true));
		assertThat(encoded, notNullValue());
		assertThat(channel.outboundMessages(), empty());
		encoded.release();
	}

	@Property(tries = 10)
	public void handsALeaveToTheServiceWithoutAnswering(@ForAll("chainIds") ChainId chainId,
		@ForAll("writers") WriterId writer) {

		final EmbeddedChannel channel = new EmbeddedChannel(new LeaveChainChannelHandler());
		final LeaveChain leave = LeaveChain.builder().chainId(chainId).writer(writer).build();

		channel.writeInbound(leave);

		assertThat(calls, contains(new Call(channel, leave)));
		assertThat(channel.outboundMessages(), empty());
	}

	@Property(tries = 10)
	public void handsEachStreamItsOwnLeave(@ForAll("chainIds") ChainId chainId, @ForAll("writers") WriterId first,
		@ForAll("writers") WriterId second) {

		final LeaveChainChannelHandler shared = new LeaveChainChannelHandler();
		final EmbeddedChannel one = new EmbeddedChannel(shared);
		final EmbeddedChannel other = new EmbeddedChannel(shared);
		final LeaveChain fromOne = LeaveChain.builder().chainId(chainId).writer(first).build();
		final LeaveChain fromOther = LeaveChain.builder().chainId(chainId).writer(second).build();

		other.writeInbound(fromOther);
		one.writeInbound(fromOne);

		assertThat(calls, contains(new Call(other, fromOther), new Call(one, fromOne)));
		assertThat(one.outboundMessages(), empty());
		assertThat(other.outboundMessages(), empty());
	}

	@Property(tries = 10)
	public void closesTheStreamWhenTheServiceFailsOnALeave(@ForAll("chainIds") ChainId chainId,
		@ForAll("writers") WriterId writer) {

		final EmbeddedChannel channel = new EmbeddedChannel(new LeaveChainChannelHandler());

		failure = new IllegalStateException("The chain service failed");
		channel.writeInbound(LeaveChain.builder().chainId(chainId).writer(writer).build());

		assertThat(channel.isOpen(), is(false));
		assertThat(channel.outboundMessages(), empty());
	}

	@Property(tries = 10)
	public void passesOtherPacketsOn(@ForAll("chainIds") ChainId chainId) {
		final EmbeddedChannel channel = new EmbeddedChannel(new JoinChainChannelHandler(),
			new LeaveChainChannelHandler());
		final ChainHeads heads = ChainHeads.builder().chainId(chainId).build();

		channel.writeInbound(heads);

		assertThat(channel.readInbound(), sameInstance(heads));
		assertThat(calls, empty());
	}

	@Property(tries = 10)
	public void passesTheOtherHandlersPacketsOn(@ForAll long requestId, @ForAll("chainIds") ChainId chainId,
		@ForAll("writers") WriterId writer) {

		final EmbeddedChannel joins = new EmbeddedChannel(new JoinChainChannelHandler());
		final EmbeddedChannel leaves = new EmbeddedChannel(new LeaveChainChannelHandler());
		final JoinChain join = joinOf(requestId, writer);
		final LeaveChain leave = LeaveChain.builder().chainId(chainId).writer(writer).build();
		final JoinReply answer = reply(requestId, JoinReply.Status.DISABLED);

		joins.writeInbound(leave, answer);
		leaves.writeInbound(join, answer);

		assertThat(joins.readInbound(), sameInstance(leave));
		assertThat(joins.readInbound(), sameInstance(answer));
		assertThat(leaves.readInbound(), sameInstance(join));
		assertThat(leaves.readInbound(), sameInstance(answer));
		assertThat(calls, empty());
		assertThat(joins.outboundMessages(), empty());
		assertThat(leaves.outboundMessages(), empty());
	}
}
