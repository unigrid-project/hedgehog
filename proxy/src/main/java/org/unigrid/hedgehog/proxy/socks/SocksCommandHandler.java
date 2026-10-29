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
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.unigrid.hedgehog.proxy.session.SocksAddress;

/* Serves the one command of a SOCKS5 connection: CONNECT opens a tunnel stream, the client hears the tunnel's
   answer as its reply, and bytes then flow both ways until either side closes. Reads stop at the command and
   resume when the stream opens, and stop again whenever the stream takes no more writes; the stream in turn
   holds back what the destination sends while the client takes no more. UDP ASSOCIATE opens a UDP association
   instead, which lasts as long as this connection does. */
final class SocksCommandHandler extends ChannelInboundHandlerAdapter {
	/* Reads stop at the command, so only what one read already held can arrive before the reply: at most
	   64 KiB with Netty's default allocator. More than twice that means reads did not stop. */
	static final int MAX_EARLY_BYTES = 128 * 1024;

	private final Tunnel tunnel;
	private final UdpBinder binder;
	private final List<byte[]> early = new ArrayList<>();
	private int earlyBytes;
	private ChannelHandlerContext context;
	private TunnelStream stream;
	private UdpAssociation association;
	private boolean open;
	private boolean ended;
	private boolean refused;

	SocksCommandHandler(Tunnel tunnel) {
		this(tunnel, UdpBinder.NIO);
	}

	SocksCommandHandler(Tunnel tunnel, UdpBinder binder) {
		this.tunnel = tunnel;
		this.binder = binder;
	}

	UdpAssociation association() {
		return association;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		context = ctx;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object message) {
		if (message instanceof Socks5CommandRequest request && association != null) {
			commandAfterAssociation(request);
		} else if (message instanceof Socks5CommandRequest request) {
			command(request);
		} else if (message instanceof ByteBuf bytes) {
			forward(bytes);
		} else {
			ReferenceCountUtil.release(message);
		}
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		if (stream != null) {
			stream.close();
		}

		if (association != null) {
			association.close();
		}

		ctx.fireChannelInactive();
	}

	@Override
	public void channelWritabilityChanged(ChannelHandlerContext ctx) {
		if (relaying()) {
			stream.setReadable(ctx.channel().isWritable());
		}

		ctx.fireChannelWritabilityChanged();
	}

	private void command(Socks5CommandRequest request) {
		context.channel().config().setAutoRead(false);

		if (isAssociate(request)) {
			associate(request.dstPort());
			return;
		}

		context.pipeline().remove(Socks5CommandRequestDecoder.class);

		if (request.decoderResult().isFailure()) {
			fail(TunnelFailure.GENERAL_FAILURE);
		} else if (Socks5CommandType.CONNECT.equals(request.type())) {
			connect(request);
		} else {
			/* BIND has no use behind an exit */
			fail(TunnelFailure.COMMAND_UNSUPPORTED);
		}
	}

	/* The control connection of an association carries no payload, so a fresh decoder reads on, the rest of this
	   read included, to notice a second command. The association exists first, so that such a command meets it even
	   if a decoder forwards it while it is being replaced. */
	private void associate(int clientPort) {
		association = new UdpAssociation(context, binder, clientPort);
		context.pipeline().replace(Socks5CommandRequestDecoder.class, null, new Socks5CommandRequestDecoder());
		association.start(tunnel, country());
	}

	/* A second command is a protocol violation; bytes the decoder cannot read as one are ignored, and so is
	   everything after them */
	private void commandAfterAssociation(Socks5CommandRequest request) {
		if (request.decoderResult().isSuccess()) {
			context.close();
		}
	}

	private void connect(Socks5CommandRequest request) {
		final Optional<SocksAddress> destination = destination(request);

		if (destination.isPresent()) {
			openStream(destination.get());
		} else {
			fail(TunnelFailure.ADDRESS_UNSUPPORTED);
		}
	}

	/* What the client named in its username, if it did */
	private Optional<String> country() {
		return Optional.ofNullable(context.channel().attr(SocksPasswordHandler.COUNTRY).get());
	}

	/* A tunnel that throws has no stream to give, and the client hears so rather than waiting for a reply */
	private void openStream(SocksAddress destination) {
		try {
			stream = tunnel.open(destination, country(), new Listener(context.executor()));
		} catch (RuntimeException ex) {
			fail(TunnelFailure.GENERAL_FAILURE);
		}
	}

