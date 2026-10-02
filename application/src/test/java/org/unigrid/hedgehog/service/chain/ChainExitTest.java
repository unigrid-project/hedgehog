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

package org.unigrid.hedgehog.service.chain;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.jqwik.Expect;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.Gap;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.exit.Target;
import org.unigrid.hedgehog.proxy.exit.TargetListener;
import org.unigrid.hedgehog.proxy.exit.UdpConnector;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.ClientSession;
import org.unigrid.hedgehog.proxy.session.Close;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.Connect;
import org.unigrid.hedgehog.proxy.session.Connected;
import org.unigrid.hedgehog.proxy.session.Data;
import org.unigrid.hedgehog.proxy.session.DestinationPolicy;
import org.unigrid.hedgehog.proxy.session.EphemeralKey;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.SessionContext;
import org.unigrid.hedgehog.proxy.session.SessionState;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import org.unigrid.hedgehog.proxy.socks.TunnelFailure;

public class ChainExitTest {
	private static final String EXIT = "gn-1";
	private static final long EPOCH_MILLIS = 3_600_000;
	private static final int WAIT_SECONDS = 5;
	private static final int DEADLOCK_SECONDS = 30;
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Connect CONNECT = new Connect(Socks5CommandType.CONNECT,
		new SocksAddress(Socks5AddressType.IPv4, "127.0.0.1", 8080));
	private static final Connected CONNECTED = new Connected(Socks5CommandStatus.SUCCESS,
		new SocksAddress(Socks5AddressType.IPv4, "127.0.0.1", 40_000));
	private static final Close BROKEN = new Close(CloseReason.BROKEN);
	private static final Close IDLE = new Close(CloseReason.IDLE);
	private static final Close DONE = new Close(CloseReason.DONE);
	private static final Connect ASSOCIATE = new Connect(Socks5CommandType.UDP_ASSOCIATE, SocksAddress.UNSPECIFIED);
	private static final Connected ASSOCIATED_REPLY = new Connected(Socks5CommandStatus.SUCCESS,
		SocksAddress.UNSPECIFIED);

	/* A node with one writer on one chain, whose sessions name the exit it is given. Its entries are handed to the
	   exit's host as a member would flood them, and what the exit floods to gn-0 is read from the exit's end of that
	   stream. */
	private static final class Caller {
		private final Rig rig;
		private final String exitId;
		private final Connect connect;
		private final SessionContext context;
		private final List<Entry> posted = new ArrayList<>();
		private final List<SealedMessage> received = new ArrayList<>();
		private final List<ClientSession> sessions = new ArrayList<>();

		private Caller(final Rig rig, final int number, final String exitId, final ChainId chainId,
			final Connect connect) {

			this.rig = rig;
			this.exitId = exitId;
			this.connect = connect;
			context = new SessionContext(new FeedWriter(ChainFleet.key(300 + number), chainId), posted::add,
				rig.fleet.clock::get, new SecureRandom(), ProxySettings.DEFAULTS);
			sessions.add(newSession());
		}

		private ClientSession newSession() {
			return new ClientSession(context, (fingerprint, excluded) -> Optional.of(exitId), connect);
		}

		private ClientSession session() {
			return sessions.get(0);
		}

		private void open() {
			session().open();
			rig.drive();
		}

		/* A further session through the same writer */
		private ClientSession another() {
			final ClientSession session = newSession();

			sessions.add(session);
			session.open();
			rig.drive();
			return session;
		}

		private WriterId writer() {
			return context.writer().writer();
		}

		private Fingerprint fingerprint() {
			return session().fingerprint().orElseThrow();
		}

		/* An entry of this caller's own feed, written beside its sessions */
		private Entry write(final Fingerprint fingerprint, final EntryKind kind, final byte[] payload) {
			return context.writer().write(rig.fleet.clock.get(), fingerprint, kind, payload);
		}

		private void send() {
			final List<Entry> entries = List.copyOf(posted);

			posted.clear();
			entries.forEach(rig::offer);
		}
	}

	private static final class Rig {
		private final ChainFleet fleet;
		private final FakeConnector connector = new FakeConnector();
		private final ManualScheduler scheduler = new ManualScheduler();
		private final AtomicBoolean released = new AtomicBoolean();
		private final List<Caller> callers = new ArrayList<>();
		private final Map<List<Object>, FeedWriter> strangers = new HashMap<>();
		private final List<FakeSocket> sockets = new ArrayList<>();
		private final EmbeddedChannel stream;
		private final ChainExit exit;

		private Rig(final ChainFleet fleet, final Function<Rig, ChainExit> exits) {
			this.fleet = fleet;
			stream = fleet.stream(EXIT, "gn-0");
			exit = exits.apply(this);
			exit.start();
		}

		/* An exit that may connect to loopback, looks names up on the caller's thread, connects to nothing and opens
		   a datagram socket of its own at once for every association */
		private Rig(final ChainFleet fleet) {
			this(fleet, rig -> rig.exit(fleet.clock::get, Runnable::run));
		}

		private Rig() {
			this(ChainFleet.group());
		}

		private ChainExit exit(final LongSupplier clock, final Executor resolving) {
			return new ChainExit(fleet.host(EXIT), fleet.settings::get, fleet.directory(EXIT), clock,
				new SecureRandom(), new ExitContexts(DestinationPolicy.allowLoopback(), connector, resolving,
				() -> released.set(true), this::associate), scheduler);
		}

		private void associate(final UdpConnector.Listener listener) {
			final FakeSocket socket = new FakeSocket();

			sockets.add(socket);
			listener.opened(socket);
		}

		/* The chains of the group in index order, the first of which the callers join */
		private ChainId chain(final int index) {
			return fleet.host(EXIT).hostedChains().get(index);
		}

		private Caller caller(final int number) {
			return caller(number, EXIT, fleet.chain());
		}

		private Caller caller(final int number, final String exitId, final ChainId chainId) {
			return caller(number, exitId, chainId, CONNECT);
		}

		private Caller caller(final int number, final String exitId, final ChainId chainId, final Connect connect) {
			final Caller caller = new Caller(this, number, exitId, chainId, connect);

			callers.add(caller);
			return caller;
		}

		private void offer(final Entry entry) {
			fleet.host(EXIT).receive(stream, packet(entry));
		}

		/* Hands entries between the callers and the exit until nothing more moves */
		private void drive() {
			boolean moved = true;

			while (moved) {
				moved = callers.stream().anyMatch(caller -> !caller.posted.isEmpty());
				callers.forEach(Caller::send);
				moved = handOut() || moved;
			}
		}

		/* Opens what the exit wrote in the sessions of the callers; true while it took anything out */
		private boolean handOut() {
			boolean moved = false;

			for (Object out = stream.readOutbound(); out != null; out = stream.readOutbound()) {
				moved = true;
				((ChainEntry) out).getEntries().forEach(entry -> callers.forEach(caller -> caller.sessions
					.forEach(session -> session.receive(entry).ifPresent(caller.received::add))));
			}

			return moved;
		}

