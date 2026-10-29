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
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;
import org.slf4j.event.Level;
import org.slf4j.event.SubstituteLoggingEvent;
import org.slf4j.helpers.SubstituteLogger;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.session.Datagram;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class SocksAssociateTest {
	private static final InetSocketAddress CLIENT = new InetSocketAddress(address(127, 0, 0, 2), 40_000);
	private static final InetSocketAddress OTHER_PORT = new InetSocketAddress(address(127, 0, 0, 2), 40_001);
	private static final InetSocketAddress OTHER_HOST = new InetSocketAddress(address(127, 0, 0, 3), 40_000);
	private static final InetSocketAddress PROXY = new InetSocketAddress(address(127, 0, 0, 1), 1080);
	private static final InetSocketAddress SOCKET = new InetSocketAddress(address(127, 0, 0, 1), 40_500);
	private static final SocksAddress REMOTE = new SocksAddress(Socks5AddressType.IPv4, "192.0.2.53", 53);
	private static final SocksAddress NAMED = new SocksAddress(Socks5AddressType.DOMAIN, "example.test", 53);
	private static final SocksAddress UNSPECIFIED = SocksAddress.UNSPECIFIED;

	private static InetAddress address(int... bytes) {
		try {
			return InetAddress.getByAddress(new byte[] { (byte) bytes[0], (byte) bytes[1], (byte) bytes[2],
				(byte) bytes[3] });
		} catch (UnknownHostException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/* An embedded channel with an IP address at each end, as a connection the proxy accepted has */
	private static class Endpoints extends EmbeddedChannel {
		private final SocketAddress local;
		private final SocketAddress remote;

		private Endpoints(SocketAddress local, SocketAddress remote, ChannelHandler... handlers) {
			super(handlers);
			this.local = local;
			this.remote = remote;
		}

		@Override
		protected SocketAddress localAddress0() {
			return isActive() ? local : null;
		}

		@Override
		protected SocketAddress remoteAddress0() {
			return isActive() ? remote : null;
		}
	}

	/* A socket whose every write fails, as one does whose datagram the kernel refuses */
	private static final class FailingWrites extends ChannelOutboundHandlerAdapter {
		@Override
		public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
			ReferenceCountUtil.release(message);
			promise.setFailure(new IOException("Message too long"));
		}
	}

	private EchoTunnel tunnel;
	private SocksCommandHandler handler;
	private Endpoints control;
	private List<Endpoints> sockets;
	private ChannelPromise heldBinding;

	@BeforeTry
	public void connectClient() {
		tunnel = new EchoTunnel();
		sockets = new ArrayList<>();
		connect(binder(SOCKET));
	}

	private void connect(UdpBinder binder, ChannelHandler... ahead) {
		connect(tunnel, binder, ahead);
	}

	private void connect(Tunnel through, UdpBinder binder, ChannelHandler... ahead) {
		final List<ChannelHandler> handlers = new ArrayList<>(List.of(ahead));

		handler = new SocksCommandHandler(through, binder);
		handlers.addAll(List.of(new Socks5CommandRequestDecoder(), handler));
		control = new Endpoints(PROXY, CLIENT, handlers.toArray(new ChannelHandler[0]));
	}

	/* Gives out a bound socket per call, at the address given and with the handlers given ahead of the association's */
	private UdpBinder binder(SocketAddress at, ChannelHandler... ahead) {
		return (connection, socketHandler) -> {
			final List<ChannelHandler> handlers = new ArrayList<>(List.of(ahead));

			handlers.add(socketHandler);
			sockets.add(new Endpoints(at, null, handlers.toArray(new ChannelHandler[0])));
			return sockets.get(sockets.size() - 1).newSucceededFuture();
		};
	}

	/* Gives out a socket that is bound once the test completes the binding */
	private UdpBinder heldBinder() {
		return (connection, socketHandler) -> {
			sockets.add(new Endpoints(SOCKET, null, socketHandler));
			heldBinding = sockets.get(sockets.size() - 1).newPromise();
			return heldBinding;
		};
	}

	private static Socks5CommandResponse reply(EmbeddedChannel channel) {
		return channel.readOutbound();
	}

	private static void assertReply(Socks5CommandResponse reply, Socks5CommandStatus status, SocksAddress bound) {
		assertThat(reply.status(), is(status));
		assertThat(new SocksAddress(reply.bndAddrType(), reply.bndAddr(), reply.bndPort()), equalTo(bound));
	}

	private void requestAssociation() {
		control.writeInbound(SocksCommandTest.request(Socks5CommandType.UDP_ASSOCIATE, UNSPECIFIED));
	}

	/* The client's request, the tunnel's answer and the reply that carries the address of the socket */
	private EchoTunnel.EchoAssociation associated(int clientPort) {
		control.writeInbound(SocksCommandTest.request(Socks5CommandType.UDP_ASSOCIATE, new SocksAddress(
			Socks5AddressType.IPv4, "0.0.0.0", clientPort)));
		tunnel.association().open();
		control.runPendingTasks();
		assertThat(reply(control).status(), is(Socks5CommandStatus.SUCCESS));
		return tunnel.association();
	}

	private EchoTunnel.EchoAssociation associated() {
		return associated(0);
	}

	private static byte[] packet(SocksAddress destination, byte[] data) {
		return UdpHeaderCodec.encode(new Datagram(destination, data));
	}

	private static int headerBytes(SocksAddress source) {
		return packet(source, new byte[0]).length;
	}

	private void sendFrom(InetSocketAddress sender, byte[] packet) {
		sendFrom(sender, Unpooled.wrappedBuffer(packet));
	}

	private void sendFrom(InetSocketAddress sender, ByteBuf content) {
		sockets.get(0).writeInbound(new DatagramPacket(content, SOCKET, sender));
	}

	private void deliver(EchoTunnel.EchoAssociation association, int datagrams) {
		for (int i = 0; i < datagrams; i++) {
			association.deliver(REMOTE, new byte[] { 9 });
		}
	}

	/* What the socket sent to the client */
	private List<DatagramPacket> written() {
		final List<DatagramPacket> packets = new ArrayList<>();

		for (DatagramPacket packet = sockets.get(0).readOutbound(); packet != null;
			packet = sockets.get(0).readOutbound()) {
			packets.add(packet);
		}

		return packets;
	}

	/* The content of a datagram that fails the test as soon as anything reads it */
	private static ByteBuf unreadable(byte[] bytes) {
		final ByteBuf content = new UnpooledHeapByteBuf(UnpooledByteBufAllocator.DEFAULT, bytes.length,
			bytes.length) {
			@Override
			public byte[] array() {
				throw new AssertionError("The content of a stranger's datagram was read");
			}

			@Override
			public ByteBuf getBytes(int index, byte[] destination, int destinationIndex, int length) {
				throw new AssertionError("The content of a stranger's datagram was read");
			}
		};

		return content.writeBytes(bytes);
	}

	@Provide
	public Arbitrary<Socks5CommandType> commands() {
		return Arbitraries.of(Socks5CommandType.CONNECT, Socks5CommandType.UDP_ASSOCIATE, Socks5CommandType.BIND);
	}

	@Example
	public void shouldKeepToItsWrittenOutLimits() {
		assertThat(UdpAssociation.MAX_QUEUED, is(64));
		assertThat(UdpAssociation.MAX_PACKET_BYTES, is(65_536 + 262));
		assertThat(UdpAssociation.MAX_DATAGRAM_BYTES, is(65_507));
		assertThat(UdpAssociation.WARN_AFTER_DROPS, is(1_000L));
	}

	@Example
	public void shouldBindNothingUntilTheTunnelHasOpened() {
		control.writeInbound(SocksCommandTest.request(Socks5CommandType.UDP_ASSOCIATE, UNSPECIFIED));

		assertThat(tunnel.association().closed, is(false));
		assertThat(sockets, is(empty()));
		assertThat(control.readOutbound(), is(nullValue()));
	}

	@Example
	public void shouldReplyWithTheAddressOfItsOwnSocket() {
		associated();

		assertThat(sockets.size(), is(1));
		assertThat(control.config().isAutoRead(), is(true));
	}

	@Example
	public void shouldAnswerWhatTheSocketIsBoundTo() {
		control.writeInbound(SocksCommandTest.request(Socks5CommandType.UDP_ASSOCIATE, UNSPECIFIED));
		tunnel.association().open();
		control.runPendingTasks();

		final Socks5CommandResponse reply = reply(control);

		assertThat(reply.bndAddrType(), is(Socks5AddressType.IPv4));
		assertThat(reply.bndAddr(), is("127.0.0.1"));
		assertThat(reply.bndPort(), is(40_500));
	}

	@Example
	public void shouldAnswerALinkLocalSocketWithoutItsZone() throws UnknownHostException {
		final byte[] linkLocal = new byte[16];

		linkLocal[0] = (byte) 0xfe;
		linkLocal[1] = (byte) 0x80;
		linkLocal[15] = 1;
		connect(binder(new InetSocketAddress(Inet6Address.getByAddress("proxy", linkLocal, 2), 40_500)));
		requestAssociation();
		tunnel.association().open();
		control.runPendingTasks();

		assertReply(reply(control), Socks5CommandStatus.SUCCESS, new SocksAddress(Socks5AddressType.IPv6, "fe80::1",
			40_500));
	}

	@Property(tries = 8)
	public void shouldTellTheClientWhyTheTunnelRefused(@ForAll TunnelFailure failure) {
		control.writeInbound(SocksCommandTest.request(Socks5CommandType.UDP_ASSOCIATE, UNSPECIFIED));
		tunnel.association().fail(failure);
		control.runPendingTasks();

		assertThat(reply(control).status(), is(failure.status()));
		assertThat(control.isOpen(), is(false));
		assertThat(sockets, is(empty()));
	}

	@Example
	public void shouldFailAnAssociationThatEndsBeforeItOpens() {
		requestAssociation();
		tunnel.association().finish();
		control.runPendingTasks();

		assertReply(reply(control), Socks5CommandStatus.FAILURE, UNSPECIFIED);
		assertThat(control.readOutbound(), is(nullValue()));
		assertThat(control.isOpen(), is(false));
		assertThat(sockets, is(empty()));
	}

	/* The refusal is held back, so that the control connection is still open when the tunnel's late answer arrives */
	@Example
	public void shouldNotOpenAnAssociationThatFailed() {
		final HeldWrites writes = new HeldWrites();

		connect(binder(SOCKET), writes);
		requestAssociation();
		tunnel.association().fail(TunnelFailure.HOST_UNREACHABLE);
		tunnel.association().open();
		control.runPendingTasks();
		writes.release();

		assertReply(reply(control), Socks5CommandStatus.HOST_UNREACHABLE, UNSPECIFIED);
		assertThat(control.readOutbound(), is(nullValue()));
		assertThat(sockets, is(empty()));
	}

	@Example
	public void shouldBindOneSocketForAnAssociationThatOpensTwice() {
		connect(heldBinder());
		requestAssociation();
		tunnel.association().open();
		tunnel.association().open();
		control.runPendingTasks();
		heldBinding.setSuccess();

		assertThat(sockets.size(), is(1));
		assertThat(reply(control).status(), is(Socks5CommandStatus.SUCCESS));
		assertThat(control.readOutbound(), is(nullValue()));
	}

	@Example
	public void shouldTellTheClientOfAFailureWhileTheSocketIsBeingBound() {
		final HeldWrites writes = new HeldWrites();

		connect(heldBinder(), writes);
		requestAssociation();
		tunnel.association().open();
		control.runPendingTasks();
		tunnel.association().fail(TunnelFailure.HOST_UNREACHABLE);
		control.runPendingTasks();
		heldBinding.setSuccess();

		assertThat(sockets.get(0).isOpen(), is(false));
		writes.release();
		assertReply(reply(control), Socks5CommandStatus.HOST_UNREACHABLE, UNSPECIFIED);
		assertThat(control.readOutbound(), is(nullValue()));
		assertThat(control.isOpen(), is(false));
	}

	@Example
	public void shouldFailAnAssociationThatEndsWhileTheSocketIsBeingBound() {
		final HeldWrites writes = new HeldWrites();

		connect(heldBinder(), writes);
		requestAssociation();
		tunnel.association().open();
		control.runPendingTasks();
		tunnel.association().finish();
		control.runPendingTasks();
		heldBinding.setSuccess();

		assertThat(sockets.get(0).isOpen(), is(false));
		writes.release();
		assertReply(reply(control), Socks5CommandStatus.FAILURE, UNSPECIFIED);
		assertThat(control.readOutbound(), is(nullValue()));
	}

	@Example
	public void shouldAnswerFailureWhenTheSocketCannotBeBound() {
		connect((connection, socketHandler) -> connection.newFailedFuture(new IllegalStateException("no port")));
		control.writeInbound(SocksCommandTest.request(Socks5CommandType.UDP_ASSOCIATE, UNSPECIFIED));
		tunnel.association().open();
		control.runPendingTasks();

		assertThat(reply(control).status(), is(Socks5CommandStatus.FAILURE));
		assertThat(control.isOpen(), is(false));
		assertThat(tunnel.association().closed, is(true));
	}

	@Example
	public void shouldEndTheAssociationBeforeTheClientHearsThatNoSocketWasBound() {
		final HeldWrites writes = new HeldWrites();

		connect((connection, socketHandler) -> connection.newFailedFuture(new IllegalStateException("no port")),
			writes);
		requestAssociation();
		tunnel.association().open();
		control.runPendingTasks();

		assertThat(tunnel.association().closed, is(true));
		writes.release();
		assertReply(reply(control), Socks5CommandStatus.FAILURE, UNSPECIFIED);
		assertThat(control.readOutbound(), is(nullValue()));
		assertThat(control.isOpen(), is(false));
	}

	@Example
	public void shouldAnswerFailureWhenTheBinderThrows() {
		connect((connection, socketHandler) -> {
			throw new IllegalStateException("The loop is shutting down");
		});
		requestAssociation();
		tunnel.association().open();
		control.runPendingTasks();
		control.checkException();

		assertReply(reply(control), Socks5CommandStatus.FAILURE, UNSPECIFIED);
		assertThat(control.isOpen(), is(false));
		assertThat(tunnel.association().closed, is(true));
	}

	@Example
	public void shouldAnswerFailureForASocketAddressTheReplyCannotCarry() {
		connect(binder(InetSocketAddress.createUnresolved("proxy.test", 40_500)));
		requestAssociation();
		tunnel.association().open();
		control.runPendingTasks();
		control.checkException();

		assertReply(reply(control), Socks5CommandStatus.FAILURE, UNSPECIFIED);
		assertThat(control.readOutbound(), is(nullValue()));
		assertThat(control.isOpen(), is(false));
		assertThat(control.config().isAutoRead(), is(false));
		assertThat(sockets.get(0).isOpen(), is(false));
		assertThat(tunnel.association().closed, is(true));
	}

	@Example
	public void shouldAnswerFailureForASocketThatClosedBeforeItsAddressWasTold() {
		connect(heldBinder());
		requestAssociation();
		tunnel.association().open();
		control.runPendingTasks();
		sockets.get(0).close();
		heldBinding.setSuccess();
		control.checkException();

		assertReply(reply(control), Socks5CommandStatus.FAILURE, UNSPECIFIED);
		assertThat(control.isOpen(), is(false));
		assertThat(tunnel.association().closed, is(true));
	}

	@Example
	public void shouldHandTheCountryOfTheConnectionToTheTunnel() {
		control.attr(SocksPasswordHandler.COUNTRY).set("NO");
		requestAssociation();
		assertThat(tunnel.association().country, equalTo(Optional.of("NO")));
	}

	@Example
	public void shouldAskForNoCountryOnBehalfOfAConnectionThatNamedNone() {
		requestAssociation();
		assertThat(tunnel.association().country, equalTo(Optional.empty()));
	}

	@Example
	public void shouldFailAnAssociationTheTunnelThrowsOn() {
		connect(new Tunnel() {
			@Override
			public TunnelStream open(SocksAddress destination, Optional<String> country, TunnelListener listener) {
				throw new IllegalStateException("The tunnel is down");
			}

			@Override
			public AssociationStream associate(Optional<String> country, AssociationListener listener) {
				throw new IllegalStateException("The tunnel is down");
			}
		}, binder(SOCKET));
		requestAssociation();

		assertReply(reply(control), Socks5CommandStatus.FAILURE, UNSPECIFIED);
		assertThat(control.readOutbound(), is(nullValue()));
		assertThat(control.isOpen(), is(false));
	}

	/* The refusal is held back, so that the control connection is still open when the tunnel's opening is heard */
	@Example
	public void shouldReplyOnlyOnceToATunnelThatOpensAndThenThrows() {
		final HeldWrites writes = new HeldWrites();

		connect(new Tunnel() {
			@Override
			public TunnelStream open(SocksAddress destination, Optional<String> country, TunnelListener listener) {
				return tunnel.open(destination, country, listener);
			}

			@Override
			public AssociationStream associate(Optional<String> country, AssociationListener listener) {
				tunnel.associate(country, listener);
				listener.opened();
				throw new IllegalStateException("The tunnel broke after it opened");
			}
		}, binder(SOCKET), writes);
		requestAssociation();
		control.runPendingTasks();
		writes.release();

		assertReply(reply(control), Socks5CommandStatus.FAILURE, UNSPECIFIED);
		assertThat(control.readOutbound(), is(nullValue()));
		assertThat(sockets, is(empty()));
	}

	@Example
	public void shouldAnswerAnAssociationThatFailedToDecodeWithAGeneralFailure() {
		final DefaultSocks5CommandRequest undecodable = new DefaultSocks5CommandRequest(
			Socks5CommandType.UDP_ASSOCIATE, Socks5AddressType.IPv4, "0.0.0.0", 0);

		undecodable.setDecoderResult(DecoderResult.failure(new DecoderException("Truncated command")));
		control.writeInbound(undecodable);

		assertReply(reply(control), Socks5CommandStatus.FAILURE, UNSPECIFIED);
		assertThat(control.isOpen(), is(false));
		assertThat(tunnel.associations, is(empty()));
	}

	@Example
	public void shouldFailAnAssociationOverAConnectionWithoutIpAddresses() {
		final EmbeddedChannel plain = new EmbeddedChannel(new Socks5CommandRequestDecoder(),
			new SocksCommandHandler(tunnel, binder(SOCKET)));

		plain.writeInbound(SocksCommandTest.request(Socks5CommandType.UDP_ASSOCIATE, UNSPECIFIED));

		assertReply(plain.readOutbound(), Socks5CommandStatus.FAILURE, UNSPECIFIED);
		assertThat(plain.isOpen(), is(false));
		assertThat(tunnel.associations, is(empty()));
	}

	@Example
	public void shouldSendTheDatagramsOfTheClientThroughTheTunnel() {
		final EchoTunnel.EchoAssociation association = associated();

		sendFrom(CLIENT, packet(REMOTE, new byte[] { 1, 2, 3 }));
		sendFrom(CLIENT, packet(REMOTE, new byte[] { 4 }));

		assertThat(association.sent, equalTo(List.of(new Datagram(REMOTE, new byte[] { 1, 2, 3 }),
			new Datagram(REMOTE, new byte[] { 4 }))));
		assertThat(handler.association().dropped(), is(0L));
	}

	@Example
	public void shouldAcceptDatagramsFromTheClientAddressOnly() {
		final EchoTunnel.EchoAssociation association = associated();

		sendFrom(OTHER_HOST, packet(REMOTE, new byte[] { 1 }));
		sendFrom(CLIENT, packet(REMOTE, new byte[] { 2 }));
		sendFrom(OTHER_PORT, packet(REMOTE, new byte[] { 3 }));
		sendFrom(CLIENT, packet(REMOTE, new byte[] { 4 }));

		assertThat(association.sent, equalTo(List.of(new Datagram(REMOTE, new byte[] { 2 }),
			new Datagram(REMOTE, new byte[] { 4 }))));
		assertThat(handler.association().dropped(), is(2L));
	}

	@Example
	public void shouldHoldTheClientToThePortItNamedInTheCommand() {
		final EchoTunnel.EchoAssociation association = associated(OTHER_PORT.getPort());

		sendFrom(CLIENT, packet(REMOTE, new byte[] { 1 }));
		sendFrom(OTHER_PORT, packet(REMOTE, new byte[] { 2 }));

		assertThat(association.sent, equalTo(List.of(new Datagram(REMOTE, new byte[] { 2 }))));
		assertThat(handler.association().dropped(), is(1L));
	}

	@Example
	public void shouldHoldTheClientToItsOwnHostWhenTheCommandNamedAPort() {
		final EchoTunnel.EchoAssociation association = associated(CLIENT.getPort());

		sendFrom(OTHER_HOST, packet(REMOTE, new byte[] { 1 }));
		sendFrom(OTHER_PORT, packet(REMOTE, new byte[] { 2 }));
		sendFrom(CLIENT, packet(REMOTE, new byte[] { 3 }));

		assertThat(association.sent, equalTo(List.of(new Datagram(REMOTE, new byte[] { 3 }))));
		assertThat(handler.association().dropped(), is(2L));
	}

	@Example
	public void shouldNotLetAStrangerTakeThePlaceOfTheClient() {
		final EchoTunnel.EchoAssociation association = associated();

		sendFrom(OTHER_HOST, packet(REMOTE, new byte[] { 1 }));
		sendFrom(CLIENT, packet(REMOTE, new byte[] { 2 }));
		association.deliver(REMOTE, new byte[] { 9 });
		control.runPendingTasks();

		assertThat(association.sent, equalTo(List.of(new Datagram(REMOTE, new byte[] { 2 }))));
		assertThat(written().get(0).recipient(), equalTo(CLIENT));
	}

	@Example
	public void shouldTakeThePortOfTheClientFromItsFirstValidDatagram() {
		final EchoTunnel.EchoAssociation association = associated();

		sendFrom(OTHER_PORT, new byte[] { 0, 0, 1 });
		sendFrom(CLIENT, packet(REMOTE, new byte[] { 2 }));
		sendFrom(OTHER_PORT, packet(REMOTE, new byte[] { 3 }));
		association.deliver(REMOTE, new byte[] { 9 });
		control.runPendingTasks();

		assertThat(association.sent, equalTo(List.of(new Datagram(REMOTE, new byte[] { 2 }))));
		assertThat(written().get(0).recipient(), equalTo(CLIENT));
		assertThat(handler.association().dropped(), is(2L));
	}

	@Example
	public void shouldDropAStrangersDatagramUnreadAndUnanswered() {
		final EchoTunnel.EchoAssociation association = associated();

		sendFrom(OTHER_HOST, unreadable(packet(REMOTE, new byte[] { 1 })));
		sendFrom(CLIENT, packet(REMOTE, new byte[] { 2 }));
		sendFrom(OTHER_HOST, unreadable(packet(REMOTE, new byte[] { 3 })));
		sendFrom(OTHER_PORT, unreadable(packet(REMOTE, new byte[] { 4 })));

		assertThat(association.sent, equalTo(List.of(new Datagram(REMOTE, new byte[] { 2 }))));
		assertThat(handler.association().dropped(), is(3L));
		assertThat(written(), is(empty()));
	}

	/* Reaching the socket before the client was told its address, it cannot be from a client that follows SOCKS5 */
	@Example
	public void shouldDropWhatReachesTheSocketBeforeTheClientWasToldOfIt() {
		connect(heldBinder());
		requestAssociation();
		tunnel.association().open();
		control.runPendingTasks();
		sendFrom(CLIENT, packet(REMOTE, new byte[] { 1 }));
		heldBinding.setSuccess();

		assertThat(reply(control).status(), is(Socks5CommandStatus.SUCCESS));
		assertThat(tunnel.association().sent, is(empty()));
		assertThat(handler.association().dropped(), is(1L));
	}

	@Property(tries = 20)
	public void shouldDropFragmentsAndPacketsThatAreNotWholeHeaders(@ForAll @IntRange(min = 1, max = 255) int fragment) {
		final EchoTunnel.EchoAssociation association = associated();
		final byte[] fragmented = packet(REMOTE, new byte[] { 1 });

		fragmented[2] = (byte) fragment;
		sendFrom(CLIENT, fragmented);
		sendFrom(CLIENT, new byte[] { 0, 0, 0, 1, 1 });
		sendFrom(CLIENT, new byte[0]);

		assertThat(association.sent, is(empty()));
		assertThat(handler.association().dropped(), is(3L));
	}

	@Example
	public void shouldTakeAPacketAsLongAsTheSocketReadsAtMost() {
		final EchoTunnel.EchoAssociation association = associated();
		final byte[] data = new byte[UdpAssociation.MAX_PACKET_BYTES - headerBytes(REMOTE)];

		sendFrom(CLIENT, packet(REMOTE, data));
		sendFrom(CLIENT, packet(REMOTE, new byte[data.length + 1]));

		assertThat(association.sent, equalTo(List.of(new Datagram(REMOTE, data))));
		assertThat(handler.association().dropped(), is(1L));
	}

	@Example
	public void shouldDropADatagramToADomainNameSpeltAsAnIpAddress() {
		final EchoTunnel.EchoAssociation association = associated();

		sendFrom(CLIENT, new byte[] { 0, 0, 0, 3, 9, '1', '2', '7', '.', '0', '.', '0', '.', '1', 0, 53, 1 });

		assertThat(association.sent, is(empty()));
		assertThat(handler.association().dropped(), is(1L));
	}

	@Example
	public void shouldCountWhatTheTunnelDrops() {
		final EchoTunnel.EchoAssociation association = associated();

		association.accepting = false;
		sendFrom(CLIENT, packet(REMOTE, new byte[] { 1 }));

		assertThat(handler.association().dropped(), is(1L));
	}

	@Example
	public void shouldHangUpOnAnAssociationThatBreaksOnASend() {
		final EchoTunnel.EchoAssociation association = associated();

		association.broken = true;
		sendFrom(CLIENT, packet(REMOTE, new byte[] { 1 }));

		assertThat(control.isOpen(), is(false));
		assertThat(association.closed, is(true));
		assertThat(sockets.get(0).isOpen(), is(false));
	}

	@Example
	public void shouldDeliverWhatTheExitSendsToTheClientWithItsSource() {
		final EchoTunnel.EchoAssociation association = associated();

		sendFrom(CLIENT, packet(REMOTE, new byte[] { 1 }));
		association.deliver(REMOTE, new byte[] { 9, 8 });
		control.runPendingTasks();

		final DatagramPacket answer = sockets.get(0).readOutbound();
		final byte[] bytes = new byte[answer.content().readableBytes()];

		answer.content().readBytes(bytes);
		assertThat(answer.recipient(), equalTo(CLIENT));
		assertThat(bytes, equalTo(packet(REMOTE, new byte[] { 9, 8 })));
	}

	@Example
	public void shouldDropWhatTheExitSendsBeforeTheClientHasSentAnything() {
		final EchoTunnel.EchoAssociation association = associated();

		association.deliver(REMOTE, new byte[] { 9 });
		control.runPendingTasks();

		assertThat(sockets.get(0).readOutbound(), is(nullValue()));
		assertThat(handler.association().dropped(), is(1L));
	}

	@Example
	public void shouldDropWhatTheExitSendsWhileTheSocketIsBackedUp() {
		final EchoTunnel.EchoAssociation association = associated();

		sendFrom(CLIENT, packet(REMOTE, new byte[] { 1 }));
		sockets.get(0).unsafe().outboundBuffer().setUserDefinedWritability(1, false);
		association.deliver(REMOTE, new byte[] { 9 });
		control.runPendingTasks();

		assertThat(sockets.get(0).readOutbound(), is(nullValue()));
		assertThat(handler.association().dropped(), is(1L));
	}

	@Example
	public void shouldWriteWhatTheExitSendsThatFillsOneDatagram() {
		final EchoTunnel.EchoAssociation association = associated();

		sendFrom(CLIENT, packet(REMOTE, new byte[] { 1 }));
		association.deliver(REMOTE, new byte[UdpAssociation.MAX_DATAGRAM_BYTES - headerBytes(REMOTE)]);
		association.deliver(NAMED, new byte[UdpAssociation.MAX_DATAGRAM_BYTES - headerBytes(NAMED)]);
		control.runPendingTasks();

		final List<DatagramPacket> written = written();

		assertThat(written.size(), is(2));
		assertThat(written.get(0).content().readableBytes(), is(UdpAssociation.MAX_DATAGRAM_BYTES));
		assertThat(written.get(1).content().readableBytes(), is(UdpAssociation.MAX_DATAGRAM_BYTES));
		assertThat(handler.association().dropped(), is(0L));
	}

	@Example
	public void shouldDropWhatTheExitSendsAByteBeyondOneDatagram() {
		final EchoTunnel.EchoAssociation association = associated();

		sendFrom(CLIENT, packet(REMOTE, new byte[] { 1 }));
		association.deliver(REMOTE, new byte[UdpAssociation.MAX_DATAGRAM_BYTES - headerBytes(REMOTE) + 1]);
		association.deliver(NAMED, new byte[UdpAssociation.MAX_DATAGRAM_BYTES - headerBytes(NAMED) + 1]);
		control.runPendingTasks();

		assertThat(written(), is(empty()));
		assertThat(handler.association().dropped(), is(2L));
	}

	@Example
	public void shouldCountWhatTheSocketFailsToWrite() {
		connect(binder(SOCKET, new FailingWrites()));

		final EchoTunnel.EchoAssociation association = associated();

		sendFrom(CLIENT, packet(REMOTE, new byte[] { 1 }));
		association.deliver(REMOTE, new byte[] { 9 });
		control.runPendingTasks();

		assertThat(handler.association().dropped(), is(1L));
	}

	@Example
	public void shouldQueueNoMoreThanTheLimitForTheEventLoop() {
		final EchoTunnel.EchoAssociation association = associated();

		sendFrom(CLIENT, packet(REMOTE, new byte[] { 1 }));

		for (int i = 0; i < UdpAssociation.MAX_QUEUED + 5; i++) {
			association.deliver(REMOTE, new byte[] { 9 });
		}

		control.runPendingTasks();

		int written = 0;

		while (sockets.get(0).readOutbound() != null) {
			written++;
		}

		assertThat(written, is(UdpAssociation.MAX_QUEUED));
		assertThat(handler.association().dropped(), is(5L));
	}

	@Example
	public void shouldQueueAgainOnceTheEventLoopHasCaughtUp() {
		final EchoTunnel.EchoAssociation association = associated();

		sendFrom(CLIENT, packet(REMOTE, new byte[] { 1 }));
		deliver(association, UdpAssociation.MAX_QUEUED + 5);
		control.runPendingTasks();
		deliver(association, UdpAssociation.MAX_QUEUED + 1);
		control.runPendingTasks();

		assertThat(written().size(), is(2 * UdpAssociation.MAX_QUEUED));
		assertThat(handler.association().dropped(), is(6L));
	}

	@Example
	public void shouldHearTheTunnelOnlyFromTheControlConnectionsLoop() {
		requestAssociation();

		final EchoTunnel.EchoAssociation association = tunnel.association();

		assertThat(control.config().isAutoRead(), is(false));
		association.open();
		assertThat(sockets, is(empty()));
		assertThat(control.readOutbound(), is(nullValue()));
		control.runPendingTasks();
		assertThat(reply(control).status(), is(Socks5CommandStatus.SUCCESS));
		sendFrom(CLIENT, packet(REMOTE, new byte[] { 1 }));
		association.deliver(REMOTE, new byte[] { 9 });
		association.finish();
		assertThat(sockets.get(0).readOutbound(), is(nullValue()));
		assertThat(control.isOpen(), is(true));
		control.runPendingTasks();

		assertThat(written().size(), is(1));
		assertThat(control.isOpen(), is(false));
		assertThat(association.closed, is(true));
		assertThat(association.calledUnderLock, is(false));
	}

	@Example
	public void shouldHearAFailureOnlyFromTheControlConnectionsLoop() {
		requestAssociation();
		tunnel.association().fail(TunnelFailure.HOST_UNREACHABLE);

		assertThat(control.readOutbound(), is(nullValue()));
		assertThat(control.isOpen(), is(true));
		control.runPendingTasks();
		assertThat(reply(control).status(), is(Socks5CommandStatus.HOST_UNREACHABLE));
	}

	/* The drops come from strangers, from the client's malformed datagrams and from the exit before the client
	   sent anything; nothing is logged but the one warning */
	@Example
	public void shouldWarnOnceWhenTheDropsFirstPassTheLimit() {
		final Queue<SubstituteLoggingEvent> events = new ArrayDeque<>();
		final UdpAssociation association = new UdpAssociation(control.pipeline().context(handler), binder(SOCKET),
			0, new SubstituteLogger("udp", events, false));

		association.start(tunnel, Optional.empty());
		tunnel.association().open();
		control.runPendingTasks();

		for (int i = 1; i < UdpAssociation.WARN_AFTER_DROPS; i += 3) {
			sendFrom(OTHER_HOST, packet(REMOTE, new byte[] { 1 }));
			sendFrom(CLIENT, new byte[] { 1 });
			association.received(REMOTE, new byte[] { 9 });
			control.runPendingTasks();
		}

		sendFrom(OTHER_HOST, packet(REMOTE, new byte[] { 1 }));
		assertThat(association.dropped(), is(UdpAssociation.WARN_AFTER_DROPS));
		assertThat(events, is(empty()));
		sendFrom(OTHER_HOST, packet(REMOTE, new byte[] { 1 }));
		assertThat(events.size(), is(1));
		assertThat(events.peek().getLevel(), is(Level.WARN));

		for (int i = 0; i < UdpAssociation.WARN_AFTER_DROPS; i++) {
			sendFrom(OTHER_HOST, packet(REMOTE, new byte[] { 1 }));
		}

		assertThat(association.dropped(), is(2 * UdpAssociation.WARN_AFTER_DROPS + 1));
		assertThat(events.size(), is(1));
	}

	@Example
	public void shouldEndWithTheControlConnection() {
		final EchoTunnel.EchoAssociation association = associated();

		control.close();

		assertThat(association.closed, is(true));
		assertThat(sockets.get(0).isOpen(), is(false));
	}

	@Example
	public void shouldCloseTheSocketEvenWhenTheTunnelCannotEndTheAssociation() {
		final EchoTunnel.EchoAssociation association = associated();

		association.closeBroken = true;
		Expect.assertThrows(IllegalStateException.class, handler.association()::close);

		assertThat(association.closed, is(true));
		assertThat(sockets.get(0).isOpen(), is(false));
	}

	@Example
	public void shouldEndWithItsSocket() {
		final EchoTunnel.EchoAssociation association = associated();

		sockets.get(0).close();

		assertThat(control.isOpen(), is(false));
		assertThat(association.closed, is(true));
	}

	@Example
	public void shouldCloseTheControlConnectionWhenTheTunnelEndsTheAssociation() {
		final EchoTunnel.EchoAssociation association = associated();

		association.finish();
		control.runPendingTasks();

		assertThat(control.isOpen(), is(false));
		assertThat(sockets.get(0).isOpen(), is(false));
	}

	@Example
	public void shouldCloseTheControlConnectionWhenTheTunnelFailsAnOpenAssociation() {
		final EchoTunnel.EchoAssociation association = associated();

		association.fail(TunnelFailure.GENERAL_FAILURE);
		control.runPendingTasks();

		assertThat(control.isOpen(), is(false));
		assertThat(control.readOutbound(), is(nullValue()));
		assertThat(sockets.get(0).isOpen(), is(false));
	}

	@Example
	public void shouldIgnoreWhatTheClientSendsOnTheControlConnection() {
		final EchoTunnel.EchoAssociation association = associated();
		final ByteBuf noise = Unpooled.wrappedBuffer(new byte[] { 1, 2, 3 });

		control.writeInbound(noise);

		assertThat(control.isOpen(), is(true));
		assertThat(association.sent, is(empty()));
		assertThat(control.readOutbound(), is(nullValue()));
	}

	@Example
	public void shouldHoldNothingOfWhatTheClientSendsOnTheControlConnection() {
		final ByteBuf early = Unpooled.wrappedBuffer(new byte[SocksCommandHandler.MAX_EARLY_BYTES + 1]);
		final ByteBuf later = Unpooled.wrappedBuffer(new byte[SocksCommandHandler.MAX_EARLY_BYTES + 1]);

		requestAssociation();
		control.writeInbound(early);
		tunnel.association().open();
		control.runPendingTasks();
		assertThat(reply(control).status(), is(Socks5CommandStatus.SUCCESS));
		control.writeInbound(later);

		assertThat(early.refCnt(), is(0));
		assertThat(later.refCnt(), is(0));
		assertThat(control.isOpen(), is(true));
		assertThat(tunnel.association().closed, is(false));
	}

	@Example
	public void shouldActOnNothingBehindBytesThatAreNoCommandInTheReadOfTheCommand() {
		final byte[] noise = new byte[SocksCommandHandler.MAX_EARLY_BYTES + 1];

		Arrays.fill(noise, (byte) 0x7f);
		control.writeInbound(Unpooled.wrappedBuffer(SocksCommandTest.request(Socks5CommandType.UDP_ASSOCIATE,
			UNSPECIFIED), Unpooled.wrappedBuffer(noise), SocksCommandTest.request(Socks5CommandType.CONNECT, REMOTE)));
		tunnel.association().open();
		control.runPendingTasks();

		assertThat(reply(control).status(), is(Socks5CommandStatus.SUCCESS));
		assertThat(control.isOpen(), is(true));
		assertThat(tunnel.association().closed, is(false));
		assertThat(tunnel.streams, is(empty()));
	}

	/* Bytes that cannot start a command leave no command to find in what follows them */
	@Example
	public void shouldActOnNothingBehindBytesThatAreNoCommand() {
		final EchoTunnel.EchoAssociation association = associated();

		control.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1, 2, 3 }));
		control.writeInbound(SocksCommandTest.request(Socks5CommandType.CONNECT, REMOTE));

		assertThat(control.isOpen(), is(true));
		assertThat(association.closed, is(false));
		assertThat(tunnel.streams, is(empty()));
		assertThat(tunnel.associations.size(), is(1));
	}

	@Property(tries = 3)
	public void shouldHangUpOnASecondCommand(@ForAll("commands") Socks5CommandType type) {
		final EchoTunnel.EchoAssociation association = associated();

		control.writeInbound(SocksCommandTest.request(type, REMOTE));

		assertThat(control.isOpen(), is(false));
		assertThat(control.readOutbound(), is(nullValue()));
		assertThat(association.closed, is(true));
		assertThat(sockets.get(0).isOpen(), is(false));
		assertThat(tunnel.streams, is(empty()));
		assertThat(tunnel.associations.size(), is(1));
	}

	@Example
	public void shouldHangUpOnASecondCommandThatTricklesIn() {
		final EchoTunnel.EchoAssociation association = associated();
		final byte[] command = ByteBufUtil.getBytes(SocksCommandTest.request(Socks5CommandType.CONNECT, NAMED));

		for (int i = 0; i < command.length - 1; i++) {
			control.writeInbound(Unpooled.wrappedBuffer(new byte[] { command[i] }));
		}

		assertThat(control.isOpen(), is(true));
		control.writeInbound(Unpooled.wrappedBuffer(new byte[] { command[command.length - 1] }));
		assertThat(control.isOpen(), is(false));
		assertThat(association.closed, is(true));
	}

	@Example
	public void shouldHangUpOnASecondCommandInTheSameRead() {
		control.writeInbound(Unpooled.wrappedBuffer(SocksCommandTest.request(Socks5CommandType.UDP_ASSOCIATE,
			UNSPECIFIED), SocksCommandTest.request(Socks5CommandType.CONNECT, REMOTE)));
		tunnel.association().open();
		control.runPendingTasks();

		assertThat(control.isOpen(), is(false));
		assertThat(control.readOutbound(), is(nullValue()));
		assertThat(tunnel.association().closed, is(true));
		assertThat(sockets, is(empty()));
		assertThat(tunnel.streams, is(empty()));
	}

	@Example
	public void shouldHangUpOnASecondCommandBeforeTheTunnelAnswered() {
		requestAssociation();
		control.writeInbound(SocksCommandTest.request(Socks5CommandType.UDP_ASSOCIATE, UNSPECIFIED));
		tunnel.association().open();
		control.runPendingTasks();

		assertThat(control.isOpen(), is(false));
		assertThat(control.readOutbound(), is(nullValue()));
		assertThat(tunnel.associations.size(), is(1));
		assertThat(tunnel.association().closed, is(true));
		assertThat(sockets, is(empty()));
	}

	@Example
	public void shouldBindNothingForAClientThatLeftBeforeTheTunnelOpened() {
		control.writeInbound(SocksCommandTest.request(Socks5CommandType.UDP_ASSOCIATE, UNSPECIFIED));
		control.close();
		tunnel.association().open();
		control.runPendingTasks();

		assertThat(sockets, is(empty()));
		assertThat(tunnel.association().closed, is(true));
	}

	@Example
	public void shouldCloseASocketThatWasBoundAfterTheClientLeft() {
		final Endpoints late = new Endpoints(SOCKET, null);
		final ChannelPromise binding = late.newPromise();

		connect((connection, socketHandler) -> binding);
		control.writeInbound(SocksCommandTest.request(Socks5CommandType.UDP_ASSOCIATE, UNSPECIFIED));
		tunnel.association().open();
		control.runPendingTasks();
		control.close();
		binding.setSuccess();
		control.runPendingTasks();

		assertThat(late.isOpen(), is(false));
		assertThat(control.readOutbound(), is(nullValue()));
	}
}
