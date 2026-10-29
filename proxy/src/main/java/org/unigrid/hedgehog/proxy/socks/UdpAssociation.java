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
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.util.NetUtil;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.proxy.session.Datagram;
import org.unigrid.hedgehog.proxy.session.SocksAddress;

/* The UDP side of one SOCKS5 client: a local datagram socket, whose address the client is told, relayed through
   an association of the tunnel. Only the client's own source address is served. What cannot be carried is dropped
   and counted, never kept: a datagram the tunnel refuses, a datagram that is not a whole UDP header or is
   fragmented, one from another source, and what the exit sends while the loop's queue or the socket's outbound
   buffer is full or what one datagram cannot hold. The client hears one reply only, and everything but the
   tunnel's callbacks runs on the event loop of the control connection. */
final class UdpAssociation implements AssociationListener {
	/* Datagrams of the exit that wait for the event loop to write them to the client */
	static final int MAX_QUEUED = 64;

	/* The largest UDP datagram and the longest header in front of it */
	static final int MAX_PACKET_BYTES = 64 * 1024 + UdpHeaderCodec.MAX_HEADER_BYTES;

	/* The largest payload of one UDP datagram over IPv4: the socket would only fail the write of a longer one */
	static final int MAX_DATAGRAM_BYTES = 65_507;

	/* Drops are counted without a word, since a stranger on the host can cause any number of them; this many earn
	   the association one warning */
	static final long WARN_AFTER_DROPS = 1_000;

	private static final Logger LOG = LoggerFactory.getLogger(UdpAssociation.class);

	private final ChannelHandlerContext control;
	private final UdpBinder binder;
	private final int clientPort;
	private final Logger log;
	private final AtomicInteger queued = new AtomicInteger();
	private final AtomicLong dropped = new AtomicLong();
	private final ChannelFutureListener countFailure = written -> {
		if (!written.isSuccess()) {
			drop();
		}
	};
	private InetAddress clientAddress;
	private AssociationStream stream;
	private Channel local;
	private InetSocketAddress client;
	private boolean opened;
	private boolean replied;

	UdpAssociation(ChannelHandlerContext control, UdpBinder binder, int clientPort) {
		this(control, binder, clientPort, LOG);
	}

	UdpAssociation(ChannelHandlerContext control, UdpBinder binder, int clientPort, Logger log) {
		this.control = control;
		this.binder = binder;
		this.clientPort = clientPort;
		this.log = log;
	}

	/* A connection without an IP address at the client's end, or a tunnel that throws, has no association to give,
	   and the client hears so rather than waiting */
	void start(Tunnel tunnel, Optional<String> country) {
		try {
			clientAddress = ((InetSocketAddress) control.channel().remoteAddress()).getAddress();
			stream = tunnel.associate(country, this);
		} catch (RuntimeException ex) {
			refuse(Socks5CommandStatus.FAILURE);
		}
	}

	long dropped() {
		return dropped.get();
	}

	/* Ends the tunnel's association and the socket, whichever of them is there; the socket is closed even when the
	   tunnel fails to end its side */
	void close() {
		try {
			if (stream != null) {
				stream.close();
			}
		} finally {
			if (local != null) {
				local.close();
			}
		}
	}

	@Override
	public void opened() {
		control.executor().execute(this::bind);
	}

	@Override
	public void failed(TunnelFailure failure) {
		control.executor().execute(() -> end(failure.status()));
	}

	/* Never holds the tunnel up: past the limit a datagram is dropped at once instead of queued for the loop */
	@Override
	public void received(SocksAddress source, byte[] bytes) {
		if (queued.incrementAndGet() > MAX_QUEUED) {
			queued.decrementAndGet();
			drop();
		} else {
			control.executor().execute(() -> {
				queued.decrementAndGet();
				toClient(source, bytes);
			});
		}
	}

	@Override
	public void closed() {
		control.executor().execute(() -> end(Socks5CommandStatus.FAILURE));
	}

	/* A client still waiting for its reply hears why the association ended; one that has it sees the control
	   connection close */
	private void end(Socks5CommandStatus status) {
		if (replied) {
			control.close();
		} else {
			refuse(status);
		}
	}

