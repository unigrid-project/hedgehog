/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class ExitRelayTest {
	private static final SocksAddress PUBLIC_LITERAL = new SocksAddress(Socks5AddressType.IPv4, "93.184.215.14", 443);
	private static final SocksAddress NAME = new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 443);
	private static final ProxySettings UDP_ONLY = new ProxySettings(8, 3600, 4, 16, 32L * 1024 * 1024, 30, 16 * 1024,
		300, ProxySettings.UDP);
	private static final Resolver NEVER = host -> {
		throw new AssertionError("Nothing to resolve, asked for " + host);
	};
	private static final Executor REJECTING = command -> {
		throw new RejectedExecutionException("no room for lookups");
	};
	private static final List<Socks5CommandStatus> REFUSING = List.of(Socks5CommandStatus.FORBIDDEN,
		Socks5CommandStatus.COMMAND_UNSUPPORTED, Socks5CommandStatus.ADDRESS_UNSUPPORTED);
	private static final List<Socks5CommandStatus> UNREACHING = List.of(Socks5CommandStatus.FAILURE,
		Socks5CommandStatus.NETWORK_UNREACHABLE, Socks5CommandStatus.HOST_UNREACHABLE,
		Socks5CommandStatus.CONNECTION_REFUSED, Socks5CommandStatus.TTL_EXPIRED);

	/* Every way a relay ends while its connection is being dialled */
	private static final Map<String, BiConsumer<SessionPair, ExitRelay>> ENDINGS = Map.of(
		"the client's Close", (pair, relay) -> {
			pair.client.close(CloseReason.DONE);
			pair.toExit().forEach(relay::handle);
		},
		"a close by the exit", (pair, relay) -> relay.close(CloseReason.BROKEN),
		"a close the outbox refuses", (pair, relay) -> {
			pair.refusing.set(true);
			Expect.assertThrows(SessionPair.OutboxRefusedException.class, () -> relay.close(CloseReason.DONE));
		},
		"the idle limit", (pair, relay) -> {
			pair.clock.addAndGet(ProxySettings.DEFAULTS.sessionIdleMillis());
			relay.tick();
		},
		"a session closed under the relay", (pair, relay) -> {
			pair.exit.close(CloseReason.BROKEN);
			relay.tick();
		},
		"data before the reply", (pair, relay) -> {
			pair.client.send(new Data(new byte[] { 1 }));
			pair.toExit().forEach(relay::handle);
		});

	private final FakeConnector connector = new FakeConnector();

	static InetAddress address(String literal) throws UnknownHostException {
		return InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(literal));
	}

	private static Resolver answering(Map<String, List<String>> answers, AtomicInteger lookups) {
		return host -> {
			lookups.incrementAndGet();

			if (!answers.containsKey(host)) {
				throw new UnknownHostException(host);
			}

			return answers.get(host).stream().map(literal -> {
				try {
					return address(literal);
				} catch (UnknownHostException ex) {
					throw new IllegalStateException(ex);
				}
			}).toList();
		};
	}

	private static Connect connect(SocksAddress destination) {
		return new Connect(Socks5CommandType.CONNECT, destination);
	}

	private ExitRelay relay(SessionPair pair, ProxySettings settings, Resolver resolver) {
		return relay(pair, new ExitContext(settings, DestinationPolicy.publicOnly(), resolver, Runnable::run,
			connector));
	}

	private static ExitRelay relay(SessionPair pair, ExitContext context) {
		final ExitRelay relay = new ExitRelay(pair.exit, context);

		pair.toExit().forEach(relay::handle);
		return relay;
	}

	/* Answers the relay's connection request with a target bound to 192.0.2.7:40000 */
	private FakeConnector.FakeTarget connected(SessionPair pair) throws UnknownHostException {
		final FakeConnector.FakeTarget target = new FakeConnector.FakeTarget(new InetSocketAddress(
			address("192.0.2.7"), 40_000));

		connector.last().listener().connected(target);
		assertThat(pair.toClient(), equalTo(List.of(new Connected(Socks5CommandStatus.SUCCESS, new SocksAddress(
			Socks5AddressType.IPv4, "192.0.2.7", 40_000)))));
		return target;
	}

	private static List<SealedMessage> refusal(Socks5CommandStatus status, CloseReason reason) {
		return List.of(new Connected(status, SocksAddress.UNSPECIFIED), new Close(reason));
	}

	@Example
	public void shouldConnectToALiteralAsGivenAndReplyWithTheBoundAddress() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);

		assertThat(connector.last().addresses(), equalTo(List.of(address("93.184.215.14"))));
		assertThat(connector.last().port(), is(443));
		assertThat(connected(pair).readable, is(true));
		assertThat(relay.isOpen(), is(true));
	}

	@Example
	public void shouldResolveANameOnceAndConnectToItsAddressesOnly() throws UnknownHostException {
		final AtomicInteger lookups = new AtomicInteger();
		final SessionPair pair = new SessionPair(connect(NAME));

		relay(pair, ProxySettings.DEFAULTS, answering(Map.of("example.org", List.of("93.184.215.14",
			"2606:2800:21f::1")), lookups));
		assertThat(connector.last().addresses(), equalTo(List.of(address("93.184.215.14"),
			address("2606:2800:21f::1"))));
		connected(pair);
		assertThat(lookups.get(), is(1));
	}

	@Example
	public void shouldRefuseANameWithALocalAddress() {
		final SessionPair pair = new SessionPair(connect(NAME));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, answering(Map.of("example.org",
			List.of("93.184.215.14", "192.168.1.10")), new AtomicInteger()));

		assertThat(pair.toClient(), equalTo(refusal(Socks5CommandStatus.FORBIDDEN, CloseReason.REFUSED)));
		assertThat(connector.requests, is(empty()));
		assertThat(relay.isOpen(), is(false));
	}

	@Example
	public void shouldCallANameWithoutAddressesUnreachable() {
		final SessionPair pair = new SessionPair(connect(NAME));

		relay(pair, ProxySettings.DEFAULTS, answering(Map.of(), new AtomicInteger()));
		assertThat(pair.toClient(), equalTo(refusal(Socks5CommandStatus.HOST_UNREACHABLE, CloseReason.UNREACHABLE)));
		assertThat(connector.requests, is(empty()));
	}

	@Example
	public void shouldRefuseConnectWhileTheSporkForbidsIt() {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));

		relay(pair, UDP_ONLY, NEVER);
		assertThat(pair.toClient(), equalTo(refusal(Socks5CommandStatus.FORBIDDEN, CloseReason.REFUSED)));
		assertThat(connector.requests, is(empty()));
	}

	@Example
	public void shouldRefuseAnAssociationWhenTheNodeRelaysNoDatagrams() {
		final SessionPair pair = new SessionPair(new Connect(Socks5CommandType.UDP_ASSOCIATE, PUBLIC_LITERAL));

		relay(pair, ProxySettings.DEFAULTS, NEVER);
		assertThat(pair.toClient(), equalTo(refusal(Socks5CommandStatus.COMMAND_UNSUPPORTED, CloseReason.REFUSED)));
	}

	@Example
	public void shouldTellTheClientWhyNoAddressAccepted() {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));

		relay(pair, ProxySettings.DEFAULTS, NEVER);
		connector.last().listener().failed(Socks5CommandStatus.CONNECTION_REFUSED);
		assertThat(pair.toClient(), equalTo(refusal(Socks5CommandStatus.CONNECTION_REFUSED,
			CloseReason.UNREACHABLE)));
	}

	@Example
	public void shouldFailWhenTheResolverThrows() {
		final SessionPair pair = new SessionPair(connect(NAME));
		final Resolver throwing = host -> {
			throw new IllegalStateException("no name service");
		};

		relay(pair, ProxySettings.DEFAULTS, throwing);
		assertThat(pair.toClient(), equalTo(refusal(Socks5CommandStatus.FAILURE, CloseReason.UNREACHABLE)));
		assertThat(connector.requests, is(empty()));
	}

	@Example
	public void shouldFailWhenTheResolverAnswersNothingAtAll() {
		final SessionPair pair = new SessionPair(connect(NAME));

		relay(pair, ProxySettings.DEFAULTS, host -> null);
		assertThat(pair.toClient(), equalTo(refusal(Socks5CommandStatus.FAILURE, CloseReason.UNREACHABLE)));
		assertThat(connector.requests, is(empty()));
	}

	@Example
	public void shouldFailWhenTheResolverAnswersWithANullAddress() {
		final SessionPair pair = new SessionPair(connect(NAME));

		relay(pair, ProxySettings.DEFAULTS, host -> Arrays.asList((InetAddress) null));
		assertThat(pair.toClient(), equalTo(refusal(Socks5CommandStatus.FAILURE, CloseReason.UNREACHABLE)));
		assertThat(connector.requests, is(empty()));
	}

	@Example
	public void shouldFailWhenTheConnectorRefusesTheWork() {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, new ExitContext(ProxySettings.DEFAULTS, DestinationPolicy.publicOnly(),
			NEVER, Runnable::run, (addresses, port, listener) -> {
				throw new RejectedExecutionException("no room for connections");
			}));

		assertThat(pair.toClient(), equalTo(refusal(Socks5CommandStatus.FAILURE, CloseReason.UNREACHABLE)));
		assertThat(relay.isOpen(), is(false));
	}

	@Example
	public void shouldFailWhenTheResolvingExecutorRefusesTheLookup() {
		final SessionPair pair = new SessionPair(connect(NAME));
		final ExitRelay relay = relay(pair, new ExitContext(ProxySettings.DEFAULTS, DestinationPolicy.publicOnly(),
			NEVER, REJECTING, connector));

		assertThat(pair.toClient(), equalTo(refusal(Socks5CommandStatus.FAILURE, CloseReason.UNREACHABLE)));
		assertThat(connector.requests, is(empty()));
		assertThat(relay.isOpen(), is(false));
	}

	@Example
	public void shouldCloseWithRefusedOnlyForARefusalOfTheRequest() {
		for (Socks5CommandStatus status : REFUSING) {
			final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));

			relay(pair, ProxySettings.DEFAULTS, NEVER);
			connector.last().listener().failed(status);
			assertThat(status.toString(), pair.toClient(), equalTo(refusal(status, CloseReason.REFUSED)));
		}

		for (Socks5CommandStatus status : UNREACHING) {
			final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));

			relay(pair, ProxySettings.DEFAULTS, NEVER);
			connector.last().listener().failed(status);
			assertThat(status.toString(), pair.toClient(), equalTo(refusal(status, CloseReason.UNREACHABLE)));
		}
	}

	@Example
	public void shouldReplyWithAnIpv6BoundAddress() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));

		relay(pair, ProxySettings.DEFAULTS, NEVER);
		connector.last().listener().connected(new FakeConnector.FakeTarget(new InetSocketAddress(
			address("2001:db8::7"), 40_000)));
		assertThat(pair.toClient(), equalTo(List.of(new Connected(Socks5CommandStatus.SUCCESS, new SocksAddress(
			Socks5AddressType.IPv6, "2001:db8::7", 40_000)))));
	}

	@Example
	public void shouldCutTheStreamAtExactlyOneEntry() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final int piece = pair.exit.maxDataBytes();

		connected(pair);

		for (int size : List.of(1, piece - 1, piece, piece + 1, 2 * piece, 2 * piece + 1)) {
			connector.last().listener().received(new byte[size]);
			assertThat(Integer.toString(size), pair.toClient().size(), is((size + piece - 1) / piece));
		}

		assertThat(relay.isOpen(), is(true));
	}

	@Example
	public void shouldDropTheTargetWhenTheSessionCannotPostItsClose() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeConnector.FakeTarget target = connected(pair);

		pair.refusing.set(true);
		Expect.assertThrows(SessionPair.OutboxRefusedException.class, () -> relay.close(CloseReason.BROKEN));
		assertThat(target.closed, is(true));
		assertThat(relay.isOpen(), is(false));
	}

	@Example
	public void shouldDropTheTargetWhenTheSessionCannotPostTheCloseOfAnIdleTick() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeConnector.FakeTarget target = connected(pair);

		pair.clock.addAndGet(ProxySettings.DEFAULTS.sessionIdleMillis());
		pair.refusing.set(true);
		Expect.assertThrows(SessionPair.OutboxRefusedException.class, relay::tick);
		assertThat(target.closed, is(true));
		assertThat(relay.isOpen(), is(false));
	}

	@Example
	public void shouldNotStartReadingFromATargetOfASessionThatEnded() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeConnector.FakeTarget target = new FakeConnector.FakeTarget(new InetSocketAddress(
			address("192.0.2.7"), 40_000));

		pair.exit.close(CloseReason.BROKEN);
		connector.last().listener().connected(target);
		assertThat(target.closed, is(true));
		assertThat(target.readable, is(false));
		assertThat(relay.isOpen(), is(false));
	}

	@Example
	public void shouldHoldBackTheBytesOfADestinationUntilTheReplyWentOut() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));

		relay(pair, ProxySettings.DEFAULTS, NEVER);
		connector.last().listener().received(new byte[] { 1, 2, 3 });
		assertThat(pair.toClient(), is(empty()));
	}

	@Example
	public void shouldBreakASessionWhoseClientSendsWhatOnlyTheExitSends() throws UnknownHostException {
		for (SealedMessage message : List.of(new Connected(Socks5CommandStatus.SUCCESS, SocksAddress.UNSPECIFIED),
			new Datagram(PUBLIC_LITERAL, new byte[] { 1 }))) {
			final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
			final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
			final FakeConnector.FakeTarget target = connected(pair);

			relay.handle(message);
			assertThat(pair.toClient(), equalTo(List.of(new Close(CloseReason.BROKEN))));
			assertThat(target.closed, is(true));
		}
	}

	@Example
	public void shouldDoNoLookupForAClientThatWentBeforeItStarted() {
		final SessionPair pair = new SessionPair(connect(NAME));
		final Queue<Runnable> queued = new ArrayDeque<>();
		final AtomicInteger lookups = new AtomicInteger();
		final ExitRelay relay = relay(pair, new ExitContext(ProxySettings.DEFAULTS, DestinationPolicy.publicOnly(),
			answering(Map.of("example.org", List.of("93.184.215.14")), lookups), queued::add, connector));

		relay.close(CloseReason.DONE);
		queued.forEach(Runnable::run);
		assertThat(lookups.get(), is(0));
		assertThat(connector.requests, is(empty()));
	}

	/* A relay whose admission waits until the test runs it, so a resolver or connector can reach the relay */
	private static ExitRelay admitting(SessionPair pair, Resolver resolver, ExitConnector connector,
		AtomicReference<ExitRelay> relayed) {

		final Queue<Runnable> queued = new ArrayDeque<>();
		final ExitRelay relay = relay(pair, new ExitContext(ProxySettings.DEFAULTS, DestinationPolicy.publicOnly(),
			resolver, queued::add, connector));

		relayed.set(relay);
		queued.forEach(Runnable::run);
		return relay;
	}

	@Example
	public void shouldOpenNoConnectionForAClientThatLeftDuringTheLookup() {
		final AtomicReference<ExitRelay> relayed = new AtomicReference<>();
		final ExitRelay relay = admitting(new SessionPair(connect(NAME)), host -> {
			relayed.get().close(CloseReason.DONE);
			return List.of(address("93.184.215.14"));
		}, connector, relayed);

		assertThat(relay.isOpen(), is(false));
		assertThat(connector.requests, is(empty()));
	}

	@Example
	public void shouldCancelAConnectThatStartedAsTheRelayEnded() {
		final AtomicReference<ExitRelay> relayed = new AtomicReference<>();
		final AtomicInteger cancels = new AtomicInteger();
		final ExitRelay relay = admitting(new SessionPair(connect(PUBLIC_LITERAL)), NEVER,
			(addresses, port, listener) -> {
				relayed.get().close(CloseReason.DONE);
				return cancels::incrementAndGet;
			}, relayed);

		assertThat(relay.isOpen(), is(false));
		assertThat(cancels.get(), is(1));
	}

	@Example
	public void shouldCancelTheConnectUnderWayOnceHoweverTheRelayEnds() {
		ENDINGS.forEach((ending, end) -> {
			final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
			final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);

			assertThat(ending, connector.last().cancels().get(), is(0));
			end.accept(pair, relay);
			relay.tick();
			assertThat(ending, relay.isOpen(), is(false));
			assertThat(ending, connector.last().cancels().get(), is(1));
		});
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), is(true));
		} catch (InterruptedException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Thread started(Runnable work) {
		final Thread thread = new Thread(work);

		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	@Example
	public void shouldHoldNoLockWhileTheLookupBlocks() throws Exception {
		final SessionPair pair = new SessionPair(connect(NAME));
		final BlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
		final CountDownLatch inLookup = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final ExitRelay relay = relay(pair, new ExitContext(ProxySettings.DEFAULTS, DestinationPolicy.publicOnly(),
			host -> {
				inLookup.countDown();
				await(release);
				return List.of(address("93.184.215.14"));
			}, queued::add, connector));
		final Thread lookup = started(queued.take());

		await(inLookup);

		final Thread probe = started(relay::isOpen);

		probe.join(RecordingTargetListener.TIMEOUT_MILLIS);
		assertThat(probe.isAlive(), is(false));
		release.countDown();
		lookup.join(RecordingTargetListener.TIMEOUT_MILLIS);
		assertThat(connector.last().addresses(), equalTo(List.of(address("93.184.215.14"))));
	}

	/* A destination that takes a write for as long as the test lets it */
	private static final class StallingTarget extends FakeConnector.FakeTarget {
		final CountDownLatch writing = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);

		StallingTarget(InetSocketAddress local) {
			super(local);
		}

		@Override
		public void write(byte[] bytes) {
			writing.countDown();
			await(release);
		}
	}

	@Example
	public void shouldHoldTheRelayAgainstEveryOtherEntryWhileOneIsInside() throws Exception {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final StallingTarget target = new StallingTarget(new InetSocketAddress(address("192.0.2.7"), 40_000));
		final List<Runnable> entries = List.of(relay::isOpen, relay::tick, () -> relay.setChainWritable(false),
			() -> relay.close(CloseReason.DONE),
			() -> relay.handle(new Close(CloseReason.DONE)),
			() -> connector.last().listener().received(new byte[1]), () -> connector.last().listener().closed());

		connector.last().listener().connected(target);

		final Thread inside = started(() -> relay.handle(new Data(new byte[] { 1 })));

		await(target.writing);

		final List<Thread> waiting = entries.stream().map(ExitRelayTest::started).toList();

		for (Thread thread : waiting) {
			RecordingTargetListener.await(() -> thread.getState() == Thread.State.BLOCKED);
		}

		target.release.countDown();
		inside.join(RecordingTargetListener.TIMEOUT_MILLIS);

		for (Thread thread : waiting) {
			thread.join(RecordingTargetListener.TIMEOUT_MILLIS);
			assertThat(thread.isAlive(), is(false));
		}
	}

	@Example
	public void shouldDropTheTargetWhenTheClientClosesBeforeTheReply() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeConnector.FakeTarget late = new FakeConnector.FakeTarget(new InetSocketAddress(
			address("192.0.2.7"), 40_000));

		pair.client.close(CloseReason.DONE);
		pair.toExit().forEach(relay::handle);
		assertThat(relay.isOpen(), is(false));
		connector.last().listener().connected(late);
		relay.tick();
		assertThat(late.closes, is(1));
		assertThat(connector.last().cancels().get(), is(1));
		assertThat(pair.toClient(), is(empty()));
	}

	@Example
	public void shouldBreakASessionThatSendsASecondConnect() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeConnector.FakeTarget target = connected(pair);

		relay.handle(connect(NAME));
		assertThat(pair.toClient(), equalTo(List.of(new Close(CloseReason.BROKEN))));
		assertThat(target.closed, is(true));
		assertThat(connector.requests.size(), is(1));
	}

	@Example
	public void shouldIgnoreAFailureReportedAfterTheTargetConnected() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeConnector.FakeTarget target = connected(pair);

		connector.last().listener().failed(Socks5CommandStatus.CONNECTION_REFUSED);
		assertThat(pair.toClient(), is(empty()));
		assertThat(target.closed, is(false));
		assertThat(relay.isOpen(), is(true));
	}

	@Example
	public void shouldEndQuietlyWhenTheSessionClosedUnderTheRelay() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeConnector.FakeTarget target = connected(pair);

		pair.exit.close(CloseReason.BROKEN);
		connector.last().listener().received(new byte[] { 1, 2, 3 });
		assertThat(target.closed, is(true));
		assertThat(relay.isOpen(), is(false));
		assertThat(pair.toClient(), equalTo(List.of(new Close(CloseReason.BROKEN))));
	}

	@Example
	public void shouldRefuseAContextOrRelayWithoutAPart() {
		final ExitContext context = new ExitContext(ProxySettings.DEFAULTS, DestinationPolicy.publicOnly(), NEVER,
			Runnable::run, connector);

		Expect.assertThrows(NullPointerException.class, () -> new ExitContext(null, context.policy(), NEVER,
			Runnable::run, connector));
		Expect.assertThrows(NullPointerException.class, () -> new ExitContext(context.settings(), null, NEVER,
			Runnable::run, connector));
		Expect.assertThrows(NullPointerException.class, () -> new ExitContext(context.settings(), context.policy(),
			null, Runnable::run, connector));
		Expect.assertThrows(NullPointerException.class, () -> new ExitContext(context.settings(), context.policy(),
			NEVER, null, connector));
		Expect.assertThrows(NullPointerException.class, () -> new ExitContext(context.settings(), context.policy(),
			NEVER, Runnable::run, null));
		Expect.assertThrows(NullPointerException.class, () -> new ExitRelay(null, context));
		Expect.assertThrows(NullPointerException.class, () -> new ExitRelay(new SessionPair(connect(
			PUBLIC_LITERAL)).exit, null));
	}

	@Property(tries = 20)
	public void shouldRelayTheStreamInEntrySizedPieces(@ForAll @IntRange(min = 1, max = 100_000) int size)
		throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeConnector.FakeTarget target = connected(pair);
		final byte[] download = new byte[size];
		final ByteArrayOutputStream delivered = new ByteArrayOutputStream();

		pair.client.send(new Data(new byte[] { 1, 2, 3 }));
		pair.toExit().forEach(relay::handle);
		assertThat(target.written.toByteArray(), equalTo(new byte[] { 1, 2, 3 }));

		download[size - 1] = 7;
		connector.last().listener().received(download);

		final List<SealedMessage> messages = pair.toClient();

		assertThat(messages, everyItem(instanceOf(Data.class)));
		messages.forEach(message -> delivered.writeBytes(((Data) message).bytes()));
		messages.forEach(message -> assertThat(((Data) message).bytes().length,
			lessThanOrEqualTo(pair.exit.maxDataBytes())));
		assertThat(delivered.toByteArray(), equalTo(download));
	}

	@Example
	public void shouldDropTheTargetWhenTheClientCloses() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeConnector.FakeTarget target = connected(pair);

		pair.client.close(CloseReason.DONE);
		pair.toExit().forEach(relay::handle);
		assertThat(target.closed, is(true));
		assertThat(relay.isOpen(), is(false));
	}

	@Example
	public void shouldCloseTheSessionWhenTheTargetCloses() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);

		connected(pair);
		connector.last().listener().closed();
		assertThat(pair.toClient(), equalTo(List.of(new Close(CloseReason.DONE))));
		assertThat(relay.isOpen(), is(false));
	}

	@Example
	public void shouldCloseAnIdleSessionAndItsTarget() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeConnector.FakeTarget target = connected(pair);

		pair.clock.addAndGet(ProxySettings.DEFAULTS.sessionIdleMillis() - 1);
		relay.tick();
		assertThat(target.closed, is(false));
		pair.clock.addAndGet(1);
		relay.tick();
		assertThat(target.closed, is(true));
		assertThat(pair.toClient(), equalTo(List.of(new Close(CloseReason.IDLE))));
	}

	@Example
	public void shouldBreakASessionThatSendsDataBeforeItsReply() throws UnknownHostException {
		final SessionPair pair = new SessionPair(connect(PUBLIC_LITERAL));
		final ExitRelay relay = relay(pair, ProxySettings.DEFAULTS, NEVER);
		final FakeConnector.FakeTarget late = new FakeConnector.FakeTarget(new InetSocketAddress(
			address("192.0.2.7"), 40_000));

		pair.client.send(new Data(new byte[] { 1 }));
		pair.toExit().forEach(relay::handle);
		assertThat(pair.toClient(), equalTo(List.of(new Close(CloseReason.BROKEN))));
		connector.last().listener().connected(late);
		assertThat(late.closed, is(true));
		assertThat(late.written.size(), is(0));
	}
}