		/* A writer of the chain that is no caller of the test */
		private FeedWriter stranger(final int number, final ChainId chainId) {
			return strangers.computeIfAbsent(List.of(number, chainId),
				key -> new FeedWriter(ChainFleet.key(500 + number), chainId));
		}

		/* An OPEN naming the exit from a writer that never completes the handshake; true when it was answered */
		private boolean opens(final int writer, final ChainId chainId) {
			final Fingerprint fingerprint = fingerprint();

			offer(open(stranger(writer, chainId), fleet.clock.get(), fingerprint, EXIT));
			return accepts(chainId, fingerprint) > 0;
		}

		private long answered(final int writer, final ChainId chainId, final int opens) {
			return IntStream.range(0, opens).filter(open -> opens(writer, chainId)).count();
		}

		/* What another writer posts when it copies an entry it saw on the chain */
		private Entry copy(final Entry entry, final int writer, final ChainId chainId) {
			return stranger(writer, chainId).write(fleet.clock.get(), entry.header().fingerprint(),
				entry.header().kind(), entry.payload());
		}

		private List<Entry> entries(final ChainId chainId, final EntryKind kind) {
			return fleet.host(EXIT).chain(chainId).orElseThrow().entries().stream()
				.filter(entry -> entry.header().kind() == kind).toList();
		}

		private long accepts(final ChainId chainId, final Fingerprint fingerprint) {
			return entries(chainId, EntryKind.ACCEPT).stream()
				.filter(entry -> entry.header().fingerprint().equals(fingerprint)).count();
		}

		private int accepts() {
			return entries(fleet.chain(), EntryKind.ACCEPT).size();
		}
	}

	/* The datagram socket of an association, which counts how often it is closed */
	private static final class FakeSocket implements UdpConnector.Socket {
		private final AtomicInteger closes = new AtomicInteger();

		@Override
		public boolean send(final InetAddress address, final int port, final byte[] bytes) {
			return true;
		}

		@Override
		public InetSocketAddress localAddress() {
			return new InetSocketAddress(InetAddress.getLoopbackAddress(), 40_001);
		}

		@Override
		public void setReadable(final boolean readable) {
			/* Nothing arrives at it */
		}

		@Override
		public void close() {
			closes.incrementAndGet();
		}
	}

	/* How far a session has got at the exit, what its client was told so far, and whether its relay holds anything of
	   the destination */
	private enum Stage {
		WAITING(CONNECT, List.of(), false),
		CONNECTING(CONNECT, List.of(), true),
		RELAYING(CONNECT, List.of(CONNECTED), true),
		ASSOCIATED(ASSOCIATE, List.of(ASSOCIATED_REPLY), true);

		private final Connect connect;
		private final List<SealedMessage> told;
		private final boolean holdsADestination;

		Stage(final Connect connect, final List<SealedMessage> told, final boolean holdsADestination) {
			this.connect = connect;
			this.told = told;
			this.holdsADestination = holdsADestination;
		}

		private List<SealedMessage> thenClosed(final CloseReason reason) {
			final List<SealedMessage> all = new ArrayList<>(told);

			all.add(new Close(reason));
			return all;
		}
	}

	private record Reached(Caller caller, IntSupplier closes) {
		/* A caller whose session got to a stage, and how often what its relay held of the destination was closed */
	}

	/* A caller on the first chain whose session got that far. What a session still connecting holds is its connect,
	   which its relay lets go of by cancelling it. */
	private static Reached reach(final Rig rig, final Stage stage) {
		final Caller caller = rig.caller(2, EXIT, rig.chain(0), stage.connect);

		if (stage == Stage.WAITING) {
			caller.session().open();
			caller.send();
			rig.handOut();
			caller.posted.clear();
			return new Reached(caller, () -> 0);
		}

		caller.open();

		if (stage == Stage.CONNECTING) {
			return new Reached(caller, rig.connector.last().cancels()::get);
		}

		if (stage == Stage.RELAYING) {
			final FakeConnector.FakeTarget target = rig.connector.connect();

			rig.drive();
			return new Reached(caller, target.closes::get);
		}

		return new Reached(caller, rig.sockets.get(0).closes::get);
	}

	private static Fingerprint fingerprint() {
		final byte[] bytes = new byte[Fingerprint.SIZE];

		RANDOM.nextBytes(bytes);
		return new Fingerprint(bytes);
	}

	/* An OPEN as a client writes it: a key of its own and the id of the exit it chose */
	private static Entry open(final FeedWriter writer, final long now, final Fingerprint fingerprint,
		final String exitId) {

		final byte[] id = exitId.getBytes(StandardCharsets.UTF_8);

		return writer.write(now, fingerprint, EntryKind.OPEN, ByteBuffer.allocate(EphemeralKey.SIZE + id.length)
			.put(EphemeralKey.generate(RANDOM).publicKey()).put(id).array());
	}

	private static ChainEntry packet(final Entry entry) {
		return ChainEntry.builder().entries(List.of(entry)).build();
	}

	@Example
	public void acceptsAnOpenThatNamesThisNodeAndRelaysTheSession() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		assertThat(rig.exit.openRelays(), is(1));
		assertThat(rig.connector.requests.size(), is(1));
		assertThat(rig.connector.requests.get(0).port(), is(8080));
		assertThat(rig.connector.requests.get(0).addresses(), equalTo(List.of(InetAddress.getLoopbackAddress())));

		final FakeConnector.FakeTarget target = rig.connector.connect();

		rig.drive();
		caller.session().send(new Data(new byte[] { 1, 2, 3 }));
		rig.drive();
		rig.connector.requests.get(0).listener().received(new byte[] { 9 });
		rig.drive();

