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

package org.unigrid.hedgehog.proxy.exit;

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.util.NetUtil;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Example;
import org.slf4j.event.Level;
import org.slf4j.event.SubstituteLoggingEvent;
import org.slf4j.helpers.SubstituteLogger;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.Close;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.Connect;
import org.unigrid.hedgehog.proxy.session.Connected;
import org.unigrid.hedgehog.proxy.session.Data;
import org.unigrid.hedgehog.proxy.session.Datagram;
import org.unigrid.hedgehog.proxy.session.DestinationPolicy;
import org.unigrid.hedgehog.proxy.session.Resolver;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;

public class ExitRelayDatagramTest {
	private static final SocksAddress LITERAL = new SocksAddress(Socks5AddressType.IPv4, "93.184.215.14", 53);
	private static final SocksAddress NAME = new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 53);
	private static final SocksAddress IPV6 = new SocksAddress(Socks5AddressType.IPv6, "2606:2800:21f:cb07::1", 53);
	private static final SocksAddress LOCAL = new SocksAddress(Socks5AddressType.IPv4, "10.0.0.1", 53);
	private static final Connect ASSOCIATE = new Connect(Socks5CommandType.UDP_ASSOCIATE, SocksAddress.UNSPECIFIED);
	private static final ProxySettings CONNECT_ONLY = new ProxySettings(8, 3600, 4, 16, 32L * 1024 * 1024, 30, 16 * 1024,
		300, ProxySettings.CONNECT);
	private static final ProxySettings UDP_ONLY = new ProxySettings(8, 3600, 4, 16, 32L * 1024 * 1024, 30, 16 * 1024,
		300, ProxySettings.UDP);
	private static final Resolver NEVER = host -> {
		throw new AssertionError("Nothing to resolve, asked for " + host);
	};

	private final FakeUdpConnector udp = new FakeUdpConnector();
	private final Queue<SubstituteLoggingEvent> events = new ArrayDeque<>();

	private ExitRelay relay(SessionPair pair, ProxySettings settings, Resolver resolver) {
		return relay(pair, settings, resolver, udp);
	}

	private ExitRelay relay(SessionPair pair, ProxySettings settings, Resolver resolver, UdpConnector connector) {
		final ExitRelay relay = new ExitRelay(pair.exit, new ExitContext(settings, DestinationPolicy.publicOnly(),
			resolver, Runnable::run, new FakeConnector(), connector, pair.clock::get),
			new SubstituteLogger("exit", events, false));

		pair.toExit().forEach(relay::handle);
		return relay;
	}

	/* Opens the socket the relay asked for and checks what the client hears of it */
	private FakeUdpConnector.FakeSocket associated(SessionPair pair) {
		final FakeUdpConnector.FakeSocket socket = new FakeUdpConnector.FakeSocket();

		udp.last().opened(socket);
		assertThat(pair.toClient(), equalTo(List.of(new Connected(Socks5CommandStatus.SUCCESS,
			SocksAddress.UNSPECIFIED))));
		return socket;
	}

	private static void send(SessionPair pair, ExitRelay relay, SealedMessage message) {
		pair.client.send(message);
		pair.toExit().forEach(relay::handle);
	}

	private static InetSocketAddress source(String literal, int port) throws UnknownHostException {
		return new InetSocketAddress(ExitRelayTest.address(literal), port);
	}

	private static List<SealedMessage> refusal(Socks5CommandStatus status, CloseReason reason) {
		return List.of(new Connected(status, SocksAddress.UNSPECIFIED), new Close(reason));
	}

	private static Thread started(Runnable work) {
		final Thread thread = new Thread(work);

		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	@Example
	public void shouldOpenASocketAndAnswerWhenItIsThere() {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);

		assertThat(udp.requests.size(), is(1));
		assertThat(pair.toClient(), is(empty()));

		final FakeUdpConnector.FakeSocket socket = associated(pair);

		assertThat(socket.readable, is(true));
		assertThat(relay.isOpen(), is(true));
		assertThat(events, is(empty()));
	}

	@Example
	public void shouldRefuseTheAssociationWhileTheSporkLacksTheUdpFlag() {
		final SessionPair pair = new SessionPair(ASSOCIATE);

		relay(pair, CONNECT_ONLY, NEVER);

		assertThat(pair.toClient(), equalTo(List.of(new Connected(Socks5CommandStatus.FORBIDDEN,
			SocksAddress.UNSPECIFIED), new Close(CloseReason.REFUSED))));
		assertThat(udp.requests, is(empty()));
	}

	@Example
	public void shouldAssociateWhenTheSporkAllowsDatagramsAlone() {
		final SessionPair pair = new SessionPair(ASSOCIATE);

		relay(pair, UDP_ONLY, NEVER);

		assertThat(udp.requests.size(), is(1));
		associated(pair);
	}

	@Example
	public void shouldTellTheClientWhenNoSocketCouldBeOpened() {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);

		udp.last().failed(Socks5CommandStatus.FAILURE);

		assertThat(pair.toClient(), equalTo(List.of(new Connected(Socks5CommandStatus.FAILURE,
			SocksAddress.UNSPECIFIED), new Close(CloseReason.UNREACHABLE))));
		assertThat(relay.isOpen(), is(false));
	}

	/* The log names the class of the failure alone, as its message may quote an address */
	@Example
	public void shouldAnswerOnceWhenTheConnectorThrows() {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER, listener -> {
			throw new IllegalStateException("No socket to reach example.org");
		});

		assertThat(pair.toClient(), equalTo(refusal(Socks5CommandStatus.FAILURE, CloseReason.UNREACHABLE)));
		assertThat(relay.isOpen(), is(false));
		assertThat(events.size(), is(1));

		final SubstituteLoggingEvent warning = events.peek();

		assertThat(warning.getLevel(), is(Level.WARN));
		assertThat(warning.getArgumentArray(), equalTo(new Object[] { IllegalStateException.class.getName() }));
		assertThat(warning.getMessage(), not(containsString("example.org")));
	}

	@Example
	public void shouldTakeTheConnectorsFirstAnswerOnly() {
		final SessionPair failing = new SessionPair(ASSOCIATE);
		final FakeUdpConnector.FakeSocket late = new FakeUdpConnector.FakeSocket();
		final ExitRelay refused = relay(failing, ProxySettings.DEFAULTS, NEVER, listener -> {
			listener.failed(Socks5CommandStatus.NETWORK_UNREACHABLE);
			listener.opened(late);
			listener.failed(Socks5CommandStatus.FAILURE);
		});

		assertThat(failing.toClient(), equalTo(refusal(Socks5CommandStatus.NETWORK_UNREACHABLE,
			CloseReason.UNREACHABLE)));
		assertThat(late.closed, is(true));
		assertThat(refused.isOpen(), is(false));

		final SessionPair opening = new SessionPair(ASSOCIATE);
		final FakeUdpConnector.FakeSocket first = new FakeUdpConnector.FakeSocket();
		final FakeUdpConnector.FakeSocket second = new FakeUdpConnector.FakeSocket();
		final ExitRelay opened = relay(opening, ProxySettings.DEFAULTS, NEVER, listener -> {
			listener.opened(first);
			listener.failed(Socks5CommandStatus.FAILURE);
			listener.opened(second);
			throw new IllegalStateException("Opened after all");
		});

		assertThat(opening.toClient(), equalTo(List.of(new Connected(Socks5CommandStatus.SUCCESS,
			SocksAddress.UNSPECIFIED))));
		assertThat(opened.isOpen(), is(true));
		assertThat(first.closed, is(false));
		assertThat(second.closed, is(true));
	}

	/* A connector that answers on a thread of its own and waits for it would hang a relay that held its lock
	   around the open */
	@Example
	public void shouldOpenTheSocketWithoutHoldingTheRelay() {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final FakeUdpConnector.FakeSocket socket = new FakeUdpConnector.FakeSocket();
		final List<Boolean> answering = new ArrayList<>();

		relay(pair, ProxySettings.DEFAULTS, NEVER, listener -> {
			final Thread answer = started(() -> listener.opened(socket));

			try {
				answer.join(RecordingTargetListener.TIMEOUT_MILLIS);
			} catch (InterruptedException ex) {
				throw new IllegalStateException(ex);
			}

			answering.add(answer.isAlive());
		});

		assertThat(answering, equalTo(List.of(false)));
		assertThat(pair.toClient(), equalTo(List.of(new Connected(Socks5CommandStatus.SUCCESS,
			SocksAddress.UNSPECIFIED))));
		assertThat(socket.readable, is(true));
	}

	@Example
	public void shouldSendTheDatagramsOfTheClientToTheAddressOfTheirLiteral() throws UnknownHostException {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket socket = associated(pair);

		send(pair, relay, new Datagram(LITERAL, new byte[] { 1, 2, 3 }));

		assertThat(socket.sent.size(), is(1));
		assertThat(socket.sent.get(0).address(), equalTo(ExitRelayTest.address("93.184.215.14")));
		assertThat(socket.sent.get(0).port(), is(53));
		assertThat(socket.sent.get(0).bytes(), equalTo(new byte[] { 1, 2, 3 }));
		assertThat(udp.requests.size(), is(1));
	}

	@Example
	public void shouldResolveANameOnceAndSendToTheAddressItChecked() throws UnknownHostException {
		final AtomicInteger lookups = new AtomicInteger();
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, host -> {
			lookups.incrementAndGet();
			return List.of(ExitRelayTest.address("93.184.215.14"), ExitRelayTest.address("2606:2800:21f::1"));
		});
		final FakeUdpConnector.FakeSocket socket = associated(pair);

		send(pair, relay, new Datagram(NAME, new byte[] { 1 }));
		send(pair, relay, new Datagram(NAME, new byte[] { 2 }));

		assertThat(lookups.get(), is(1));
		assertThat(socket.sent.stream().map(FakeUdpConnector.FakeSocket.Sent::address).toList(),
			equalTo(List.of(ExitRelayTest.address("93.184.215.14"), ExitRelayTest.address("93.184.215.14"))));
	}

	@Example
	public void shouldDropDatagramsToLocalDestinationsAndCountThem() {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket socket = associated(pair);

		for (String local : List.of("127.0.0.1", "10.0.0.1", "169.254.169.254", "::1", "::ffff:192.168.0.1")) {
			send(pair, relay, new Datagram(new SocksAddress(local.contains(":") ? Socks5AddressType.IPv6
				: Socks5AddressType.IPv4, local, 53), new byte[] { 1 }));
		}

		assertThat(socket.sent, is(empty()));
		assertThat(relay.droppedDatagrams(), is(5L));
		assertThat(relay.isOpen(), is(true));
	}

	@Example
	public void shouldSendADatagramThatFillsAnEntry() {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket socket = associated(pair);
		final int largest = pair.exit.maxDataBytes() - LITERAL.encodedSize();

		send(pair, relay, new Datagram(LITERAL, new byte[largest]));

		assertThat(socket.sent.get(0).bytes().length, is(largest));
		assertThat(relay.droppedDatagrams(), is(0L));
	}

	@Example
	public void shouldCountWhatTheSocketCannotTake() {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket socket = associated(pair);

		socket.accepting = false;
		send(pair, relay, new Datagram(LITERAL, new byte[] { 1 }));

		assertThat(relay.droppedDatagrams(), is(1L));
	}

	@Example
	public void shouldRelayRepliesOnlyFromWhereItSent() throws UnknownHostException {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket socket = associated(pair);

		send(pair, relay, new Datagram(LITERAL, new byte[] { 1 }));
		udp.last().received(source("93.184.215.14", 53), new byte[] { 9, 8 });
		udp.last().received(source("93.184.215.14", 54), new byte[] { 7 });
		udp.last().received(source("93.184.215.15", 53), new byte[] { 6 });
		udp.last().received(source("10.0.0.1", 53), new byte[] { 5 });

		assertThat(pair.toClient(), equalTo(List.of(new Datagram(LITERAL, new byte[] { 9, 8 }))));
		assertThat(relay.droppedDatagrams(), is(3L));
		assertThat(socket.closed, is(false));
	}

	/* The connector asks before it copies anything, so a stranger's datagram costs the exit no allocation */
	@Example
	public void shouldRefuseAStrangerBeforeItsDatagramIsRead() throws UnknownHostException {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);

		assertThat(udp.last().accepts(source("93.184.215.14", 53)), is(false));
		associated(pair);
		send(pair, relay, new Datagram(LITERAL, new byte[] { 1 }));

		assertThat(udp.last().accepts(source("93.184.215.14", 53)), is(true));
		assertThat(udp.last().accepts(source("93.184.215.14", 54)), is(false));
		assertThat(udp.last().accepts(source("93.184.215.15", 53)), is(false));
		assertThat(relay.droppedDatagrams(), is(2L));
		assertThat(pair.toClient(), is(empty()));

		relay.close(CloseReason.DONE);
		assertThat(udp.last().accepts(source("93.184.215.14", 53)), is(false));
	}

	@Example
	public void shouldDropAReplyThatDoesNotFitAnEntry() throws UnknownHostException {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final int largest = pair.exit.maxDataBytes() - LITERAL.encodedSize();

		associated(pair);
		send(pair, relay, new Datagram(LITERAL, new byte[] { 1 }));
		udp.last().received(source("93.184.215.14", 53), new byte[largest + 1]);
		udp.last().received(source("93.184.215.14", 53), new byte[largest]);

		assertThat(pair.toClient().size(), is(1));
		assertThat(relay.droppedDatagrams(), is(1L));
	}

	/* An IPv6 address takes 12 bytes more of the entry than an IPv4 one, so its reply is bound 12 bytes lower */
	@Example
	public void shouldAnswerFromAnIpv6SourceWithinItsOwnBound() throws UnknownHostException {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final int largest = pair.exit.maxDataBytes() - IPV6.encodedSize();

		associated(pair);
		send(pair, relay, new Datagram(IPV6, new byte[] { 1 }));
		udp.arrive(source(IPV6.host(), 53), new byte[largest + 1]);
		udp.arrive(source(IPV6.host(), 53), new byte[largest]);

		assertThat(pair.toClient(), equalTo(List.of(new Datagram(IPV6, new byte[largest]))));
		assertThat(relay.droppedDatagrams(), is(1L));
	}

	/* The policy takes an IPv4-mapped literal for the IPv4 address inside it, which is where the datagram goes and
	   what the reply names; a source in the mapped form never was a destination */
	@Example
	public void shouldSendAMappedLiteralToItsIpv4AddressAndAnswerFromThat() throws UnknownHostException {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket socket = associated(pair);
		final byte[] mapped = NetUtil.createByteArrayFromIpAddressString("::ffff:93.184.215.14");

		send(pair, relay, new Datagram(new SocksAddress(Socks5AddressType.IPv6, "::ffff:93.184.215.14", 53),
			new byte[] { 1 }));
		udp.arrive(new InetSocketAddress(Inet6Address.getByAddress(null, mapped, -1), 53), new byte[] { 3 });
		udp.arrive(source("93.184.215.14", 53), new byte[] { 2 });

		assertThat(socket.sent.get(0).address(), instanceOf(Inet4Address.class));
		assertThat(socket.sent.get(0).address(), equalTo(ExitRelayTest.address("93.184.215.14")));
		assertThat(pair.toClient(), equalTo(List.of(new Datagram(LITERAL, new byte[] { 2 }))));
		assertThat(relay.droppedDatagrams(), is(1L));
	}

	@Example
	public void shouldCountItsOwnDropsWithThoseOfTheDestinations() throws UnknownHostException {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket socket = associated(pair);

		send(pair, relay, new Datagram(LITERAL, new byte[] { 1 }));
		send(pair, relay, new Datagram(LOCAL, new byte[] { 2 }));
		socket.accepting = false;
		send(pair, relay, new Datagram(LITERAL, new byte[] { 3 }));
		udp.arrive(source("93.184.215.15", 53), new byte[] { 4 });
		udp.arrive(source("93.184.215.14", 53), new byte[pair.exit.maxDataBytes()]);

		assertThat(relay.droppedDatagrams(), is(4L));
		assertThat(pair.toClient(), is(empty()));
		assertThat(events, is(empty()));
	}

	/* Strangers decide how many datagrams arrive, so nothing is logged per datagram; the destinations' drops are the
	   client's own doing and do not count towards the warning */
	@Example
	public void shouldWarnOnceWhenItsOwnDropsFirstPassTheLimit() throws UnknownHostException {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final InetSocketAddress stranger = source("93.184.215.15", 53);

		associated(pair);

		for (int i = 0; i < 3; i++) {
			send(pair, relay, new Datagram(LOCAL, new byte[] { 1 }));
		}

		for (int i = 0; i < DatagramRelay.WARN_AFTER_DROPS; i++) {
			udp.arrive(stranger, new byte[] { 1 });
		}

		assertThat(relay.droppedDatagrams(), is(DatagramRelay.WARN_AFTER_DROPS + 3));
		assertThat(events, is(empty()));
		udp.arrive(stranger, new byte[] { 1 });
		assertThat(events.size(), is(1));
		assertThat(events.peek().getLevel(), is(Level.WARN));
		assertThat(events.peek().getArgumentArray(), equalTo(new Object[] { DatagramRelay.WARN_AFTER_DROPS }));

		for (int i = 0; i < DatagramRelay.WARN_AFTER_DROPS; i++) {
			udp.arrive(stranger, new byte[] { 1 });
		}

		assertThat(relay.droppedDatagrams(), is(2 * DatagramRelay.WARN_AFTER_DROPS + 4));
		assertThat(events.size(), is(1));
		assertThat(DatagramRelay.WARN_AFTER_DROPS, is(1_000L));
	}

	@Example
	public void shouldStopReadingTheSocketWhileTheChainIsFull() {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket socket = associated(pair);

		relay.setChainWritable(false);
		assertThat(socket.readable, is(false));
		relay.setChainWritable(true);
		assertThat(socket.readable, is(true));
	}

	@Example
	public void shouldOpenTheSocketPausedWhenTheChainIsFullAlready() {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);

		relay.setChainWritable(false);

		assertThat(associated(pair).readable, is(false));
	}

	@Example
	public void shouldCloseTheSocketWhenTheClientClosesOrTheSessionIsIdle() {
		final SessionPair closing = new SessionPair(ASSOCIATE);
		final ExitRelay closed = relay(closing, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket first = associated(closing);

		closing.client.close(CloseReason.DONE);
		closing.toExit().forEach(closed::handle);
		assertThat(first.closed, is(true));
		assertThat(closed.isOpen(), is(false));
		assertThat(closing.toClient(), is(empty()));

		final SessionPair idle = new SessionPair(ASSOCIATE);
		final ExitRelay quiet = relay(idle, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket second = associated(idle);

		idle.clock.addAndGet(ProxySettings.DEFAULTS.sessionIdleMillis() - 1);
		quiet.tick();
		assertThat(second.closed, is(false));
		idle.clock.incrementAndGet();
		quiet.tick();
		assertThat(second.closed, is(true));
		assertThat(idle.toClient(), equalTo(List.of(new Close(CloseReason.IDLE))));
	}

	/* The host closes a relay with BROKEN for a gap in its client's feed; whatever comes after the end, the socket
	   was closed once and nothing more is sent either way */
	@Example
	public void shouldCloseTheSocketOnceWhateverEndsTheAssociation() throws UnknownHostException {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket socket = associated(pair);

		send(pair, relay, new Datagram(LITERAL, new byte[] { 1 }));
		relay.close(CloseReason.BROKEN);
		assertThat(pair.toClient(), equalTo(List.of(new Close(CloseReason.BROKEN))));

		relay.close(CloseReason.BROKEN);
		pair.clock.addAndGet(ProxySettings.DEFAULTS.sessionIdleMillis());
		relay.tick();
		relay.handle(new Datagram(LITERAL, new byte[] { 2 }));
		relay.handle(new Close(CloseReason.DONE));
		udp.last().received(source("93.184.215.14", 53), new byte[pair.exit.maxDataBytes()]);
		udp.last().closed();

		assertThat(socket.closes, is(1));
		assertThat(socket.sent.size(), is(1));
		assertThat(relay.droppedDatagrams(), is(0L));
		assertThat(pair.toClient(), is(empty()));
		assertThat(relay.isOpen(), is(false));
	}

	/* An outbox that refuses the Close must not keep the socket or the relay's slot */
	@Example
	public void shouldReleaseTheSocketWhenTheOutboxRefusesTheClose() {
		final SessionPair breaking = new SessionPair(ASSOCIATE);
		final ExitRelay broken = relay(breaking, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket first = associated(breaking);

		breaking.refusing.set(true);
		Expect.assertThrows(SessionPair.OutboxRefusedException.class, () -> broken.close(CloseReason.BROKEN));
		assertThat(first.closed, is(true));
		assertThat(broken.isOpen(), is(false));

		final SessionPair idle = new SessionPair(ASSOCIATE);
		final ExitRelay quiet = relay(idle, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket second = associated(idle);

		idle.refusing.set(true);
		idle.clock.addAndGet(ProxySettings.DEFAULTS.sessionIdleMillis());
		Expect.assertThrows(SessionPair.OutboxRefusedException.class, quiet::tick);
		assertThat(second.closed, is(true));
		assertThat(quiet.isOpen(), is(false));
	}

	@Example
	public void shouldCloseTheSessionWhenTheSocketCloses() {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);

		associated(pair);
		udp.last().closed();

		assertThat(pair.toClient(), equalTo(List.of(new Close(CloseReason.DONE))));
		assertThat(relay.isOpen(), is(false));
	}

	@Example
	public void shouldCloseASocketThatOpensAfterTheSessionEnded() {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket late = new FakeUdpConnector.FakeSocket();

		relay.close(CloseReason.BROKEN);
		udp.last().opened(late);

		assertThat(late.closed, is(true));
		assertThat(pair.toClient(), equalTo(List.of(new Close(CloseReason.BROKEN))));

		final SessionPair leaving = new SessionPair(ASSOCIATE);
		final ExitRelay left = relay(leaving, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket later = new FakeUdpConnector.FakeSocket();

		leaving.client.close(CloseReason.DONE);
		leaving.toExit().forEach(left::handle);
		udp.last().opened(later);

		assertThat(later.closed, is(true));
		assertThat(leaving.toClient(), is(empty()));
	}

	/* The session closes itself, as when an entry of the client fails to open, while the socket is being opened */
	@Example
	public void shouldCloseTheSocketWhenTheSessionEndedUnderTheReply() {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket socket = new FakeUdpConnector.FakeSocket();

		pair.exit.close(CloseReason.BROKEN);
		udp.last().opened(socket);

		assertThat(socket.closed, is(true));
		assertThat(socket.readable, is(false));
		assertThat(relay.isOpen(), is(false));
		assertThat(pair.toClient(), equalTo(List.of(new Close(CloseReason.BROKEN))));
	}

	@Example
	public void shouldBreakASessionThatMixesStreamsAndDatagrams() {
		final SessionPair data = new SessionPair(ASSOCIATE);
		final ExitRelay streaming = relay(data, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket socket = associated(data);

		send(data, streaming, new Data(new byte[] { 1 }));
		assertThat(data.toClient(), equalTo(List.of(new Close(CloseReason.BROKEN))));
		assertThat(socket.closed, is(true));

		final SessionPair early = new SessionPair(ASSOCIATE);
		final ExitRelay waiting = relay(early, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket late = new FakeUdpConnector.FakeSocket();

		send(early, waiting, new Datagram(LITERAL, new byte[] { 1 }));
		assertThat(early.toClient(), equalTo(List.of(new Close(CloseReason.BROKEN))));
		udp.last().opened(late);
		assertThat(late.closed, is(true));
		assertThat(early.toClient(), is(empty()));
	}

	@Example
	public void shouldBreakAnAssociationOnASecondConnect() {
		final SessionPair pair = new SessionPair(ASSOCIATE);
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeUdpConnector.FakeSocket socket = associated(pair);

		send(pair, relay, ASSOCIATE);
		assertThat(pair.toClient(), equalTo(List.of(new Close(CloseReason.BROKEN))));
		assertThat(socket.closed, is(true));

		final SessionPair opening = new SessionPair(ASSOCIATE);
		final ExitRelay waiting = relay(opening, ProxySettings.DEFAULTS, NEVER);

		send(opening, waiting, ASSOCIATE);
		assertThat(opening.toClient(), equalTo(List.of(new Close(CloseReason.BROKEN))));
		assertThat(udp.requests.size(), is(2));
	}

	@Example
	public void shouldRefuseADatagramContextWithoutAConnectorOrClock() {
		final ExitContext context = new ExitContext(ProxySettings.DEFAULTS, DestinationPolicy.publicOnly(), NEVER,
			Runnable::run, new FakeConnector());

		Expect.assertThrows(NullPointerException.class, () -> new ExitContext(context.settings(), context.policy(),
			NEVER, Runnable::run, context.connector(), null, context.clock()));
		Expect.assertThrows(NullPointerException.class, () -> new ExitContext(context.settings(), context.policy(),
			NEVER, Runnable::run, context.connector(), udp, null));
	}

	/* A wall clock would be some 1.7 trillion milliseconds, the monotonic one counts from an arbitrary origin */
	@Example
	public void shouldGiveTheFiveComponentContextTheMonotonicClock() {
		final ExitContext context = new ExitContext(ProxySettings.DEFAULTS, DestinationPolicy.publicOnly(), NEVER,
			Runnable::run, new FakeConnector());
		final long before = System.nanoTime() / 1_000_000;
		final long now = context.clock().getAsLong();
		final long after = System.nanoTime() / 1_000_000;

		assertThat(now, is(both(greaterThanOrEqualTo(before)).and(lessThanOrEqualTo(after))));
	}
}
