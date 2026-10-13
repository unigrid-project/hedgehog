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

package org.unigrid.hedgehog.proxy.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class SocksPacingTest {
	private static final SocksAddress TARGET = new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 443);
	private static final int LIMIT = SocksCommandHandler.MAX_EARLY_BYTES;

	private EchoTunnel tunnel;
	private List<Boolean> readableCalls;
	private EmbeddedChannel channel;

	@BeforeTry
	public void sendCommand() {
		tunnel = new EchoTunnel();
		readableCalls = new ArrayList<>();
		channel = client();
		channel.writeInbound(SocksCommandTest.request(Socks5CommandType.CONNECT, TARGET));
	}

	private EmbeddedChannel client() {
		return new EmbeddedChannel(new Socks5CommandRequestDecoder(), new SocksCommandHandler(this::open));
	}

	/* A client whose writes are held back, as a socket that has not sent them yet would, so that a close that
	   waits for them leaves it open */
	private EmbeddedChannel heldClient() {
		final EmbeddedChannel client = new EmbeddedChannel(new HeldWrites(), new Socks5CommandRequestDecoder(),
			new SocksCommandHandler(this::open));

		client.writeInbound(SocksCommandTest.request(Socks5CommandType.CONNECT, TARGET));
		return client;
	}

	/* The echo tunnel, with every call of setReadable on its streams noted in order */
	private TunnelStream open(SocksAddress destination, TunnelListener listener) {
		final TunnelStream stream = tunnel.open(destination, listener);

		return new TunnelStream() {
			@Override
			public void write(byte[] bytes) {
				stream.write(bytes);
			}

			@Override
			public boolean isWritable() {
				return stream.isWritable();
			}

			@Override
			public void setReadable(boolean readable) {
				readableCalls.add(readable);
				stream.setReadable(readable);
			}

			@Override
			public void close() {
				stream.close();
			}
		};
	}

	private static byte[] counting(int length) {
		final byte[] bytes = new byte[length];

		for (int i = 0; i < length; i++) {
			bytes[i] = (byte) i;
		}

		return bytes;
	}

	private static int references(List<ByteBuf> buffers) {
		return buffers.stream().mapToInt(ByteBuf::refCnt).sum();
	}

	private static void setWritable(EmbeddedChannel client, boolean writable) {
		client.unsafe().outboundBuffer().setUserDefinedWritability(1, writable);
		client.runPendingTasks();
	}

	/* Sends counting bytes ahead of the reply in chunks of the given size, and returns the buffers they came in */
	private List<ByteBuf> sendEarly(int bytes, int chunk) {
		final byte[] all = counting(bytes);
		final List<ByteBuf> sent = new ArrayList<>();

		for (int offset = 0; offset < bytes; offset += chunk) {
			final ByteBuf buffer = Unpooled.wrappedBuffer(all, offset, Math.min(chunk, bytes - offset));

			sent.add(buffer);
			channel.writeInbound(buffer);
		}

		return sent;
	}

	/* The stream opens and the client hears so once */
	private void replied() {
		tunnel.stream().connect(SocksAddress.UNSPECIFIED);
		channel.runPendingTasks();
		assertThat(((Socks5CommandResponse) channel.readOutbound()).status(), is(Socks5CommandStatus.SUCCESS));
	}

	private void assertNothingRelayed(EmbeddedChannel client) {
		tunnel.stream().connect(SocksAddress.UNSPECIFIED);
		client.runPendingTasks();
		assertThat(tunnel.stream().written.size(), is(0));
		assertThat(client.readOutbound(), is(nullValue()));
	}

	@Property(tries = 10)
	public void shouldHoldUpToTheLimitAheadOfTheReply(@ForAll @IntRange(min = 1000, max = 20_000) int chunk) {
		final List<ByteBuf> sent = sendEarly(LIMIT, chunk);

		assertThat(tunnel.stream().written.size(), is(0));
		replied();
		assertThat(tunnel.stream().written.toByteArray(), equalTo(counting(LIMIT)));
		assertThat(SocksCommandTest.readBytes(channel), equalTo(counting(LIMIT)));
		assertThat(channel.isOpen(), is(true));
		assertThat(references(sent), is(0));
	}

	@Example
	public void shouldDropAClientThatSendsMoreAheadOfTheReply() {
		final List<ByteBuf> sent = sendEarly(LIMIT + 1, 4096);

		assertThat(channel.isOpen(), is(false));
		assertThat(tunnel.stream().closed, is(true));
		assertThat(references(sent), is(0));
		assertNothingRelayed(channel);
	}

	@Property(tries = 10)
	public void shouldDropAClientThatSendsMoreInChunksOfAnySize(@ForAll @IntRange(min = 1000, max = 20_000) int chunk) {
		final List<ByteBuf> sent = sendEarly(LIMIT + 1, chunk);

		assertThat(channel.isOpen(), is(false));
		assertThat(references(sent), is(0));
		assertNothingRelayed(channel);
	}

	@Example
	public void shouldNotCopyAReadThatPassesTheLimit() {
		final AtomicInteger copied = new AtomicInteger();
		final byte[] read = new byte[LIMIT + 1];

		channel.writeInbound(new UnpooledHeapByteBuf(UnpooledByteBufAllocator.DEFAULT, read, read.length) {
			@Override
			public boolean hasArray() {
				return false;
			}

			@Override
			public ByteBuf getBytes(int index, byte[] destination, int offset, int length) {
				copied.addAndGet(length);
				return super.getBytes(index, destination, offset, length);
			}
		});
		assertThat(channel.isOpen(), is(false));
		assertThat(copied.get(), is(0));
	}

	@Example
	public void shouldHold128KiBThatArrivesInOneRead() {
		sendEarly(128 * 1024, 128 * 1024);
		replied();
		assertThat(tunnel.stream().written.toByteArray(), equalTo(counting(128 * 1024)));
		assertThat(channel.isOpen(), is(true));
	}

	@Example
	public void shouldDropAClientWhoseOneReadHoldsAByteMoreThan128KiB() {
		final List<ByteBuf> sent = sendEarly(128 * 1024 + 1, 128 * 1024 + 1);

		assertThat(channel.isOpen(), is(false));
		assertThat(tunnel.stream().closed, is(true));
		assertThat(references(sent), is(0));
		assertNothingRelayed(channel);
	}

	/* The decoder hands on what it kept of the read that held the command in one piece */
	@Example
	public void shouldHoldTheLimitThatArrivesBehindTheCommand() {
		final EmbeddedChannel client = client();
		final ByteBuf read = Unpooled.copiedBuffer(SocksCommandTest.request(Socks5CommandType.CONNECT, TARGET),
			Unpooled.wrappedBuffer(counting(LIMIT)));

		client.writeInbound(read);
		tunnel.stream().connect(SocksAddress.UNSPECIFIED);
		client.runPendingTasks();
		assertThat(tunnel.stream().written.toByteArray(), equalTo(counting(LIMIT)));
		assertThat(client.isOpen(), is(true));
		assertThat(read.refCnt(), is(0));
	}

	@Example
	public void shouldDropAClientThatSendsAByteMoreBehindTheCommand() {
		final EmbeddedChannel client = client();
		final ByteBuf read = Unpooled.copiedBuffer(SocksCommandTest.request(Socks5CommandType.CONNECT, TARGET),
			Unpooled.wrappedBuffer(counting(LIMIT + 1)));

		client.writeInbound(read);
		assertThat(client.isOpen(), is(false));
		assertThat(tunnel.stream().closed, is(true));
		assertThat(read.refCnt(), is(0));
		assertNothingRelayed(client);
	}

	@Example
	public void shouldDropRatherThanHoldWhatFollowsTheEnd() {
		final EmbeddedChannel client = heldClient();

		tunnel.stream().connect(SocksAddress.UNSPECIFIED);
		client.runPendingTasks();
		tunnel.stream().finish();
		client.runPendingTasks();
		client.writeInbound(Unpooled.wrappedBuffer(new byte[LIMIT + 1]));
		assertThat(client.isOpen(), is(true));
		assertThat(tunnel.stream().written.size(), is(0));
	}

	@Example
	public void shouldDropRatherThanHoldWhatFollowsARefusal() {
		final EmbeddedChannel client = heldClient();

		tunnel.stream().fail(TunnelFailure.HOST_UNREACHABLE);
		client.runPendingTasks();
		client.writeInbound(Unpooled.wrappedBuffer(new byte[LIMIT + 1]));
		assertThat(client.isOpen(), is(true));
		assertThat(tunnel.stream().written.size(), is(0));
	}

	@Example
	public void shouldRelayPastTheLimitOnceTheStreamOpens() {
		sendEarly(LIMIT, 4096);
		replied();
		channel.writeInbound(Unpooled.wrappedBuffer(new byte[LIMIT + 1]));
		assertThat(tunnel.stream().written.size(), is(2 * LIMIT + 1));
		assertThat(channel.isOpen(), is(true));
	}

	@Example
	public void shouldHoldTheDestinationBackWhileTheClientCannotKeepUp() {
		replied();
		setWritable(channel, false);
		assertThat(tunnel.stream().readable, is(false));
		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1, 2 }));
		assertThat(SocksCommandTest.readBytes(channel).length, is(0));

		setWritable(channel, true);
		assertThat(tunnel.stream().readable, is(true));
		assertThat(SocksCommandTest.readBytes(channel), equalTo(new byte[] { 1, 2 }));
		assertThat(readableCalls, contains(false, true));
	}

	@Example
	public void shouldLeaveTheDestinationAloneWhileTheClientWaitsForItsReply() {
		setWritable(channel, false);
		setWritable(channel, true);
		assertThat(readableCalls, is(empty()));
		assertThat(tunnel.stream().readable, is(true));
	}

	@Example
	public void shouldLeaveTheDestinationAloneOnceTheStreamEnded() {
		final EmbeddedChannel client = heldClient();

		tunnel.stream().connect(SocksAddress.UNSPECIFIED);
		client.runPendingTasks();
		setWritable(client, false);
		tunnel.stream().finish();
		client.runPendingTasks();
		setWritable(client, true);
		assertThat(client.isOpen(), is(true));
		assertThat(readableCalls, contains(false));
	}

	/* No writability change reaches the stream when the client fell behind before the reply, so the bytes that
	   reach the client first hold the rest back */
	@Example
	public void shouldHoldTheDestinationBackFromAClientThatFellBehindBeforeTheReply() {
		setWritable(channel, false);
		replied();
		assertThat(readableCalls, is(empty()));
		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1, 2 }));
		assertThat(readableCalls, contains(false));
		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 3 }));
		assertThat(SocksCommandTest.readBytes(channel), equalTo(new byte[] { 1, 2 }));

		setWritable(channel, true);
		assertThat(SocksCommandTest.readBytes(channel), equalTo(new byte[] { 3 }));
		assertThat(readableCalls, contains(false, true));
	}

	/* The tunnel may have sent these before it heard to hold back */
	@Example
	public void shouldWriteAllTheTunnelSentInOrderWhileTheClientCannotKeepUp() {
		replied();
		setWritable(channel, false);
		tunnel.stream().listener.received(new byte[] { 1 });
		tunnel.stream().listener.received(new byte[] { 2, 3 });
		tunnel.stream().listener.received(new byte[] { 4 });
		channel.runPendingTasks();
		assertThat(SocksCommandTest.readBytes(channel), equalTo(new byte[] { 1, 2, 3, 4 }));
		assertThat(channel.isOpen(), is(true));
	}

	@Example
	public void shouldPassEveryWritabilityChangeOnDownThePipeline() {
		final List<Boolean> passed = new ArrayList<>();
		final EmbeddedChannel client = new EmbeddedChannel(new Socks5CommandRequestDecoder(),
			new SocksCommandHandler(this::open), new ChannelInboundHandlerAdapter() {
				@Override
				public void channelWritabilityChanged(ChannelHandlerContext ctx) {
					passed.add(ctx.channel().isWritable());
				}
			});

		client.writeInbound(SocksCommandTest.request(Socks5CommandType.CONNECT, TARGET));
		setWritable(client, false);
		tunnel.stream().connect(SocksAddress.UNSPECIFIED);
		client.runPendingTasks();
		setWritable(client, true);
		assertThat(passed, contains(false, true));
	}
}