		assertThat(target.written.toByteArray(), equalTo(new byte[] { 1, 2, 3 }));
		assertThat(target.readable, is(true));
		assertThat(caller.received, contains(CONNECTED, new Data(new byte[] { 9 })));
	}

	@Example
	public void staysSilentForAnOpenThatNamesAnotherExit() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1, "gn-2", rig.fleet.chain());

		caller.open();

		assertThat(rig.accepts(), is(0));
		assertThat(rig.exit.heldRelays(), is(0));
		assertThat(caller.received, is(empty()));
	}

	@Example
	public void staysSilentWhileTheProxyIsOff() {
		final Rig rig = new Rig();

		rig.fleet.settings.set(Optional.empty());
		rig.caller(1).open();

		assertThat(rig.accepts(), is(0));
		assertThat(rig.exit.heldRelays(), is(0));
	}

	/* The chains this node hosted are retired, and still take entries, once it is no active gridnode */
	@Example
	public void staysSilentWhileThisNodeIsNoActiveGridnode() {
		final Rig rig = new Rig();

		rig.fleet.gridnodes.replaceAll(gridnode -> EXIT.equals(gridnode.getId())
			? ChainFleet.gridnode(EXIT, Gridnode.Status.INACTIVE) : gridnode);
		rig.fleet.host(EXIT).refresh();
		rig.caller(1).open();

		assertThat(rig.fleet.host(EXIT).retiredChains(), hasItem(rig.fleet.chain()));
		assertThat(rig.entries(rig.fleet.chain(), EntryKind.OPEN).size(), is(1));
		assertThat(rig.accepts(), is(0));
		assertThat(rig.exit.heldRelays(), is(0));
	}

	/* A node follows the chain of another group as a client, whose clients choose their exits in that group */
	@Example
	public void staysSilentForAnOpenOnAChainThisNodeOnlyFollows() {
		final ChainFleet fleet = ChainClientTest.twoGroups(16, 4, Set.of(0, 1));
		final String exitId = fleet.view().groups().members(0).get(0);
		final ChainId followed = fleet.view().chains(1).get(0);
		final EmbeddedChannel stream = fleet.stream(exitId, fleet.view().groups().members(1).get(0));
		final ChainExit exit = new ChainExit(fleet.host(exitId), fleet.settings::get, fleet.directory(exitId),
			fleet.clock::get, new SecureRandom(), new ExitContexts(DestinationPolicy.allowLoopback(),
			new FakeConnector(), Runnable::run, () -> { }), new ManualScheduler());

		exit.start();
		assertThat(fleet.host(exitId).follow(followed, stream, Map.of()), is(true));
		fleet.host(exitId).receive(stream, packet(open(new FeedWriter(ChainFleet.key(1), followed),
			fleet.clock.get(), fingerprint(), exitId)));

		assertThat(fleet.host(exitId).chain(followed).orElseThrow().entries().stream()
			.map(entry -> entry.header().kind()).toList(), contains(EntryKind.OPEN));
		assertThat(exit.heldRelays(), is(0));
	}

	/* Around the turn of an epoch a client may still open a session on the chain it joined in the one before */
	@Example
	public void answersAnOpenOnAChainThisNodeRetiredAtTheTurnOfTheEpoch() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		rig.fleet.clock.addAndGet(TimeUnit.SECONDS.toMillis(rig.fleet.settings.get().orElseThrow()
			.reorganizePeriodSeconds()));
		rig.fleet.host(EXIT).refresh();
		caller.open();
		rig.connector.connect();
		rig.drive();

		assertThat(rig.fleet.host(EXIT).retiredChains(), hasItem(rig.fleet.chain()));
		assertThat(caller.received, contains(CONNECTED));
	}

	@Example
	public void refusesTheDestinationWhenTheSporkDoesNotAllowConnect() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		rig.fleet.settings.set(Optional.of(ChainFleet.settings(ChainFleet.MEMBERS, 4, 16, 30, ProxySettings.UDP)));
		caller.open();

		assertThat(caller.received, contains(new Connected(Socks5CommandStatus.FORBIDDEN, SocksAddress.UNSPECIFIED),
			new Close(CloseReason.REFUSED)));
		assertThat(rig.connector.requests, is(empty()));
	}

	/* A session of a copy would write under the same feed and fingerprint as the first, so garbage under the copier's
	   writer would make it post a Close that the client of the first cannot open */
	@Example
	public void answersNoCopyOfAnOpenItAnsweredAndNothingTheCopierSendsReachesTheSession() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);
		final ChainId chain = rig.fleet.chain();

		caller.session().open();

		final Entry open = caller.posted.get(0);

		rig.drive();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		rig.offer(rig.copy(open, 9, chain));
		rig.offer(rig.stranger(9, chain).write(rig.fleet.clock.get(), caller.fingerprint(), EntryKind.SEALED,
			new byte[40]));
		caller.session().send(new Data(new byte[] { 1 }));
		rig.drive();
		rig.connector.requests.get(0).listener().received(new byte[] { 2 });
		rig.drive();

		assertThat(caller.received, contains(CONNECTED, new Data(new byte[] { 2 })));
		assertThat(target.written.toByteArray(), equalTo(new byte[] { 1 }));
		assertThat(rig.accepts(chain, caller.fingerprint()), is(1L));
		assertThat(rig.exit.heldRelays(), is(1));
	}

	/* The ACCEPT of the copy names the copier's writer in its transcript, so it confirms nothing to the client, and
	   the copier has no key for the session its copy opened */
	@Example
	public void costsTheClientOneAttemptWhenACopyOfItsOpenReachesTheExitFirst() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);
		final ChainId chain = rig.fleet.chain();

		caller.session().open();

		final Fingerprint first = caller.fingerprint();

		rig.offer(rig.copy(caller.posted.get(0), 9, chain));
		rig.drive();

		assertThat(rig.accepts(chain, first), is(1L));
		assertThat(caller.session().state(), is(SessionState.OPENING));

		rig.fleet.clock.addAndGet(ClientSession.ACCEPT_TIMEOUT_MILLIS);
		caller.session().tick();
		rig.drive();
		rig.connector.connect();
		rig.drive();

		assertThat(caller.fingerprint(), not(first));
		assertThat(caller.received, contains(CONNECTED));
		assertThat(rig.exit.heldRelays(), is(2));

		rig.fleet.clock.addAndGet(ChainExit.CONNECT_ARRIVAL_MILLIS - ClientSession.ACCEPT_TIMEOUT_MILLIS);
		rig.exit.tick();
		rig.drive();

		assertThat(rig.exit.heldRelays(), is(1));
		assertThat(rig.exit.openRelays(), is(1));
		assertThat(caller.received, contains(CONNECTED));
	}

	@Example
	public void answersACopyOnAnotherChainWithoutTouchingTheSessionOfTheFirst() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.session().open();
		rig.offer(rig.copy(caller.posted.get(0), 9, rig.chain(1)));
		rig.drive();
		rig.connector.connect();
		rig.drive();

		assertThat(rig.accepts(rig.chain(1), caller.fingerprint()), is(1L));
		assertThat(rig.accepts(rig.fleet.chain(), caller.fingerprint()), is(1L));
		assertThat(caller.received, contains(CONNECTED));
	}

	/* The writer of the chain withholds an entry, and the one after it waits for the gap until the retention window
	   has passed, when the chain gives the gap up and tells the exit which fingerprints the waiting entries had */
	private static void leaveAGap(final Rig rig, final FeedWriter writer, final Fingerprint... fingerprints) {
		writer.write(rig.fleet.clock.get(), Fingerprint.NONE, EntryKind.SEALED, new byte[1]);

		for (final Fingerprint fingerprint : fingerprints) {
			rig.offer(writer.write(rig.fleet.clock.get(), fingerprint, EntryKind.SEALED, new byte[1]));
		}

		rig.fleet.clock.addAndGet(ProxySettings.DEFAULTS.chainLimits().retentionMillis() + 1000);
		rig.fleet.host(EXIT).maintain();
	}

	@Example
	public void closesTheRelayOfASessionWhoseEntriesTheChainGaveUpOn() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget lost = rig.connector.connect();

		caller.another();

		final FakeConnector.FakeTarget kept = rig.connector.connect();

		rig.drive();
		leaveAGap(rig, caller.context.writer(), caller.fingerprint());
		rig.drive();

		assertThat(lost.closed, is(true));
		assertThat(kept.closed, is(false));
		assertThat(rig.exit.openRelays(), is(1));
		assertThat(caller.received, contains(CONNECTED, CONNECTED, BROKEN));
	}

	/* A destination that fails to close makes the close of its relay fail */
	@Example
	public void closesEveryRelayOfAGapWhenTheCloseOfOneFails() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget first = rig.connector.connect();
		final ClientSession second = caller.another();
		final FakeConnector.FakeTarget other = rig.connector.connect();

		first.failingClose = true;
		other.failingClose = true;
		leaveAGap(rig, caller.context.writer(), caller.fingerprint(), second.fingerprint().orElseThrow());

		assertThat(List.of(first.closed, other.closed), everyItem(is(true)));
	}

	/* Any writer can leave a gap that names any fingerprint, and the gap of a chain is no gap of another */
	@Example
	public void leavesASessionAloneForAGapOfAnotherWriterOrChain() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		leaveAGap(rig, rig.stranger(9, rig.fleet.chain()), caller.fingerprint());
		rig.exit.gapped(rig.chain(1), new Gap(caller.writer(), 0, 1, Set.of(caller.fingerprint())));

		assertThat(target.closed, is(false));
		assertThat(rig.exit.openRelays(), is(1));
	}

	/* However a session ends while its destination is being dialled, the connect goes with it, as its place does */
	@Example
	public void cancelsTheConnectOfASessionThatEndsBeforeItsDestinationAnswers() {
		final Map<String, BiConsumer<Rig, Caller>> endings = Map.of(
			"the client's Close", (rig, caller) -> {
				caller.session().close(CloseReason.DONE);
				rig.drive();
			},
			"a gap", (rig, caller) -> leaveAGap(rig, caller.context.writer(), caller.fingerprint()),
			"the idle limit", (rig, caller) -> {
				rig.fleet.clock.addAndGet(ProxySettings.DEFAULTS.sessionIdleMillis());
				rig.exit.tick();
			},
			"a dropped chain", (rig, caller) -> {
				rig.fleet.settings.set(Optional.empty());
				rig.fleet.host(EXIT).refresh();
				rig.exit.tick();
			});

		endings.forEach((ending, end) -> {
			final Rig rig = new Rig();
			final Caller caller = rig.caller(1);

			caller.open();
			assertThat(ending, rig.connector.last().cancels().get(), is(0));
			end.accept(rig, caller);
			rig.exit.tick();
			assertThat(ending, rig.connector.last().cancels().get(), is(1));
			assertThat(ending, rig.exit.heldRelays(), is(0));
		});
	}

	@Example
	public void forgetsARelayOnceItsSessionEnded() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();
		caller.session().close(CloseReason.DONE);
		rig.drive();

		assertThat(rig.exit.openRelays(), is(0));
		assertThat(rig.exit.heldRelays(), is(1));

		rig.exit.tick();
		assertThat(rig.exit.heldRelays(), is(0));
	}

	@Example
	public void closesASessionAfterTheIdleTimeOfTheSpork() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		rig.fleet.clock.addAndGet(ProxySettings.DEFAULTS.sessionIdleMillis());
		rig.exit.tick();
		rig.drive();

		assertThat(target.closed, is(true));
		assertThat(caller.received, contains(CONNECTED, IDLE));
		assertThat(rig.exit.heldRelays(), is(0));
	}

	/* The ACCEPTs are not handed to the callers, so their Connects never come. Entries of another writer under a
	   fingerprint are nothing its client said. */
	@Example
	public void closesARelayWhoseClientSentNoConnectWithinTheArrivalInterval() {
		final Rig rig = new Rig();
		final Caller early = rig.caller(1);
		final Caller late = rig.caller(2);

		early.session().open();
		early.send();
		rig.fleet.clock.incrementAndGet();
		late.session().open();
		late.send();
		rig.offer(rig.stranger(9, rig.fleet.chain()).write(rig.fleet.clock.get(), early.fingerprint(),
			EntryKind.SEALED, new byte[40]));

		rig.fleet.clock.addAndGet(ChainExit.CONNECT_ARRIVAL_MILLIS - 2);
		rig.exit.tick();
		assertThat(rig.exit.openRelays(), is(2));

		rig.fleet.clock.incrementAndGet();
		rig.exit.tick();
		assertThat(rig.exit.heldRelays(), is(1));

		rig.fleet.clock.incrementAndGet();
		rig.exit.tick();
		assertThat(rig.exit.heldRelays(), is(0));

		rig.drive();
		assertThat(early.received, contains(IDLE));
		assertThat(late.received, contains(IDLE));
		assertThat(rig.connector.requests, is(empty()));
	}

	@Example
	public void keepsARelayWhoseClientSentItsConnectPastTheArrivalInterval() {
		final Rig rig = new Rig();

		rig.caller(1).open();
		rig.fleet.clock.addAndGet(ChainExit.CONNECT_ARRIVAL_MILLIS);
		rig.exit.tick();

		assertThat(rig.exit.openRelays(), is(1));
	}

	/* The stamp of an OPEN is its writer's word, so the interval cannot run from it */
	@Example
	public void countsTheArrivalIntervalFromTheAnswerAndNotFromTheStampOfTheOpen() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.session().open();
		rig.fleet.clock.addAndGet(20_000);
		caller.send();
		assertThat(rig.exit.heldRelays(), is(1));

		rig.fleet.clock.addAndGet(ChainExit.CONNECT_ARRIVAL_MILLIS - 1);
		rig.exit.tick();
		assertThat(rig.exit.openRelays(), is(1));

		rig.fleet.clock.incrementAndGet();
		rig.exit.tick();
		assertThat(rig.exit.openRelays(), is(0));
	}

	@Example
	public void endsTheRelayWithTheSessionWhenAnEntryOfItsClientDoesNotOpen() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		rig.drive();
		rig.offer(caller.write(caller.fingerprint(), EntryKind.SEALED, new byte[40]));

		assertThat(target.closed, is(true));
		assertThat(rig.exit.openRelays(), is(0));

		rig.drive();
		assertThat(caller.received, contains(CONNECTED, BROKEN));
	}

	@Example
	public void endsARelayWhoseDestinationFailsAndTellsTheClient() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		rig.drive();
		target.failingWrites = true;
		caller.session().send(new Data(new byte[] { 1 }));
		rig.drive();

		assertThat(target.closed, is(true));
		assertThat(rig.exit.openRelays(), is(0));
		assertThat(caller.received, contains(CONNECTED, BROKEN));
	}

	/* A destination that fails to close makes the tick of its relay fail */
	@Example
	public void ticksEveryRelayWhenTheTickOfOneFails() {
		final Rig rig = new Rig();
		final Caller first = rig.caller(1);
		final Caller second = rig.caller(2);

		first.open();
		rig.connector.connect().failingClose = true;
		second.open();
		rig.connector.connect().failingClose = true;
		rig.drive();
		rig.fleet.clock.addAndGet(ProxySettings.DEFAULTS.sessionIdleMillis());
		rig.exit.tick();
		rig.drive();

		assertThat(first.received, contains(CONNECTED, IDLE));
		assertThat(second.received, contains(CONNECTED, IDLE));
	}

	@Example
	public void answersNoMoreOpensOfOneWriterThanItsShare() {
		final Rig rig = new Rig();
		final ChainId chain = rig.fleet.chain();

		assertThat(rig.answered(1, chain, 63), is(63L));
		assertThat(rig.opens(1, chain), is(true));
		assertThat(rig.opens(1, chain), is(false));
		assertThat(rig.opens(1, rig.chain(1)), is(false));
		assertThat(rig.opens(2, chain), is(true));
	}

	@Example
	public void answersNoMoreOpensOnOneChainThanItsShare() {
		final Rig rig = new Rig();
		final ChainId chain = rig.fleet.chain();

		assertThat(rig.answered(1, chain, 64) + rig.answered(2, chain, 63), is(127L));
		assertThat(rig.opens(3, chain), is(true));
		assertThat(rig.opens(4, chain), is(false));
		assertThat(rig.opens(4, rig.chain(1)), is(true));
	}

	@Example
	public void relaysNoMoreSessionsThanItsLimit() {
		final Rig rig = new Rig();

		assertThat(rig.answered(1, rig.chain(0), 64) + rig.answered(2, rig.chain(0), 64)
			+ rig.answered(3, rig.chain(1), 64) + rig.answered(4, rig.chain(1), 63), is(255L));
		assertThat(rig.opens(5, rig.chain(2)), is(true));
		assertThat(rig.opens(6, rig.chain(2)), is(false));
		assertThat(rig.exit.openRelays(), is(256));
	}

	/* Nothing ends meanwhile, so an OPEN is answered exactly when the node, its chain and its writer are below their
	   shares at the moment it arrives. Two chains get most of the OPENs and five writers send them, so that each
	   bound is reached in most tries. */
	@Property(tries = 8)
	public void answersAnOpenExactlyWhileTheNodeItsChainAndItsWriterHaveRoom(
		@ForAll @Size(min = 280, max = 360) List<@IntRange(max = 17) Integer> opens) {

		final Rig rig = new Rig();
		final Map<Integer, Integer> perWriter = new HashMap<>();
		final Map<Integer, Integer> perChain = new HashMap<>();
		int held = 0;

		for (final int open : opens) {
			final int writer = open % 5;
			final int chain = open / 8;
			final boolean room = held < 256 && perChain.getOrDefault(chain, 0) < 128
				&& perWriter.getOrDefault(writer, 0) < 64;

			assertThat(rig.opens(writer, rig.chain(chain)), is(room));

			if (room) {
				held++;
				perWriter.merge(writer, 1, Integer::sum);
				perChain.merge(chain, 1, Integer::sum);
			}
		}
	}

	/* Only a sealed entry of the session's own client under its fingerprint is opened by the session */
	@Property(tries = 40)
	public void leavesASessionWholeWhateverElseTheChainCarries(@ForAll EntryKind kind,
		@ForAll @Size(max = 96) byte[] payload, @ForAll boolean ofItsClient, @ForAll boolean underItsFingerprint) {

		Assume.that(!(ofItsClient && underItsFingerprint && kind == EntryKind.SEALED));

		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget target = rig.connector.connect();
		final Fingerprint fingerprint = underItsFingerprint ? caller.fingerprint() : fingerprint();
		final Entry entry = ofItsClient ? caller.write(fingerprint, kind, payload)
			: rig.stranger(9, rig.fleet.chain()).write(rig.fleet.clock.get(), fingerprint, kind, payload);

		rig.drive();
		assertThat(ChainFleet.warningsWhile(() -> rig.offer(entry)), is(empty()));
		caller.session().send(new Data(new byte[] { 7 }));
		rig.drive();
		rig.connector.requests.get(0).listener().received(new byte[] { 8 });
		rig.drive();

		assertThat(target.written.toByteArray(), equalTo(new byte[] { 7 }));
		assertThat(caller.received, contains(CONNECTED, new Data(new byte[] { 8 })));
		assertThat(rig.exit.heldRelays(), is(1));
	}

	@Example
	public void pausesTheDestinationWhileTheChainHasNoRoomForWhatItSends() {
		final Rig rig = new Rig(ChainFleet.group(ChainFleet.tinyChains()));
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		rig.drive();
		rig.fleet.clock.addAndGet(15_000);
		rig.connector.requests.get(0).listener().received(new byte[300]);
		rig.exit.tick();
		assertThat(target.readable, is(false));

		rig.fleet.clock.addAndGet(16_000);
		rig.fleet.host(EXIT).maintain();
		rig.exit.tick();
		rig.drive();

		assertThat(target.readable, is(true));
		assertThat(caller.received, contains(CONNECTED, new Data(new byte[300])));
	}

	/* A member had no room for an entry of the exit's feed, which pauses the feed until the tick offers the entries
	   again */
	@Example
	public void startsARelayPausedWhileItsChainTakesNothingFromTheExit() {
		final Rig rig = new Rig();

		rig.caller(1).open();
		rig.fleet.host(EXIT).refused(rig.stream, EntryRefused.of(rig.entries(rig.fleet.chain(), EntryKind.ACCEPT)
			.get(0), Admission.FULL));
		rig.caller(2).open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		assertThat(target.readable, is(false));

		rig.exit.tick();
		assertThat(target.readable, is(true));
	}

	@Example
	public void startsAFeedOfItsOwnOnAChainTheHostRebuiltAndEndsTheSessionsOfTheOldOne() {
		final Rig rig = new Rig();

		rig.caller(1).open();

		final FakeConnector.FakeTarget old = rig.connector.connect();
		final WriterId feed = rig.entries(rig.fleet.chain(), EntryKind.ACCEPT).get(0).header().writer();

		rig.fleet.settings.set(Optional.of(ChainFleet.settings(ChainFleet.MEMBERS, 4, 16, 31, 3)));
		rig.fleet.host(EXIT).refresh();

		final Caller caller = rig.caller(2);

		caller.open();
		rig.connector.connect();
		rig.drive();

		assertThat(old.closed, is(true));
		assertThat(caller.received, contains(CONNECTED));
		assertThat(rig.entries(rig.fleet.chain(), EntryKind.ACCEPT).get(0).header().writer(), not(feed));

		rig.exit.tick();
		assertThat(rig.exit.heldRelays(), is(1));
		assertThat(rig.exit.heldChains(), is(1));
	}

	@Example
	public void endsTheRelaysAndForgetsTheFeedOfAChainTheHostDropped() {
		final Rig rig = new Rig();

		rig.caller(1).open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		rig.fleet.settings.set(Optional.empty());
		rig.fleet.host(EXIT).refresh();
		rig.exit.tick();

		assertThat(target.closed, is(true));
		assertThat(rig.exit.heldRelays(), is(0));
		assertThat(rig.exit.heldChains(), is(0));
	}

	@Example
	public void endsEveryRelayOfADroppedChainWhenTheCloseOfOneFails() {
		final Rig rig = new Rig();

		rig.caller(1).open();

		final FakeConnector.FakeTarget first = rig.connector.connect();

		rig.caller(2).open();

		final FakeConnector.FakeTarget second = rig.connector.connect();

		first.failingClose = true;
		second.failingClose = true;
		rig.fleet.settings.set(Optional.empty());
		rig.fleet.host(EXIT).refresh();
		rig.exit.tick();

		assertThat(List.of(first.closed, second.closed), everyItem(is(true)));
	}

	/* A name needs a thread of the resolver, an IP literal none */
	@Example
	public void tellsTheClientANameIsUnreachableWhenTheResolverHasNoRoomAndStillConnectsALiteral() {
		final Rig rig = new Rig(ChainFleet.group(), built -> built.exit(built.fleet.clock::get, command -> {
			throw new RejectedExecutionException();
		}));
		final Caller named = rig.caller(1, EXIT, rig.fleet.chain(), new Connect(Socks5CommandType.CONNECT,
			new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 8080)));

		named.open();

		assertThat(named.received, contains(new Connected(Socks5CommandStatus.FAILURE, SocksAddress.UNSPECIFIED),
			new Close(CloseReason.UNREACHABLE)));
		assertThat(rig.exit.openRelays(), is(0));

		final Caller literal = rig.caller(2);

		literal.open();
		rig.connector.connect();
		rig.drive();

		assertThat(literal.received, contains(CONNECTED));
		assertThat(rig.exit.openRelays(), is(1));
	}

	@Example
	public void looksUpNoMoreNamesAtOnceThanItHasThreadsAndQueueFor() throws Exception {
		final ExitContexts production = ExitContexts.production();
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch started = new CountDownLatch(ExitContexts.RESOLVER_THREADS);
		final Set<Thread> threads = ConcurrentHashMap.newKeySet();
		final Executor resolving = production.of(ProxySettings.DEFAULTS).resolving();
		final Runnable stuck = () -> {
			threads.add(Thread.currentThread());
			started.countDown();

			try {
				release.await();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		};

		try {
			for (int lookup = 0; lookup < 32 + 256; lookup++) {
				resolving.execute(stuck);
			}

			Expect.assertThrows(RejectedExecutionException.class, () -> resolving.execute(stuck));
			assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
			assertThat(threads.size(), is(32));
			assertThat(threads.stream().map(Thread::isDaemon).toList(), everyItem(is(true)));
			assertThat(((ThreadPoolExecutor) resolving).allowsCoreThreadTimeOut(), is(true));
		} finally {
			release.countDown();
			production.close();
		}
	}

	@Example
	public void connectsAndLooksUpNothingOnceReleasedHoweverOften() {
		final ExitContexts production = ExitContexts.production();
		final List<Socks5CommandStatus> failures = new ArrayList<>();

		production.close();
		production.close();
		production.of(ProxySettings.DEFAULTS).connector().connect(List.of(InetAddress.getLoopbackAddress()), 9,
			new TargetListener() {
				@Override
				public void connected(final Target target) {
					target.close();
				}

				@Override
				public void failed(final Socks5CommandStatus status) {
					failures.add(status);
				}

				@Override
				public void received(final byte[] bytes) {
					/* Nothing connects */
				}

				@Override
				public void closed() {
					/* Nothing connects */
				}
			});

		assertThat(failures, contains(Socks5CommandStatus.FAILURE));
		Expect.assertThrows(RejectedExecutionException.class,
			() -> production.of(ProxySettings.DEFAULTS).resolving().execute(() -> { }));
	}

	@Example
	public void keepsTickingAfterATickFailed() {
		final ChainFleet fleet = ChainFleet.group();
		final AtomicBoolean failing = new AtomicBoolean();
		final Rig rig = new Rig(fleet, built -> built.exit(() -> {
			if (failing.get()) {
				throw new IllegalStateException("The clock failed");
			}

			return fleet.clock.get();
		}, Runnable::run));
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		rig.drive();
		fleet.clock.addAndGet(ProxySettings.DEFAULTS.sessionIdleMillis());

		failing.set(true);
		rig.scheduler.scheduled.run();
		assertThat(target.closed, is(false));

		failing.set(false);
		rig.scheduler.scheduled.run();
		assertThat(target.closed, is(true));
	}

	@Example
	public void neverAllowsWhatThePublicPolicyRefusesInProduction() throws Exception {
		final ExitContexts production = ExitContexts.production();

		try {
			final DestinationPolicy policy = production.of(ProxySettings.DEFAULTS).policy();

			assertThat(policy.allows(InetAddress.getLoopbackAddress()), is(false));
			assertThat(policy.allows(InetAddress.getByName("10.1.2.3")), is(false));
			assertThat(policy.allows(InetAddress.getByName("8.8.8.8")), is(true));
		} finally {
			production.close();
		}
	}

	/* The exit's own clock is the system's, so the fleet's is set to it. The lookup runs on a thread of the exit,
	   so the entries it writes are read only once the relay has ended. */
	@Example
	public void refusesALoopbackDestinationWhenBuiltTheWayTheContainerBuildsIt() {
		final ChainFleet fleet = ChainFleet.group();

		fleet.clock.set(System.currentTimeMillis());

		final Rig rig = new Rig(fleet, built -> new ChainExit(fleet.host(EXIT), fleet.settings::get,
			fleet.directory(EXIT)));
		final Caller caller = rig.caller(1);

		caller.session().open();
		caller.send();
		rig.handOut();
		caller.send();
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> rig.exit.openRelays() == 0);
		rig.handOut();
		rig.exit.stop();

		assertThat(caller.received, contains(new Connected(Socks5CommandStatus.FORBIDDEN, SocksAddress.UNSPECIFIED),
			new Close(CloseReason.REFUSED)));
	}

	@Example
	public void closesTheSessionsOfOneChainWithTheReasonGivenAndNoOthers() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		rig.exit.closeChain(rig.fleet.host(EXIT).hostedChains().get(1), CloseReason.DONE);
		assertThat(target.closed, is(false));
		assertThat(rig.exit.openRelays(), is(1));

		rig.exit.closeChain(rig.fleet.chain(), CloseReason.DONE);
		rig.drive();

		assertThat(target.closed, is(true));
		assertThat(caller.received, contains(CONNECTED, new Close(CloseReason.DONE)));
		assertThat(rig.exit.openRelays(), is(0));
	}

	@Example
	public void tellsTheHostWhetherASessionStillRunsOnAChain() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		assertThat(rig.exit.holds(rig.fleet.chain()), is(true));
		assertThat(rig.exit.holds(rig.fleet.host(EXIT).hostedChains().get(1)), is(false));

		caller.session().close(CloseReason.DONE);
		rig.drive();
		assertThat(rig.exit.holds(rig.fleet.chain()), is(false));
	}

	@Example
	public void endsItsSessionsWhenTheHostDropsTheirChainAfterTheGrace() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();
		rig.connector.connect();
		rig.fleet.clock.addAndGet(EPOCH_MILLIS);
		rig.fleet.host(EXIT).refresh();
		rig.fleet.clock.addAndGet(ChainHost.DRAIN_GRACE_MILLIS);
		rig.fleet.host(EXIT).drain();
		rig.drive();

		assertThat(caller.received, contains(CONNECTED, new Close(CloseReason.DONE)));
		assertThat(rig.fleet.host(EXIT).retiredChains(), is(empty()));
	}

	/* Wherever its session got, a relay of the chain ends with the reason given and lets go of what it held of the
	   destination once, through its own close, the tick frees its place, and the relay of another chain goes on */
	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void closesEverySessionOfTheChainWithTheReasonGivenWhereverItGot(@ForAll Stage stage,
		@ForAll CloseReason reason) {

		final Rig rig = new Rig();
		final Caller other = rig.caller(1, EXIT, rig.chain(1));

		other.open();

		final FakeConnector.FakeTarget kept = rig.connector.connect();

		rig.drive();

		final Reached reached = reach(rig, stage);

		assertThat(reached.caller().received, equalTo(stage.told));
		assertThat(rig.exit.holds(rig.chain(0)), is(true));

		rig.exit.closeChain(rig.chain(0), reason);
		rig.handOut();
		assertThat(reached.caller().received, equalTo(stage.thenClosed(reason)));
		assertThat(rig.exit.holds(rig.chain(0)), is(false));

		rig.exit.closeChain(rig.chain(0), reason);
		rig.exit.tick();
		rig.handOut();

		assertThat(reached.caller().received, equalTo(stage.thenClosed(reason)));
		assertThat(reached.closes().getAsInt(), is(stage.holdsADestination ? 1 : 0));
		assertThat(rig.exit.heldRelays(), is(1));
		assertThat(rig.exit.holds(rig.chain(1)), is(true));
		assertThat(kept.closes.get(), is(0));
		assertThat(other.received, contains(CONNECTED));
	}

	/* The exit holds a retired chain while a session runs on it, and the host tells it when the grace is over: the
	   sessions end in order wherever they got, the destination sees a close and a client still waiting for its reply
	   takes the Close as a general failure */
	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void endsEverySessionOfAChainInOrderOnceItsGraceIsOver(@ForAll Stage stage) {
		final Rig rig = new Rig();
		final Reached reached = reach(rig, stage);
		final ChainHost host = rig.fleet.host(EXIT);

		rig.fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();
		rig.fleet.clock.addAndGet(ChainHost.DRAIN_GRACE_MILLIS - 1);
		host.drain();
		rig.handOut();
		assertThat(host.retiredChains(), contains(rig.fleet.chain()));
		assertThat(reached.caller().received, equalTo(stage.told));

		rig.fleet.clock.addAndGet(1);
		host.drain();
		rig.handOut();

		assertThat(host.retiredChains(), is(empty()));
		assertThat(reached.caller().received, equalTo(stage.thenClosed(CloseReason.DONE)));
		assertThat(reached.closes().getAsInt(), is(stage.holdsADestination ? 1 : 0));
		assertThat(TunnelFailure.of(CloseReason.DONE), is(TunnelFailure.GENERAL_FAILURE));
	}

	@Example
	public void closesTheRelaysOfAChainWhoseGraceIsOverWithNoLockOfTheHostHeld() {
		final Rig rig = new Rig();
		final AtomicBoolean locked = new AtomicBoolean(true);

		rig.caller(1).open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		target.closing = () -> locked.set(Thread.holdsLock(rig.fleet.host(EXIT)));
		rig.fleet.clock.addAndGet(EPOCH_MILLIS);
		rig.fleet.host(EXIT).refresh();
		rig.fleet.clock.addAndGet(ChainHost.DRAIN_GRACE_MILLIS);
		rig.fleet.host(EXIT).tick();

		assertThat(target.closes.get(), is(1));
		assertThat(locked.get(), is(false));
	}

	@Example
	public void closesEveryRelayOfTheChainWhenTheCloseOfOneFails() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget first = rig.connector.connect();

		caller.another();

		final FakeConnector.FakeTarget second = rig.connector.connect();

		rig.drive();
		first.failingClose = true;
		second.failingClose = true;
		rig.exit.closeChain(rig.fleet.chain(), CloseReason.DONE);
		rig.handOut();

		assertThat(List.of(first.closes.get(), second.closes.get()), everyItem(is(1)));
		assertThat(caller.received, contains(CONNECTED, CONNECTED, DONE, DONE));
	}

	@Example
	public void closesEveryRelayOfTheChainWhenTheCloseOfOneEndsWithAnErrorAndThenThrowsIt() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget first = rig.connector.connect();

		caller.another();

		final FakeConnector.FakeTarget second = rig.connector.connect();

		rig.drive();
		first.closeError = new StackOverflowError();
		second.closeError = new StackOverflowError();

		Expect.assertThrows(StackOverflowError.class, () -> rig.exit.closeChain(rig.fleet.chain(), CloseReason.DONE));
		rig.handOut();

		assertThat(List.of(first.closes.get(), second.closes.get()), everyItem(is(1)));
		assertThat(caller.received, contains(CONNECTED, CONNECTED, DONE, DONE));
	}

	/* The host keeps its drains, so a stopped exit holds no chain and what it is told changes nothing */
	@Example
	public void holdsNoChainAndEndsNothingOnceTheDaemonStops() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		rig.drive();
		rig.exit.stop();

		assertThat(rig.exit.holds(rig.fleet.chain()), is(false));

		rig.exit.expired(rig.fleet.chain());
		rig.exit.closeChain(rig.fleet.chain(), CloseReason.BROKEN);
		rig.handOut();

		assertThat(target.closes.get(), is(0));
		assertThat(caller.received, contains(CONNECTED));
	}

	/* The host keeps its listeners, so what it tells a stopped exit must change nothing */
	@Example
	public void ticksOnceASecondAndIgnoresTheChainsOnceTheDaemonStops() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		rig.exit.tick();
		assertThat(rig.scheduler.periodMillis, is(1000L));
		assertThat(rig.scheduler.schedules, is(1));

		caller.open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		rig.drive();
		rig.exit.stop();
		caller.session().send(new Data(new byte[] { 1 }));
		rig.drive();
		rig.exit.gapped(rig.fleet.chain(), new Gap(caller.writer(), 0, 1, Set.of(caller.fingerprint())));
		rig.caller(2).open();

		assertThat(rig.scheduler.isShutdown(), is(true));
		assertThat(rig.released.get(), is(true));
		assertThat(target.written.size(), is(0));
		assertThat(target.closed, is(false));
		assertThat(rig.accepts(), is(1));
	}

	/* An exit whose host floods what this node posts on a delivery thread of its own, as the daemon's host does, and
	   whose chains drain on the threads that hand them entries. Its one member stream takes no writes, so the flood
	   passes it over, and what the exit writes is read from its chains. */
	private static final class Threaded implements AutoCloseable {
		private final ChainFleet fleet = new ChainFleet(ChainFleet.MEMBERS);
		private final ExecutorService delivery = Executors.newSingleThreadExecutor();
		private final ExecutorService threads = Executors.newFixedThreadPool(4);
		private final EmbeddedChannel stream = ChainFleet.unwritable(fleet.stream(EXIT, "gn-0"));
		private final FakeConnector connector = new FakeConnector();
		private final List<Future<?>> work = new ArrayList<>();
		private final ChainHost host;
		private final ChainExit exit;

		private Threaded() {
			fleet.host("gn-0");
			host = new ChainHost(fleet.settings::get, fleet.directory(EXIT), fleet.transport(EXIT),
				fleet.clock::get, new Random(1), new ManualScheduler(), delivery);
			host.refresh();
			exit = new ChainExit(host, fleet.settings::get, fleet.directory(EXIT), fleet.clock::get,
				new SecureRandom(), new ExitContexts(DestinationPolicy.allowLoopback(), connector,
				Runnable::run, () -> { }), new ManualScheduler());
			exit.start();
		}

		private ChainId chain(final int index) {
			return host.hostedChains().get(index);
		}

		private void offer(final Entry entry) {
			host.receive(stream, packet(entry));
		}

		private void run(final Runnable task) {
			work.add(threads.submit(task));
		}

		/* The tasks start together, each on a thread of its own */
		private void atOnce(final List<Runnable> tasks) {
			final CountDownLatch start = new CountDownLatch(1);

			tasks.forEach(task -> run(() -> {
				try {
					start.await();
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}

				task.run();
			}));
			start.countDown();
		}

		private long accepts(final ChainId chainId) {
			return host.chain(chainId).orElseThrow().entries().stream()
				.filter(entry -> entry.header().kind() == EntryKind.ACCEPT).count();
		}

		/* A thread that hands a chain entries leaves them to the delivery thread when that one drains the chain, and
		   it drains until nothing waits before it takes the next task */
		private void quiesce() throws Exception {
			delivery.submit(() -> { }).get(WAIT_SECONDS, TimeUnit.SECONDS);
		}

		/* What the threads threw is thrown here */
		private void awaitWork() throws Exception {
			threads.shutdown();
			assertThat("No thread waits for good", threads.awaitTermination(DEADLOCK_SECONDS, TimeUnit.SECONDS),
				is(true));

			for (final Future<?> done : work) {
				done.get();
			}

			quiesce();
		}

		@Override
		public void close() {
			threads.shutdownNow();
			delivery.shutdownNow();
		}
	}

	/* The destinations call back on threads of their own and the tick runs on another. The clock stands still, so the
	   entries of the clients fit what the stream may hand over in one second. */
	@Example
	public void relaysOnTheThreadsOfTheChainTheDestinationsAndTheTickAtOnce() throws Exception {
		final int rounds = 800;

		try (Threaded node = new Threaded()) {
			final ChainId chain = node.chain(0);
			final List<Entry> posted = new ArrayList<>();
			final List<ClientSession> sessions = IntStream.range(0, 4).mapToObj(number -> new ClientSession(
				new SessionContext(new FeedWriter(ChainFleet.key(300 + number), chain), posted::add,
				node.fleet.clock::get, new SecureRandom(), ProxySettings.DEFAULTS),
				(fingerprint, excluded) -> Optional.of(EXIT), CONNECT)).toList();
			final Runnable post = () -> {
				posted.forEach(node::offer);
				posted.clear();
			};

			sessions.forEach(ClientSession::open);
			post.run();
			node.quiesce();
			node.host.chain(chain).orElseThrow().entries()
				.forEach(entry -> sessions.forEach(session -> session.receive(entry)));
			post.run();
			node.quiesce();

			final List<FakeConnector.FakeTarget> targets = IntStream.range(0, sessions.size())
				.mapToObj(node.connector::connect).toList();

			node.run(() -> IntStream.range(0, rounds).forEach(round -> {
				sessions.forEach(session -> session.send(new Data(new byte[] { 1 })));
				post.run();
			}));
			IntStream.range(0, 2).forEach(loop -> node.run(() -> IntStream.range(0, rounds)
				.forEach(round -> IntStream.range(0, sessions.size()).filter(relay -> relay % 2 == loop)
				.forEach(relay -> node.connector.requests.get(relay).listener().received(new byte[1])))));
			node.run(() -> IntStream.range(0, rounds).forEach(round -> node.exit.tick()));
			node.awaitWork();

			assertThat(targets.stream().map(target -> target.written.size()).toList(), everyItem(is(rounds)));
			assertThat(node.exit.openRelays(), is(sessions.size()));
		}
	}

	/* Every chain drains on the thread that hands it entries, so OPENs of one writer on four chains reach the exit at
	   once */
	@Example
	public void keepsTheShareOfAWriterWhenItsOpensArriveOnSeveralChainsAtOnce() throws Exception {
		try (Threaded node = new Threaded()) {
			final List<FeedWriter> feeds = IntStream.range(0, 4)
				.mapToObj(chain -> new FeedWriter(ChainFleet.key(1), node.chain(chain))).toList();

			IntStream.range(0, 62)
				.forEach(open -> node.offer(open(feeds.get(0), ChainFleet.NOW, fingerprint(), EXIT)));
			node.atOnce(feeds.stream().map(feed -> open(feed, ChainFleet.NOW, fingerprint(), EXIT))
				.<Runnable>map(open -> () -> node.offer(open)).toList());
			node.awaitWork();

			assertThat(node.exit.heldRelays(), is(64));
		}
	}

	/* A chain the host rebuilt drains on two threads while the old one is swapped out, so an OPEN and its copy may
	   both be on their way to an answer before either has one */
	@Example
	public void answersOneOfTwoOpensOfAFingerprintThatArriveAtOnce() throws Exception {
		try (Threaded node = new Threaded()) {
			final ChainId chain = node.chain(0);
			final Entry open = open(new FeedWriter(ChainFleet.key(1), chain), ChainFleet.NOW, fingerprint(),
				EXIT);
			final Entry copy = new FeedWriter(ChainFleet.key(2), chain).write(ChainFleet.NOW,
				open.header().fingerprint(), EntryKind.OPEN, open.payload());

			node.atOnce(List.of(() -> node.exit.appended(chain, open), () -> node.exit.appended(chain, copy)));
			node.awaitWork();

			assertThat(node.accepts(chain), is(1L));
			assertThat(node.exit.heldRelays(), is(1));
		}
	}
}