	/* One socket per association, bound once the tunnel has opened it and only for a client that still waits */
	private void bind() {
		if (opened || replied || !control.channel().isActive()) {
			return;
		}

		opened = true;

		try {
			binder.bind(control.channel(), new Receiver()).addListener((ChannelFutureListener) this::bound);
		} catch (RuntimeException ex) {
			fail();
		}
	}

	/* A socket bound for a client that left or already has its answer is closed again */
	private void bound(ChannelFuture binding) {
		if (replied || !control.channel().isActive()) {
			binding.channel().close();
		} else if (binding.isSuccess()) {
			announce(binding.channel());
		} else {
			fail();
		}
	}

	/* The reply is built before anything changes, so that an address it cannot carry leaves nothing open. Reads on
	   the control connection start again so that its end, which ends the association, is noticed. */
	private void announce(Channel socket) {
		final Socks5CommandResponse reply;

		try {
			reply = success((InetSocketAddress) socket.localAddress());
		} catch (RuntimeException ex) {
			socket.close();
			fail();
			return;
		}

		local = socket;
		replied = true;
		socket.closeFuture().addListener(closing -> control.close());
		control.channel().config().setAutoRead(true);
		control.writeAndFlush(reply);
	}

	/* Nothing stays open for a client that is told FAILURE */
	private void fail() {
		close();
		refuse(Socks5CommandStatus.FAILURE);
	}

	private void refuse(Socks5CommandStatus status) {
		final SocksAddress none = SocksAddress.UNSPECIFIED;

		replied = true;
		control.writeAndFlush(new DefaultSocks5CommandResponse(status, none.type(), none.host(), none.port()))
			.addListener(ChannelFutureListener.CLOSE);
	}

	/* What the exit sends goes to the client's source once it is known and while the socket takes writes */
	private void toClient(SocksAddress source, byte[] bytes) {
		if (client == null || !local.isWritable() || !fits(source, bytes)) {
			drop();
		} else {
			final byte[] packet = UdpHeaderCodec.encode(new Datagram(source, bytes));

			local.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(packet), client))
				.addListener(countFailure);
		}
	}

	/* Nothing of a datagram is read before its source is known to be the client's, and only once the client was
	   told the socket's address */
	private void fromClient(InetSocketAddress sender, ByteBuf content) {
		if (local == null || !isClient(sender)) {
			drop();
			return;
		}

		try {
			final Datagram datagram = UdpHeaderCodec.decode(ByteBufUtil.getBytes(content), MAX_PACKET_BYTES);

			client = sender;
			relay(datagram);
		} catch (MalformedDatagramException ex) {
			drop();
		}
	}

	/* An association that throws on a send is broken, and the client is hung up on rather than left sending into it */
	private void relay(Datagram datagram) {
		try {
			if (!stream.send(datagram.address(), datagram.bytes())) {
				drop();
			}
		} catch (RuntimeException ex) {
			control.close();
		}
	}

	/* The IP of the control connection, with the port the command named, or the one the first datagram came from */
	private boolean isClient(InetSocketAddress sender) {
		if (client != null) {
			return client.equals(sender);
		}

		return clientAddress.equals(sender.getAddress()) && (clientPort == 0 || sender.getPort() == clientPort);
	}

	private void drop() {
		if (dropped.incrementAndGet() == WARN_AFTER_DROPS + 1) {
			log.warn("The UDP association of {} has dropped more than {} datagrams",
				control.channel().remoteAddress(), WARN_AFTER_DROPS);
		}
	}

	/* The socket would only fail the write of a datagram longer than that, so it is not even encoded */
	private static boolean fits(SocksAddress source, byte[] bytes) {
		return UdpHeaderCodec.PREFIX_BYTES + source.encodedSize() + bytes.length <= MAX_DATAGRAM_BYTES;
	}

	private static Socks5CommandResponse success(InetSocketAddress bound) {
		final InetAddress address = bound.getAddress();
		final Socks5AddressType type = address instanceof Inet4Address ? Socks5AddressType.IPv4
			: Socks5AddressType.IPv6;

		return new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, type, NetUtil.toAddressString(address),
			bound.getPort());
	}

	private final class Receiver extends SimpleChannelInboundHandler<DatagramPacket> {
		@Override
		protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
			fromClient(packet.sender(), packet.content());
		}
	}
}
