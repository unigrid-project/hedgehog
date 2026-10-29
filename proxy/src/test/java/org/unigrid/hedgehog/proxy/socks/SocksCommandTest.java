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
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5ClientEncoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.util.NetUtil;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class SocksCommandTest {
	private static final SocksAddress TARGET = new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 443);
	private static final SocksAddress BOUND = new SocksAddress(Socks5AddressType.IPv4, "192.0.2.7", 40_000);

	private EchoTunnel tunnel;
	private EmbeddedChannel channel;

	@BeforeTry
	public void connectClient() {
		tunnel = new EchoTunnel();
		channel = new EmbeddedChannel(new Socks5CommandRequestDecoder(), new SocksCommandHandler(tunnel));
	}

	static ByteBuf request(Socks5CommandType type, SocksAddress destination) {
		final EmbeddedChannel encoder = new EmbeddedChannel(Socks5ClientEncoder.DEFAULT);

		encoder.writeOutbound(new DefaultSocks5CommandRequest(type, destination.type(), destination.host(),
			destination.port()));
		return encoder.readOutbound();
	}

	static byte[] readBytes(EmbeddedChannel channel) {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		for (ByteBuf buffer = channel.readOutbound(); buffer != null; buffer = channel.readOutbound()) {
			bytes.writeBytes(ByteBufUtil.getBytes(buffer));
			buffer.release();
		}

		return bytes.toByteArray();
	}

	private static void assertReply(Socks5CommandResponse reply, Socks5CommandStatus status, SocksAddress bound) {
		assertThat(reply.status(), is(status));
		assertThat(new SocksAddress(reply.bndAddrType(), reply.bndAddr(), reply.bndPort()), equalTo(bound));
	}

	private Socks5CommandResponse reply() {
		return channel.readOutbound();
	}

	private EchoTunnel.EchoStream opened() {
		channel.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		tunnel.stream().connect(BOUND);
		channel.runPendingTasks();
		assertThat(reply().status(), is(Socks5CommandStatus.SUCCESS));
		return tunnel.stream();
	}

	/* The client sends bytes ahead of the reply, which must reach the stream in order once it opens at the bound
	   address, with reads resumed as the stream takes writes */
	private Socks5CommandResponse openedAheadOfTheReply(SocksAddress bound) {
		channel.writeInbound(Unpooled.wrappedBuffer(request(Socks5CommandType.CONNECT, TARGET),
			Unpooled.wrappedBuffer(new byte[] { 1, 2 })));
		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 3 }));
		tunnel.stream().connect(bound);
		channel.runPendingTasks();

		final Socks5CommandResponse reply = reply();

		assertThat(tunnel.stream().written.toByteArray(), equalTo(new byte[] { 1, 2, 3 }));
		assertThat(readBytes(channel), equalTo(new byte[] { 1, 2, 3 }));
		assertThat(channel.config().isAutoRead(), is(true));
		return reply;
	}

	private EmbeddedChannel heldClient(HeldWrites writes) {
		return new EmbeddedChannel(writes, new Socks5CommandRequestDecoder(), new SocksCommandHandler(tunnel));
	}

	@Provide
	public Arbitrary<SocksAddress> destinations() {
		final Arbitrary<Integer> ports = Arbitraries.integers().between(0, 0xFFFF);
		final Arbitrary<String> ipv4 = Arbitraries.bytes().array(byte[].class).ofSize(4)
			.map(NetUtil::bytesToIpAddress);
		final Arbitrary<String> ipv6 = Arbitraries.bytes().array(byte[].class).ofSize(16)
			.map(NetUtil::bytesToIpAddress);
		final Arbitrary<String> domains = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(63)
			.map(label -> label + ".test");

		return Arbitraries.oneOf(addresses(Socks5AddressType.IPv4, ipv4, ports),
			addresses(Socks5AddressType.IPv6, ipv6, ports), addresses(Socks5AddressType.DOMAIN, domains, ports));
	}

	private static Arbitrary<SocksAddress> addresses(Socks5AddressType type, Arbitrary<String> hosts,
		Arbitrary<Integer> ports) {
		return Combinators.combine(hosts, ports).as((host, port) -> new SocksAddress(type, host, port));
	}

	@Property(tries = 100)
	public void shouldOpenAStreamToTheRequestedDestination(@ForAll("destinations") SocksAddress destination) {
		channel.writeInbound(request(Socks5CommandType.CONNECT, destination));
		assertThat(tunnel.stream().destination, equalTo(destination));
		assertThat(channel.config().isAutoRead(), is(false));
		assertThat(channel.readOutbound(), is(nullValue()));
		assertThat(channel.pipeline().get(Socks5CommandRequestDecoder.class), is(nullValue()));
	}

	@Example
	public void shouldOpenAStreamToTheLongestDomainName() {
		final String label = "a".repeat(63);
		final SocksAddress longest = new SocksAddress(Socks5AddressType.DOMAIN, String.join(".", label, label, label,
			label), 443);

		channel.writeInbound(request(Socks5CommandType.CONNECT, longest));
		assertThat(tunnel.stream().destination, equalTo(longest));
	}

	@Example
	public void shouldOpenAStreamToTheShortestDomainName() {
		final SocksAddress shortest = new SocksAddress(Socks5AddressType.DOMAIN, "a", 443);

		channel.writeInbound(request(Socks5CommandType.CONNECT, shortest));
		assertThat(tunnel.stream().destination, equalTo(shortest));
	}

	@Example
	public void shouldRefuseADomainNameSpeltAsAnIpAddress() {
		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 5, 1, 0, 3, 5, '1', '2', '7', '.', '1', 0, 80 }));
		assertReply(reply(), Socks5CommandStatus.ADDRESS_UNSUPPORTED, SocksAddress.UNSPECIFIED);
		assertThat(channel.isOpen(), is(false));
		assertThat(tunnel.streams, is(empty()));
	}

	@Example
	public void shouldRefuseAnEmptyDomainName() {
		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 5, 1, 0, 3, 0, 0, 80 }));
		assertThat(reply().status(), is(Socks5CommandStatus.ADDRESS_UNSUPPORTED));
		assertThat(channel.isOpen(), is(false));
		assertThat(tunnel.streams, is(empty()));
	}

	/* The application learns nothing of the exit: where the stream is bound is for the tunnel alone to know */
	@Example
	public void connectReplyNeverNamesTheExit() {
		channel.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		tunnel.stream().connect(new SocksAddress(Socks5AddressType.IPv4, "203.0.113.5", 4000));
		channel.runPendingTasks();

		final Socks5CommandResponse reply = reply();

		assertThat(reply.status(), is(Socks5CommandStatus.SUCCESS));
		assertThat(reply.bndAddrType(), is(Socks5AddressType.IPv4));
		assertThat(reply.bndAddr(), is("0.0.0.0"));
		assertThat(reply.bndPort(), is(0));
		assertThat(channel.config().isAutoRead(), is(true));
		assertThat(channel.readOutbound(), is(nullValue()));
	}

	@Property(tries = 50)
	public void shouldReplyWithTheUnspecifiedAddressWhateverTheTunnelReports(@ForAll("destinations")
		SocksAddress bound) {

		assertReply(openedAheadOfTheReply(bound), Socks5CommandStatus.SUCCESS, SocksAddress.UNSPECIFIED);
	}

	/* A name of 64 characters in a label is more than a reply could carry, and 63 is the most it could */
	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void shouldReplyWithTheUnspecifiedAddressForANameOfAnyLabelLength(@ForAll @IntRange(min = 63, max = 64)
		int label) {

		final SocksAddress bound = new SocksAddress(Socks5AddressType.DOMAIN, "b".repeat(label) + ".test", 40_000);

		assertReply(openedAheadOfTheReply(bound), Socks5CommandStatus.SUCCESS, SocksAddress.UNSPECIFIED);
	}

	@Example
	public void shouldReplyOnlyOnceToAStreamThatOpensTwice() {
		channel.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		tunnel.stream().connect(BOUND);
		tunnel.stream().connect(BOUND);
		channel.runPendingTasks();
		assertThat(reply().status(), is(Socks5CommandStatus.SUCCESS));
		assertThat(channel.readOutbound(), is(nullValue()));
	}

	@Property(tries = 8)
	public void shouldTellTheClientWhyTheStreamFailed(@ForAll TunnelFailure failure) {
		channel.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		tunnel.stream().fail(failure);
		channel.runPendingTasks();
		assertReply(reply(), failure.status(), SocksAddress.UNSPECIFIED);
		assertThat(channel.readOutbound(), is(nullValue()));
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void shouldNotOpenAStreamThatFailed() {
		final HeldWrites writes = new HeldWrites();
		final EmbeddedChannel client = heldClient(writes);

		client.writeInbound(Unpooled.wrappedBuffer(request(Socks5CommandType.CONNECT, TARGET),
			Unpooled.wrappedBuffer(new byte[] { 1, 2 })));
		tunnel.stream().fail(TunnelFailure.HOST_UNREACHABLE);
		tunnel.stream().connect(BOUND);
		client.runPendingTasks();
		assertThat(client.config().isAutoRead(), is(false));
		assertThat(tunnel.stream().written.size(), is(0));
		writes.release();
		assertReply(client.readOutbound(), Socks5CommandStatus.HOST_UNREACHABLE, SocksAddress.UNSPECIFIED);
		assertThat(client.readOutbound(), is(nullValue()));
		assertThat(client.isOpen(), is(false));
	}

	@Example
	public void shouldReplyOnlyOnceToAStreamThatFailsAgainAndEnds() {
		channel.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		tunnel.stream().fail(TunnelFailure.HOST_UNREACHABLE);
		tunnel.stream().fail(TunnelFailure.CONNECTION_REFUSED);
		tunnel.stream().finish();
		channel.runPendingTasks();
		assertReply(reply(), Socks5CommandStatus.HOST_UNREACHABLE, SocksAddress.UNSPECIFIED);
		assertThat(channel.readOutbound(), is(nullValue()));
	}

	@Example
	public void shouldHandTheCountryOfTheConnectionToTheTunnel() {
		channel.attr(SocksPasswordHandler.COUNTRY).set("SE");
		channel.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		assertThat(tunnel.stream().country, equalTo(Optional.of("SE")));
	}

	@Example
	public void shouldAskForNoCountryOnBehalfOfAConnectionThatNamedNone() {
		channel.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		assertThat(tunnel.stream().country, equalTo(Optional.empty()));
	}

	@Example
	public void shouldFailACommandTheTunnelThrowsOn() {
		final EmbeddedChannel client = new EmbeddedChannel(new Socks5CommandRequestDecoder(),
			new SocksCommandHandler((destination, country, listener) -> {
				throw new IllegalStateException("The tunnel is down");
			}));

		client.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		assertReply(client.readOutbound(), Socks5CommandStatus.FAILURE, SocksAddress.UNSPECIFIED);
		assertThat(client.readOutbound(), is(nullValue()));
		assertThat(client.isOpen(), is(false));
	}

	@Example
	public void shouldRefuseToBindOnBehalfOfTheClient() {
		channel.writeInbound(request(Socks5CommandType.BIND, TARGET));
		assertReply(reply(), Socks5CommandStatus.COMMAND_UNSUPPORTED, SocksAddress.UNSPECIFIED);
		assertThat(channel.readOutbound(), is(nullValue()));
		assertThat(channel.isOpen(), is(false));
		assertThat(tunnel.streams, is(empty()));
		assertThat(tunnel.associations, is(empty()));
	}

	@Example
	public void shouldRefuseACommandSocksDoesNotDefine() {
		channel.writeInbound(request(Socks5CommandType.valueOf((byte) 9), TARGET));
		assertReply(reply(), Socks5CommandStatus.COMMAND_UNSUPPORTED, SocksAddress.UNSPECIFIED);
		assertThat(channel.readOutbound(), is(nullValue()));
		assertThat(channel.isOpen(), is(false));
		assertThat(tunnel.streams, is(empty()));
		assertThat(tunnel.associations, is(empty()));
	}

	/* The refusal is held back, as a socket that has not sent it yet would, so that the bytes behind the command
	   reach the handler before the close takes the pipeline down */
	@Example
	public void shouldReleaseWhatTheClientSendsBehindARefusedCommand() {
		final HeldWrites writes = new HeldWrites();
		final EmbeddedChannel client = heldClient(writes);
		final ByteBuf command = Unpooled.copiedBuffer(request(Socks5CommandType.BIND, TARGET),
			Unpooled.wrappedBuffer(new byte[] { 1, 2 }));

		client.writeInbound(command);
		assertThat(command.refCnt(), is(0));
		writes.release();
		assertReply(client.readOutbound(), Socks5CommandStatus.COMMAND_UNSUPPORTED, SocksAddress.UNSPECIFIED);
		assertThat(client.isOpen(), is(false));
	}

	@Example
	public void shouldLetTheClientLeaveBeforeItsCommand() {
		channel.close();
		channel.checkException();
		assertThat(channel.readOutbound(), is(nullValue()));
		assertThat(tunnel.streams, is(empty()));
	}

	@Example
	public void shouldRefuseADomainNameSocksAddressesCannotHold() {
		channel.writeInbound(request(Socks5CommandType.CONNECT, TARGET).setByte(5, ' '));
		assertThat(reply().status(), is(Socks5CommandStatus.ADDRESS_UNSUPPORTED));
		assertThat(channel.isOpen(), is(false));
		assertThat(tunnel.streams, is(empty()));
	}

	@Example
	public void shouldAnswerAnUndecodableCommandWithAGeneralFailure() {
		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 5, 1, 0, 9, 1, 2, 3, 4, 0, 80 }));
		assertReply(reply(), Socks5CommandStatus.FAILURE, SocksAddress.UNSPECIFIED);
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void shouldRelayBytesBothWays() {
		final EchoTunnel.EchoStream stream = opened();

		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1, 2, 3 }));
		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 4 }));
		assertThat(stream.written.toByteArray(), equalTo(new byte[] { 1, 2, 3, 4 }));
		assertThat(readBytes(channel), equalTo(new byte[] { 1, 2, 3, 4 }));
	}

	@Example
	public void shouldHoldWhatTheClientSendsAheadOfTheReply() {
		final ByteBuf command = request(Socks5CommandType.CONNECT, TARGET);

		channel.writeInbound(Unpooled.wrappedBuffer(command, Unpooled.wrappedBuffer(new byte[] { 1, 2 })));
		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 3 }));
		assertThat(tunnel.stream().written.size(), is(0));
		tunnel.stream().connect(BOUND);
		channel.runPendingTasks();
		assertThat(reply().status(), is(Socks5CommandStatus.SUCCESS));
		assertThat(tunnel.stream().written.toByteArray(), equalTo(new byte[] { 1, 2, 3 }));
	}

	@Example
	public void shouldCloseTheStreamWhenTheClientLeaves() {
		final EchoTunnel.EchoStream stream = opened();

		channel.close();
		assertThat(stream.closed, is(true));
	}

	@Example
	public void shouldCloseTheClientAfterWhatTheStreamSentLast() {
		final EchoTunnel.EchoStream stream = opened();

		stream.listener.received(new byte[] { 9, 8 });
		stream.finish();
		channel.runPendingTasks();
		assertThat(readBytes(channel), equalTo(new byte[] { 9, 8 }));
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void shouldStopRelayingOnceTheStreamEnds() {
		final EmbeddedChannel client = heldClient(new HeldWrites());
		final ByteBuf late = Unpooled.wrappedBuffer(new byte[] { 1 });

		client.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		tunnel.stream().connect(BOUND);
		client.runPendingTasks();
		tunnel.stream().finish();
		tunnel.stream().takeWrites(true);
		client.runPendingTasks();
		assertThat(client.config().isAutoRead(), is(false));
		client.writeInbound(late);
		assertThat(late.refCnt(), is(0));
		assertThat(tunnel.stream().written.size(), is(0));
		assertThat(client.config().isAutoRead(), is(false));
	}

	@Example
	public void shouldNotReopenAStreamThatEnded() {
		final EmbeddedChannel client = heldClient(new HeldWrites());

		client.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		tunnel.stream().connect(BOUND);
		client.runPendingTasks();
		tunnel.stream().finish();
		client.runPendingTasks();
		client.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1 }));
		tunnel.stream().connect(BOUND);
		client.runPendingTasks();
		assertThat(tunnel.stream().written.size(), is(0));
		assertThat(client.config().isAutoRead(), is(false));
	}

	@Example
	public void shouldHangUpOnAStreamThatBreaksOnAWrite() {
		final EchoTunnel.EchoStream stream = opened();

		stream.broken = true;
		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1 }));
		assertThat(channel.isOpen(), is(false));
		assertThat(stream.closed, is(true));
	}

	@Example
	public void shouldHangUpOnAStreamThatBreaksOnWhatTheClientSentAhead() {
		channel.writeInbound(Unpooled.wrappedBuffer(request(Socks5CommandType.CONNECT, TARGET),
			Unpooled.wrappedBuffer(new byte[] { 1 })));
		tunnel.stream().broken = true;
		tunnel.stream().connect(BOUND);
		channel.runPendingTasks();
		assertThat(reply().status(), is(Socks5CommandStatus.SUCCESS));
		assertThat(channel.isOpen(), is(false));
		assertThat(tunnel.stream().closed, is(true));
	}

	@Example
	public void shouldFailAStreamThatEndsBeforeItOpens() {
		channel.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		tunnel.stream().finish();
		channel.runPendingTasks();
		assertThat(reply().status(), is(Socks5CommandStatus.FAILURE));
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void shouldStopReadingWhileTheStreamTakesNoMoreWrites() {
		final EchoTunnel.EchoStream stream = opened();

		stream.takeWrites(false);
		channel.runPendingTasks();
		assertThat(channel.config().isAutoRead(), is(false));
		stream.takeWrites(true);
		channel.runPendingTasks();
		assertThat(channel.config().isAutoRead(), is(true));
		stream.writable = false;
		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1 }));
		assertThat(channel.config().isAutoRead(), is(false));
	}

	@Example
	public void shouldKeepReadsStoppedWhenTheStreamOpensFull() {
		channel.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		tunnel.stream().writable = false;
		tunnel.stream().connect(BOUND);
		channel.runPendingTasks();
		assertThat(reply().status(), is(Socks5CommandStatus.SUCCESS));
		assertThat(channel.config().isAutoRead(), is(false));
	}

	@Example
	public void shouldHearTheTunnelOnlyFromTheChannelsLoop() {
		channel.writeInbound(request(Socks5CommandType.CONNECT, TARGET));

		final TunnelListener listener = tunnel.stream().listener;

		listener.connected(BOUND);
		assertThat(channel.readOutbound(), is(nullValue()));
		channel.runPendingTasks();
		assertThat(reply().status(), is(Socks5CommandStatus.SUCCESS));

		listener.received(new byte[] { 1 });
		listener.writabilityChanged(false);
		listener.closed();
		assertThat(channel.readOutbound(), is(nullValue()));
		assertThat(channel.config().isAutoRead(), is(true));
		assertThat(channel.isOpen(), is(true));
		channel.runPendingTasks();
		assertThat(readBytes(channel), equalTo(new byte[] { 1 }));
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void shouldHearAFailureOnlyFromTheChannelsLoop() {
		channel.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		tunnel.stream().fail(TunnelFailure.HOST_UNREACHABLE);
		assertThat(channel.readOutbound(), is(nullValue()));
		assertThat(channel.isOpen(), is(true));
		channel.runPendingTasks();
		assertThat(reply().status(), is(Socks5CommandStatus.HOST_UNREACHABLE));
	}

	@Example
	public void shouldOpenAStreamWhoseTunnelConnectsBeforeOpenReturns() {
		final Tunnel eager = (destination, country, listener) -> {
			final TunnelStream stream = tunnel.open(destination, country, listener);

			listener.connected(BOUND);
			return stream;
		};
		final EmbeddedChannel client = new EmbeddedChannel(new Socks5CommandRequestDecoder(),
			new SocksCommandHandler(eager));

		client.writeInbound(Unpooled.wrappedBuffer(request(Socks5CommandType.CONNECT, TARGET),
			Unpooled.wrappedBuffer(new byte[] { 1, 2 })));
		client.runPendingTasks();
		assertThat(((Socks5CommandResponse) client.readOutbound()).status(), is(Socks5CommandStatus.SUCCESS));
		assertThat(tunnel.stream().written.toByteArray(), equalTo(new byte[] { 1, 2 }));
	}

	@Example
	public void shouldNotWriteToAStreamWhoseClientLeftBeforeTheReply() {
		channel.writeInbound(Unpooled.wrappedBuffer(request(Socks5CommandType.CONNECT, TARGET),
			Unpooled.wrappedBuffer(new byte[] { 1, 2 })));
		channel.close();
		tunnel.stream().connect(BOUND);
		channel.runPendingTasks();
		assertThat(tunnel.stream().closed, is(true));
		assertThat(tunnel.stream().written.size(), is(0));
	}

	@Example
	public void shouldKeepTheClientWaitingForItsReplyUntilTheStreamOpens() {
		channel.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		tunnel.stream().takeWrites(true);
		tunnel.stream().listener.received(new byte[] { 1 });
		channel.runPendingTasks();
		assertThat(channel.config().isAutoRead(), is(false));
		assertThat(channel.readOutbound(), is(nullValue()));
	}

	@Example
	public void shouldIgnoreAFailureAfterTheStreamOpened() {
		final EchoTunnel.EchoStream stream = opened();

		stream.fail(TunnelFailure.GENERAL_FAILURE);
		channel.runPendingTasks();
		assertThat(channel.readOutbound(), is(nullValue()));
		assertThat(channel.isOpen(), is(true));
	}

	@Example
	public void shouldHangUpOnlyOnceTheFailureIsSent() {
		final HeldWrites writes = new HeldWrites();
		final EmbeddedChannel client = new EmbeddedChannel(writes, new Socks5CommandRequestDecoder(),
			new SocksCommandHandler(tunnel));

		client.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		tunnel.stream().fail(TunnelFailure.HOST_UNREACHABLE);
		client.runPendingTasks();
		assertThat(client.isOpen(), is(true));
		writes.release();
		assertThat(((Socks5CommandResponse) client.readOutbound()).status(),
			is(Socks5CommandStatus.HOST_UNREACHABLE));
		assertThat(client.isOpen(), is(false));
	}

	@Example
	public void shouldHangUpOnlyOnceWhatTheStreamSentLastIsSent() {
		final HeldWrites writes = new HeldWrites();
		final EmbeddedChannel client = new EmbeddedChannel(writes, new Socks5CommandRequestDecoder(),
			new SocksCommandHandler(tunnel));

		client.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		tunnel.stream().connect(BOUND);
		client.runPendingTasks();
		writes.release();
		assertThat(((Socks5CommandResponse) client.readOutbound()).status(), is(Socks5CommandStatus.SUCCESS));
		tunnel.stream().listener.received(new byte[] { 9, 8 });
		tunnel.stream().finish();
		client.runPendingTasks();
		assertThat(client.isOpen(), is(true));
		writes.release();
		assertThat(readBytes(client), equalTo(new byte[] { 9, 8 }));
		assertThat(client.isOpen(), is(false));
	}

	@Example
	public void shouldReleaseWhatTheClientSends() {
		final ByteBuf early = Unpooled.wrappedBuffer(new byte[] { 1 });
		final ByteBuf later = Unpooled.wrappedBuffer(new byte[] { 2 });

		channel.writeInbound(request(Socks5CommandType.CONNECT, TARGET));
		channel.writeInbound(early);
		tunnel.stream().connect(BOUND);
		channel.runPendingTasks();
		channel.writeInbound(later);
		assertThat(early.refCnt(), is(0));
		assertThat(later.refCnt(), is(0));
	}

	@Example
	public void shouldReleaseAMessageItDoesNotKnow() {
		final ByteBufHolder unknown = new DefaultByteBufHolder(Unpooled.buffer(1));

		channel.writeInbound(unknown);
		assertThat(unknown.refCnt(), is(0));
		assertThat(channel.readInbound(), is(nullValue()));
		assertThat(tunnel.streams, is(empty()));
	}
}