	/* Bytes behind a refused command or after the stream ended have nowhere to go */
	private void forward(ByteBuf bytes) {
		try {
			if (relaying()) {
				relay(ByteBufUtil.getBytes(bytes));
				context.channel().config().setAutoRead(stream.isWritable());
			} else if (awaitingTunnel()) {
				hold(bytes);
			}
		} finally {
			bytes.release();
		}
	}

	private void hold(ByteBuf bytes) {
		if (bytes.readableBytes() > MAX_EARLY_BYTES - earlyBytes) {
			early.clear();
			context.close();
		} else {
			earlyBytes += bytes.readableBytes();
			early.add(ByteBufUtil.getBytes(bytes));
		}
	}

	/* The client gets one reply only. It is built before anything changes, so that one that cannot be built does
	   not leave the stream open with no reply sent. */
	private void opened(SocksAddress bound) {
		if (awaitingTunnel() && context.channel().isActive()) {
			final Socks5CommandResponse reply = success(bound);

			open = true;
			context.writeAndFlush(reply);
			early.forEach(this::relay);
			early.clear();
			context.channel().config().setAutoRead(stream.isWritable());
		}
	}

	private void failed(TunnelFailure failure) {
		if (awaitingTunnel()) {
			fail(failure);
		}
	}

	/* The tunnel may have sent these before it heard to hold back, so they are written all the same. A client
	   that fell behind before the reply caused no writability change the stream heard of, so it is told here. */
	private void received(byte[] bytes) {
		if (open) {
			context.writeAndFlush(Unpooled.wrappedBuffer(bytes));

			if (!context.channel().isWritable()) {
				stream.setReadable(false);
			}
		}
	}

	private void paced(boolean writable) {
		if (relaying()) {
			context.channel().config().setAutoRead(writable);
		}
	}

	/* Whatever the destination sent is flushed before the client sees the end of the stream, and nothing the
	   client sends meanwhile is read into a stream that ended */
	private void closed() {
		if (relaying()) {
			ended = true;
			context.channel().config().setAutoRead(false);
			context.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
		} else if (awaitingTunnel()) {
			fail(TunnelFailure.GENERAL_FAILURE);
		}
	}

	/* A stream that throws on a write is broken, and the client is hung up on rather than left sending into it */
	private void relay(byte[] bytes) {
		try {
			stream.write(bytes);
		} catch (RuntimeException ex) {
			context.close();
		}
	}

	private boolean awaitingTunnel() {
		return stream != null && !open && !refused;
	}

	private boolean relaying() {
		return open && !ended;
	}

	private void fail(TunnelFailure failure) {
		refused = true;
		context.writeAndFlush(response(failure.status(), SocksAddress.UNSPECIFIED))
			.addListener(ChannelFutureListener.CLOSE);
	}

	/* An exit may report a bound name the reply cannot carry, such as one with a label over 63 characters. The
	   stream is open all the same, so the client still hears of it, only without a bound address. */
	private static Socks5CommandResponse success(SocksAddress bound) {
		try {
			return response(Socks5CommandStatus.SUCCESS, bound);
		} catch (IllegalArgumentException ex) {
			return response(Socks5CommandStatus.SUCCESS, SocksAddress.UNSPECIFIED);
		}
	}

	private static Socks5CommandResponse response(Socks5CommandStatus status, SocksAddress bound) {
		return new DefaultSocks5CommandResponse(status, bound.type(), bound.host(), bound.port());
	}

	private static boolean isAssociate(Socks5CommandRequest request) {
		return request.decoderResult().isSuccess() && Socks5CommandType.UDP_ASSOCIATE.equals(request.type());
	}

	private static Optional<SocksAddress> destination(Socks5CommandRequest request) {
		try {
			return Optional.of(new SocksAddress(request.dstAddrType(), request.dstAddr(), request.dstPort()));
		} catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	/* Moves every call of the tunnel onto the channel's event loop, behind whatever runs there now */
	private final class Listener implements TunnelListener {
		private final EventExecutor loop;

		Listener(EventExecutor loop) {
			this.loop = loop;
		}

		@Override
		public void connected(SocksAddress bound) {
			loop.execute(() -> opened(bound));
		}

		@Override
		public void failed(TunnelFailure failure) {
			loop.execute(() -> SocksCommandHandler.this.failed(failure));
		}

		@Override
		public void received(byte[] bytes) {
			loop.execute(() -> SocksCommandHandler.this.received(bytes));
		}

		@Override
		public void writabilityChanged(boolean writable) {
			loop.execute(() -> paced(writable));
		}

		@Override
		public void closed() {
			loop.execute(SocksCommandHandler.this::closed);
		}
	}
}
