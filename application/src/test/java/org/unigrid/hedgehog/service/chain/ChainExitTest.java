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

package org.unigrid.hedgehog.service.chain;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.jqwik.Expect;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Gap;
import org.unigrid.hedgehog.proxy.chain.Tag;
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
import org.unigrid.hedgehog.proxy.session.ExitCandidate;
import org.unigrid.hedgehog.proxy.session.ExitSelector;
import org.unigrid.hedgehog.proxy.session.NoiseNk;
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
		private final ExitSelector exits;
		private final Connect connect;
		private final SessionContext context;
		private final List<Entry> posted = new ArrayList<>();
		private final List<SealedMessage> received = new ArrayList<>();
		private final List<ClientSession> sessions = new ArrayList<>();

		private Caller(final Rig rig, final int number, final ExitSelector exits, final ChainId chainId,
			final Connect connect) {

			this.rig = rig;
			this.exits = exits;
			this.connect = connect;
			context = new SessionContext(new FeedWriter(ChainFleet.key(300 + number), chainId), posted::add,
				rig.fleet.clock::get, new SecureRandom(), ProxySettings.DEFAULTS);
			sessions.add(newSession());
		}

		private ClientSession newSession() {
			return new ClientSession(context, exits, connect);
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

		/* The tag of the next message of the first session, which its relay looks out for. The message is sent and
		   never delivered, so the feed of this caller has a hole from then on. */
		private Tag nextTag() {
			return nextTag(session());
		}

		private Tag nextTag(final ClientSession of) {
			of.send(new Data(new byte[] { 0 }));
			return posted.remove(posted.size() - 1).header().tag();
		}

		/* An entry of this caller's own feed, written beside its sessions */
		private Entry write(final Tag tag, final byte[] payload) {
			return context.writer().write(rig.fleet.clock.get(), tag, payload);
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
			return exit(fleet.settings::get, clock, resolving);
		}

		private ChainExit exit(final ProxySettingsSource proxy, final LongSupplier clock, final Executor resolving) {
			return new ChainExit(fleet.host(EXIT), proxy, ChainFleet.sealingKey(EXIT), clock, new SecureRandom(),
				new ExitContexts(DestinationPolicy.allowLoopback(), connector, resolving, () -> released.set(true),
				this::associate), scheduler);
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
			return caller(number, excluded -> Optional.of(candidate(exitId)), chainId, connect);
		}

		private Caller caller(final int number, final ExitSelector exits, final ChainId chainId,
			final Connect connect) {

			final Caller caller = new Caller(this, number, exits, chainId, connect);

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

		/* An opening sealed to the exit from a writer that never completes the handshake; true when it was answered */
		private boolean opens(final int writer, final ChainId chainId) {
			final Entry opening = opening(stranger(writer, chainId), fleet.clock.get(), EXIT);

			offer(opening);
			return answers(chainId, opening) > 0;
		}

		private long answered(final int writer, final ChainId chainId, final int opens) {
			return IntStream.range(0, opens).filter(open -> opens(writer, chainId)).count();
		}

		/* What another writer posts when it copies an entry it saw on the chain */
		private Entry copy(final Entry entry, final int writer, final ChainId chainId) {
			return stranger(writer, chainId).write(fleet.clock.get(), entry.header().tag(), entry.payload());
		}

		private List<Entry> entries(final ChainId chainId) {
			return fleet.host(EXIT).chain(chainId).orElseThrow().entries();
		}

		/* The entries of the chain that the exit's sealing key opens */
		private List<Entry> openings(final ChainId chainId) {
			return entries(chainId).stream().filter(entry -> ChainFleet.responderOf(entry, EXIT).isPresent())
				.toList();
		}

		/* The entries of the chain under the answer tag of an opening sealed to the exit */
		private List<Entry> answers(final ChainId chainId) {
			final Set<Tag> tags = openings(chainId).stream().map(opening -> ChainFleet.answerTagOf(opening,
				EXIT)).collect(Collectors.toSet());

			return entries(chainId).stream().filter(entry -> tags.contains(entry.header().tag())).toList();
		}

		private long answers(final ChainId chainId, final Entry opening) {
			final Tag tag = ChainFleet.answerTagOf(opening, EXIT);

			return entries(chainId).stream().filter(entry -> entry.header().tag().equals(tag)).count();
		}

		private int accepts() {
			return answers(fleet.chain()).size();
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

	/* The ways a relay ends: its client closes, its destination closes, it goes idle, or the grace of its chain is
	   over */
	private enum Ending {
		CLIENT {
			@Override
			void end(final Rig rig, final Caller caller) {
				caller.session().close(CloseReason.DONE);
				rig.drive();
			}
		},
		DESTINATION {
			@Override
			void end(final Rig rig, final Caller caller) {
				rig.connector.requests.get(0).listener().closed();
			}
		},
		IDLE {
			@Override
			void end(final Rig rig, final Caller caller) {
				rig.fleet.clock.addAndGet(ProxySettings.DEFAULTS.sessionIdleMillis());
				rig.exit.tick();
			}
		},
		GRACE {
			@Override
			void end(final Rig rig, final Caller caller) {
				rig.exit.expired(rig.fleet.chain());
			}
		};

		abstract void end(Rig rig, Caller caller);
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

	/* The opening, the answer, the Connect, the Connected, a Data the exit sends 20 s later and an entry of a stranger
	   fill a chain of six entries. The clock steps back 15 s, the destination closes and the Close waits for room, which
	   entries of the stranger take as soon as the first four age out, until the relay's 30 s are up. */
	private static void drainPastTheRetention(final Rig rig, final Caller caller) {
		final ChainId chainId = rig.fleet.chain();
		final FeedWriter stranger = rig.stranger(1, chainId);

		caller.open();
		rig.connector.connect();
		rig.drive();
		rig.fleet.clock.addAndGet(20_000);
		rig.connector.requests.get(0).listener().received(new byte[] { 1 });
		rig.offer(stranger.write(rig.fleet.clock.get(), ChainFleet.TAG, padded(new byte[1])));
		rig.drive();
		rig.fleet.clock.addAndGet(-15_000);
		rig.connector.requests.get(0).listener().closed();
		rig.exit.tick();
		assertThat(rig.exit.drainingRelays(), is(1));

		rig.fleet.clock.set(ChainFleet.NOW + 31_000);
		rig.fleet.host(EXIT).maintain();
		IntStream.range(0, 4).forEach(entry -> rig.offer(stranger.write(rig.fleet.clock.get(), ChainFleet.TAG,
			padded(new byte[1]))));
		rig.exit.tick();
		assertThat(rig.exit.drainingRelays(), is(1));

		rig.fleet.clock.set(ChainFleet.NOW + 40_000);
		rig.exit.tick();
		rig.drive();
	}

	/* One session of the caller that opens, takes the answer if the exit sends one, and ends at once. Only this
	   session is handed what the exit writes, as the caller keeps no record of it. */
	private static void churn(final Rig rig, final Caller caller) {
		final ClientSession session = new ClientSession(caller.context, caller.exits, caller.connect);

		session.open();
		caller.send();
		handTo(rig, session);
		session.close(CloseReason.DONE);
		caller.send();
		handTo(rig, session);
		rig.exit.tick();
	}

	private static void handTo(final Rig rig, final ClientSession session) {
		for (Object out = rig.stream.readOutbound(); out != null; out = rig.stream.readOutbound()) {
			((ChainEntry) out).getEntries().forEach(session::receive);
		}
	}

	/* gn-0 refuses every entry the exit floods to it as one its chain has no room for, as a member that keeps the
	   exit's writers paused does, and takes it all the same */
	private static void refuseEverythingAt(final Rig rig) {
		rig.stream.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
			@Override
			public void write(final ChannelHandlerContext context, final Object packet, final ChannelPromise promise) {
				if (packet instanceof ChainEntry flooded) {
					flooded.getEntries().forEach(entry -> rig.fleet.host(EXIT).refused(rig.stream,
						EntryRefused.of(entry, Admission.FULL)));
				}

				context.write(packet, promise);
			}
		});
	}

	private static byte[] padded(final byte[] payload) {
		return ChainFleet.padded(payload, ProxySettings.DEFAULTS.chainLimits().maxEntryBytes());
	}

	private static ExitCandidate candidate(final String exitId) {
		return new ExitCandidate(exitId, ChainFleet.sealingKey(exitId).publicKey().orElseThrow());
	}

	/* An opening as a client writes it: its handshake message sealed to the key the exit announces, with the chain
	   and the writer in the prologue, in an entry of the smallest class under a random tag */
	@SneakyThrows
	private static Entry opening(final FeedWriter writer, final long now, final String exitId) {
		final byte[] prologue = ByteBuffer.allocate(ChainId.SIZE + WriterId.SIZE).put(writer.chainId().bytes())
			.put(writer.writer().bytes()).array();
		final byte[] message = NoiseNk.initiate(candidate(exitId).sealingKey(), prologue, RANDOM).message();

		return writer.write(now, Tag.random(RANDOM), padded(message));
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
		assertThat(rig.openings(rig.fleet.chain()).size(), is(1));
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
		final ChainExit exit = new ChainExit(fleet.host(exitId), fleet.settings::get, ChainFleet.sealingKey(exitId),
			fleet.clock::get, new SecureRandom(), new ExitContexts(DestinationPolicy.allowLoopback(),
			new FakeConnector(), Runnable::run, () -> { }), new ManualScheduler());

		exit.start();
		assertThat(fleet.host(exitId).follow(followed, stream, Map.of()), is(true));
		fleet.host(exitId).receive(stream, packet(opening(new FeedWriter(ChainFleet.key(1), followed),
			fleet.clock.get(), exitId)));

		assertThat(fleet.host(exitId).chain(followed).orElseThrow().entries(), hasSize(1));
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

	/* The prologue of an opening binds its writer, so a copy under another writer opens nothing, and an entry of
	   another writer under the tag of the session's next message is nothing its client said */
	@Example
	public void answersNoCopyOfAnOpeningAndNothingTheCopierSendsReachesTheSession() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);
		final ChainId chain = rig.fleet.chain();

		caller.session().open();

		final Entry open = caller.posted.get(0);

		rig.drive();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		rig.offer(rig.copy(open, 9, chain));
		caller.session().send(new Data(new byte[] { 1 }));
		rig.offer(rig.stranger(9, chain).write(rig.fleet.clock.get(), caller.posted.get(0).header().tag(),
			padded(new byte[40])));
		rig.drive();
		rig.connector.requests.get(0).listener().received(new byte[] { 2 });
		rig.drive();

		assertThat(caller.received, contains(CONNECTED, new Data(new byte[] { 2 })));
		assertThat(target.written.toByteArray(), equalTo(new byte[] { 1 }));
		assertThat(rig.answers(chain, open), is(1L));
		assertThat(rig.accepts(), is(1));
		assertThat(rig.exit.heldRelays(), is(1));
	}

	/* A copy of an opening that reaches the exit first opens nothing, so it costs the client nothing either */
	@Example
	public void costsTheClientNothingWhenACopyOfItsOpeningReachesTheExitFirst() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);
		final ChainId chain = rig.fleet.chain();

		caller.session().open();

		final Entry open = caller.posted.get(0);

		rig.offer(rig.copy(open, 9, chain));
		rig.drive();
		rig.connector.connect();
		rig.drive();

		assertThat(rig.answers(chain, open), is(1L));
		assertThat(rig.accepts(), is(1));
		assertThat(caller.received, contains(CONNECTED));
		assertThat(rig.exit.heldRelays(), is(1));
	}

	/* The prologue binds the chain as well */
	@Example
	public void answersNoCopyOnAnotherChain() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.session().open();
		rig.offer(rig.copy(caller.posted.get(0), 9, rig.chain(1)));
		rig.drive();
		rig.connector.connect();
		rig.drive();

		assertThat(rig.answers(rig.chain(1)), is(empty()));
		assertThat(rig.accepts(), is(1));
		assertThat(caller.received, contains(CONNECTED));
	}

	/* Two exits take in the same openings, and only the one whose key sealed an opening answers it */
	@Example
	public void onlyTheChosenExitAnswers() {
		final Rig rig = new Rig();
		final ChainId chain = rig.fleet.chain();
		final String otherId = "gn-2";
		final ChainExit other = new ChainExit(rig.fleet.host(otherId), rig.fleet.settings::get,
			ChainFleet.sealingKey(otherId), rig.fleet.clock::get, new SecureRandom(), new ExitContexts(
			DestinationPolicy.allowLoopback(), new FakeConnector(), Runnable::run, () -> { }), new ManualScheduler());
		final Entry toExit = opening(rig.stranger(1, chain), rig.fleet.clock.get(), EXIT);
		final Entry toOther = opening(rig.stranger(2, chain), rig.fleet.clock.get(), otherId);

		other.start();

		for (final Entry opening : List.of(toExit, toOther)) {
			rig.offer(opening);
			rig.fleet.host(otherId).receive(rig.fleet.stream(otherId, "gn-0"), packet(opening));
		}

		final List<Entry> atOther = rig.fleet.host(otherId).chain(chain).orElseThrow().entries();

		assertThat(rig.answers(chain, toExit), is(1L));
		assertThat(rig.accepts(), is(1));
		assertThat(rig.exit.heldRelays(), is(1));
		assertThat(other.heldRelays(), is(1));
		assertThat(atOther.stream().filter(entry -> entry.header().tag().equals(ChainFleet.answerTagOf(
			toOther, otherId))).count(), is(1L));
		assertThat(atOther.stream().filter(entry -> entry.header().tag().equals(ChainFleet.answerTagOf(
			toExit, EXIT))).count(), is(0L));
	}

	/* The writer of the chain withholds an entry, and the ones after it, under the tags given, wait for the gap until
	   the retention window has passed, when the chain gives the gap up and tells the exit the tags they had */
	private static void leaveAGap(final Rig rig, final FeedWriter writer, final Tag... tags) {
		writer.write(rig.fleet.clock.get(), ChainFleet.TAG, padded(new byte[1]));

		for (final Tag tag : tags) {
			rig.offer(writer.write(rig.fleet.clock.get(), tag, padded(new byte[1])));
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
		leaveAGap(rig, caller.context.writer(), caller.nextTag());
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
		leaveAGap(rig, caller.context.writer(), caller.nextTag(), caller.nextTag(second));

		assertThat(List.of(first.closed, other.closed), everyItem(is(true)));
	}

	/* Any writer that saw a tag can leave a gap that names it, and the gap of a chain is no gap of another */
	@Example
	public void leavesASessionAloneForAGapOfAnotherWriterOrChain() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		final Tag tag = caller.nextTag();

		leaveAGap(rig, rig.stranger(9, rig.fleet.chain()), tag);
		rig.exit.gapped(rig.chain(1), new Gap(caller.writer(), 0, 1, Set.of(tag)));

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
			"a gap", (rig, caller) -> leaveAGap(rig, caller.context.writer(), caller.nextTag()),
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

	/* Every way a relay ends posts the Close of the exit, the last entry of the session's own feed, so that the chain
	   can let the feed go once it is past the retention window */
	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void endsTheFeedOfTheSessionWithItsCloseWhicheverWayTheRelayEnds(@ForAll Ending ending) {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();
		rig.connector.connect();
		rig.drive();

		final WriterId writer = rig.answers(rig.fleet.chain()).get(0).header().writer();

		ending.end(rig, caller);
		rig.drive();

		final List<Entry> written = rig.entries(rig.fleet.chain()).stream().filter(entry -> entry.header().writer()
			.equals(writer)).toList();

		assertThat(rig.exit.openRelays(), is(0));
		assertThat(written.get(written.size() - 1).header().last(), is(true));
		assertThat(written.stream().filter(entry -> entry.header().last()).count(), is(1L));
	}

	/* No two sessions of the exit share a writer, so its feeds on the chain do not tell which sessions one exit
	   relays: each session writes its answer and all that follows as a writer of its own */
	@Example
	public void eachSessionHasItsOwnWriter() {
		final Rig rig = new Rig();
		final Caller first = rig.caller(1);
		final Caller second = rig.caller(2);

		first.open();
		rig.connector.connect();
		first.another();
		rig.connector.connect();
		second.open();
		rig.connector.connect();
		rig.drive();

		final List<WriterId> writers = rig.answers(rig.fleet.chain()).stream().map(answer -> answer.header().writer())
			.toList();
		final Map<WriterId, Long> written = rig.entries(rig.fleet.chain()).stream()
			.map(entry -> entry.header().writer()).filter(writers::contains)
			.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

		assertThat(Set.copyOf(writers), hasSize(3));
		assertThat(written, equalTo(writers.stream().collect(Collectors.toMap(Function.identity(), writer -> 2L))));
		assertThat(first.received, contains(CONNECTED, CONNECTED));
		assertThat(second.received, contains(CONNECTED));
	}

	/* A session that ended takes its writer with it: a member that refuses an entry of that writer afterwards gets
	   nothing offered again under it */
	@Example
	public void postsNothingMoreAsTheWriterOfASessionThatEnded() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();
		rig.connector.connect();
		rig.drive();

		final WriterId writer = rig.answers(rig.fleet.chain()).get(0).header().writer();
		final Entry last = rig.entries(rig.fleet.chain()).stream().filter(entry -> entry.header().writer()
			.equals(writer)).reduce((earlier, later) -> later).orElseThrow();

		caller.session().close(CloseReason.DONE);
		rig.drive();
		rig.exit.tick();
		rig.fleet.host(EXIT).refused(rig.stream, EntryRefused.of(last, Admission.FULL));
		rig.exit.tick();
		rig.exit.tick();

		assertThat(rig.exit.heldRelays(), is(0));
		assertThat((Object) rig.stream.readOutbound(), is(nullValue()));
	}

	/* The Close of a session whose chain had no room waits in the outbox of the session, which outlives the relay
	   until the chain takes what waits in it. The relay that drains holds no place among the relays meanwhile. */
	@Example
	public void placesTheCloseOfASessionThatEndedWhileItsChainHadNoRoom() {
		/* The opening, the answer, the Connect and the Connected fill the chain */
		final Rig rig = new Rig(ChainFleet.group(ChainFleet.smallChains(4)));
		final Caller caller = rig.caller(1);

		caller.open();
		rig.connector.connect();
		rig.drive();
		rig.fleet.clock.addAndGet(10_000);
		rig.connector.requests.get(0).listener().closed();
		rig.exit.tick();
		rig.drive();

		assertThat(caller.received, contains(CONNECTED));
		assertThat(rig.exit.heldRelays(), is(0));
		assertThat(rig.exit.drainingRelays(), is(1));

		rig.fleet.clock.addAndGet(21_000);
		rig.fleet.host(EXIT).maintain();
		rig.exit.tick();
		rig.drive();

		assertThat(caller.received, contains(CONNECTED, DONE));
		assertThat(rig.exit.drainingRelays(), is(0));
	}

	/* A client that opens and ends sessions over and over leaves a writer of the exit on the chain for each, which
	   holds a place there until its entries aged out. The exit answers no opening on a chain where the writers of its
	   sessions hold their share of the places, so the churn of one client leaves the chain open to new clients. */
	@Example
	public void churnedSessionsOfOneClientNeverTakeTheExitPastItsShareOfAChain() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);
		final ChainId chainId = rig.fleet.chain();

		for (int round = 1; round <= HostedChain.MAX_WRITERS; round++) {
			churn(rig, caller);

			/* The stream brings in no more entries than its budget of a second */
			if (round % 1000 == 0) {
				rig.fleet.clock.addAndGet(1000);
			}
		}

		final Set<WriterId> exitWriters = new HashSet<>(rig.fleet.host(EXIT).chain(chainId).orElseThrow().heads()
			.keySet());

		exitWriters.remove(caller.writer());
		assertThat(exitWriters.size(), lessThanOrEqualTo(ChainExit.MAX_WRITERS_PER_CHAIN));
		assertThat(rig.fleet.host(EXIT).join(new EmbeddedChannel(), JoinChain.builder().requestId(1)
			.writer(ChainFleet.writer(999)).chainId(chainId).build()).getStatus(), is(JoinReply.Status.JOINED));

		/* The writers of the sessions that ended count no more once their entries aged out */
		rig.fleet.clock.addAndGet(31_000);
		rig.exit.tick();
		churn(rig, caller);
		assertThat(rig.fleet.host(EXIT).chain(chainId).orElseThrow().heads().size(), is(exitWriters.size() + 2));
	}

	/* A relay drains for the retention of its chain at most. Its writer stamped ahead before the clock stepped back, so
	   its Close is still young enough for the chain when the time is up, and only the chain, kept full, has no room
	   for it: the relay goes all the same, and its Close with it. */
	@Example
	public void letsGoOfADrainingRelayOnceTheRetentionOfItsChainIsUp() {
		final Rig rig = new Rig(ChainFleet.group(ChainFleet.smallChains(6)));
		final Caller caller = rig.caller(1);

		drainPastTheRetention(rig, caller);

		assertThat(rig.exit.drainingRelays(), is(0));
		assertThat(caller.received, contains(CONNECTED, new Data(new byte[] { 1 })));
	}

	/* A relay that went before its Close reached the chain leaves a feed that never ended, which the chain keeps until
	   it is dropped, so its writer goes on counting against the share of the exit once the retention window is past */
	@Example
	public void countsTheWriterOfARelayWhoseCloseNeverReachedTheChainForAsLongAsTheChainIsHeld() {
		final Rig rig = new Rig(ChainFleet.group(ChainFleet.smallChains(6)));

		drainPastTheRetention(rig, rig.caller(1));
		rig.fleet.clock.set(ChainFleet.NOW + 80_000);
		rig.exit.tick();

		assertThat(rig.exit.writersOn(rig.fleet.chain(), rig.fleet.clock.get()), is(1L));
	}

	/* A writer may have stamped ahead before the clock stepped back, so the writer of an ended session holds its
	   place until its latest entry aged out of the chain, and not just for a retention from when its relay went */
	@Example
	public void holdsThePlaceOfAnEndedWriterUntilItsLatestEntryAgedOut() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);
		final ChainId chainId = rig.fleet.chain();

		caller.open();
		rig.connector.connect();
		rig.drive();
		rig.fleet.clock.addAndGet(20_000);
		rig.connector.requests.get(0).listener().received(new byte[] { 1 });
		rig.drive();
		rig.fleet.clock.addAndGet(-15_000);
		rig.connector.requests.get(0).listener().closed();
		rig.exit.tick();
		assertThat(rig.exit.heldRelays() + rig.exit.drainingRelays(), is(0));

		rig.fleet.clock.set(ChainFleet.NOW + 40_000);
		rig.exit.tick();
		assertThat(rig.exit.writersOn(chainId, rig.fleet.clock.get()), is(1L));

		rig.fleet.clock.set(ChainFleet.NOW + 51_000);
		rig.exit.tick();
		assertThat(rig.exit.writersOn(chainId, rig.fleet.clock.get()), is(0L));
	}

	/* The writer of a session that ended posts nothing more, so the pause a member put on it goes with its relay and
	   the pauses of writers that are gone do not crowd those of the live ones */
	@Example
	public void liftsThePauseOfTheWriterOfARelayThatWent() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		refuseEverythingAt(rig);
		caller.open();

		final WriterId writer = rig.answers(rig.fleet.chain()).get(0).header().writer();

		assertThat(rig.fleet.host(EXIT).pauses().isPaused(rig.fleet.chain(), writer), is(true));
		caller.session().close(CloseReason.DONE);
		rig.drive();
		rig.exit.tick();

		assertThat(rig.exit.heldRelays(), is(0));
		assertThat(rig.fleet.host(EXIT).pauses().isPaused(rig.fleet.chain(), writer), is(false));
	}

	/* A member that refuses every entry of the exit keeps the writers of its sessions paused for good, which says
	   nothing about what their outboxes hold: a relay that ended with nothing waiting goes at the tick, so its place
	   is free for the next opening */
	@Example
	public void freesThePlaceOfAnEndedRelayWhileAMemberKeepsItsWriterPaused() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		refuseEverythingAt(rig);
		caller.open();
		IntStream.range(1, ChainExit.RELAYS_PER_WRITER).forEach(session -> caller.another());
		assertThat(rig.exit.openRelays(), is(ChainExit.RELAYS_PER_WRITER));

		caller.sessions.forEach(session -> session.close(CloseReason.DONE));
		rig.drive();
		rig.exit.tick();

		assertThat(rig.exit.heldRelays(), is(0));
		assertThat(rig.exit.drainingRelays(), is(0));

		caller.another();
		assertThat(rig.exit.openRelays(), is(1));
	}

	/* The entries of this exit's own writers are no openings, those of a session that ended too: a chain the host
	   rebuilt takes them in again from a member, and none of them gets as far as the trial of an opening, which
	   reads the settings first */
	@Example
	public void triesNoEntryOfItsOwnWritersAsAnOpening() {
		final AtomicInteger reads = new AtomicInteger();
		final ChainFleet fleet = ChainFleet.group();
		final Rig rig = new Rig(fleet, built -> built.exit(() -> {
			reads.incrementAndGet();
			return fleet.settings.get();
		}, fleet.clock::get, Runnable::run));

		rig.caller(1).open();
		rig.connector.connect();
		rig.drive();

		final WriterId writer = rig.answers(rig.fleet.chain()).get(0).header().writer();
		final List<Entry> own = rig.entries(rig.fleet.chain()).stream().filter(entry -> entry.header().writer()
			.equals(writer)).toList();

		rig.fleet.settings.set(Optional.of(ChainFleet.settings(ChainFleet.MEMBERS, 4, 16, 31, 3)));
		rig.fleet.host(EXIT).refresh();
		rig.exit.tick();
		reads.set(0);
		own.forEach(rig::offer);

		assertThat(own, hasSize(2));
		assertThat(rig.entries(rig.fleet.chain()).containsAll(own), is(true));
		assertThat(reads.get(), is(0));
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

	/* The Connects of the callers never reach the exit. Another writer's copy of a Connect, under the tag the relay
	   looks out for, is nothing its client said. */
	@Example
	public void closesARelayWhoseClientSentNoConnectWithinTheArrivalInterval() {
		final Rig rig = new Rig();
		final ChainId chain = rig.fleet.chain();
		final Caller early = rig.caller(1);
		final Caller late = rig.caller(2);

		early.session().open();

		final Tag answerTag = ChainFleet.answerTagOf(early.posted.get(0), EXIT);

		early.send();
		rig.fleet.clock.incrementAndGet();
		late.session().open();
		late.send();
		early.session().receive(rig.entries(chain).stream().filter(entry -> entry.header().tag().equals(answerTag))
			.findFirst().orElseThrow());

		final Entry connect = early.posted.remove(0);
		final Entry copy = rig.stranger(9, chain).write(rig.fleet.clock.get(), connect.header().tag(),
			padded(connect.payload()));

		rig.offer(copy);
		assertThat(rig.entries(chain), hasItem(copy));
		assertThat(early.posted, is(empty()));

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

	/* The stamp of an opening is its writer's word, so the interval cannot run from it */
	@Example
	public void countsTheArrivalIntervalFromTheAnswerAndNotFromTheStampOfTheOpen() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.session().open();
		rig.fleet.clock.addAndGet(ChainExit.OPENING_MAX_AGE_MILLIS);
		caller.send();
		assertThat(rig.exit.heldRelays(), is(1));

		rig.fleet.clock.addAndGet(ChainExit.CONNECT_ARRIVAL_MILLIS - 1);
		rig.exit.tick();
		assertThat(rig.exit.openRelays(), is(1));

		rig.fleet.clock.incrementAndGet();
		rig.exit.tick();
		assertThat(rig.exit.openRelays(), is(0));
	}

	/* An entry of the client under a later tag than the next means the messages before it were lost. The host hands
	   over a feed in order, so the exit is told of it directly. */
	@Example
	public void endsTheRelayWithTheSessionWhenAnEntryOfItsClientIsPastTheNext() {
		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		rig.drive();
		caller.session().send(new Data(new byte[] { 1 }));
		caller.session().send(new Data(new byte[] { 2 }));

		final Entry second = caller.posted.get(1);

		caller.posted.clear();
		rig.exit.appended(rig.fleet.chain(), second);

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

	/* The exit at the share of the client's writer stays silent, as it would for an opening sealed to another member,
	   and the client moves on to its next candidate once the answer is overdue */
	@Example
	public void anExitAtItsLimitsStaysSilent() {
		final Rig rig = new Rig();
		final ChainId chain = rig.fleet.chain();
		final List<String> order = List.of(EXIT, "gn-2");
		final Caller caller = rig.caller(1, excluded -> order.stream().filter(id -> !excluded.contains(id))
			.findFirst().map(ChainExitTest::candidate), chain, CONNECT);

		IntStream.range(0, ChainExit.RELAYS_PER_WRITER).forEach(number -> rig.offer(opening(caller.context.writer(),
			rig.fleet.clock.get(), EXIT)));
		assertThat(rig.exit.heldRelays(), is(ChainExit.RELAYS_PER_WRITER));

		caller.open();

		final Entry silent = rig.openings(chain).get(ChainExit.RELAYS_PER_WRITER);

		assertThat(rig.answers(chain, silent), is(0L));
		assertThat(rig.exit.heldRelays(), is(ChainExit.RELAYS_PER_WRITER));
		assertThat(caller.session().state(), is(SessionState.OPENING));
		assertThat(caller.session().exit(), equalTo(Optional.of(EXIT)));

		rig.fleet.clock.addAndGet(ClientSession.ACCEPT_TIMEOUT_MILLIS - 1);
		assertThat(caller.session().tick(), equalTo(Optional.empty()));
		assertThat(caller.posted, is(empty()));

		rig.fleet.clock.incrementAndGet();
		assertThat(caller.session().tick(), equalTo(Optional.empty()));
		assertThat(caller.session().exit(), equalTo(Optional.of("gn-2")));
		assertThat(caller.posted, hasSize(1));
		assertThat(ChainFleet.openerOf(caller.posted.get(0), order), equalTo(Optional.of("gn-2")));
	}

	/* A chain that gives an opening again, as a rebuilt one that fetches the feeds of its writers anew does, gets no
	   second answer: that would hold a relay for nobody, and put the same answer tag on the chain twice */
	@Example
	public void answersAnOpeningOnceAfterItsRelayIsGone() {
		final Rig rig = new Rig();
		final ChainId chain = rig.fleet.chain();
		final Entry opening = opening(rig.stranger(1, chain), rig.fleet.clock.get(), EXIT);

		rig.offer(opening);
		assertThat(rig.answers(chain, opening), is(1L));

		rig.exit.closeChain(chain, CloseReason.DONE);
		rig.exit.tick();
		assertThat(rig.exit.heldRelays(), is(0));

		rig.exit.appended(chain, opening);
		assertThat(rig.answers(chain, opening), is(1L));
		assertThat(rig.exit.heldRelays(), is(0));
	}

	@Example
	public void answersAnOpeningOnceAfterTheHostRebuiltItsChain() {
		final Rig rig = new Rig();
		final ChainId chain = rig.fleet.chain();
		final Entry opening = opening(rig.stranger(1, chain), rig.fleet.clock.get(), EXIT);

		rig.offer(opening);
		rig.fleet.settings.set(Optional.of(ChainFleet.settings(ChainFleet.MEMBERS, 4, 16, 31, 3)));
		rig.fleet.host(EXIT).refresh();
		rig.exit.tick();
		assertThat(rig.exit.heldRelays(), is(0));
		assertThat(rig.entries(chain), is(empty()));

		rig.offer(opening);
		assertThat(rig.openings(chain), hasSize(1));
		assertThat(rig.answers(chain, opening), is(0L));
		assertThat(rig.exit.heldRelays(), is(0));
	}

	/* A client gives an opening up after its timeout, so one stamped longer ago than that, and the skew between the
	   clocks, is answered for nobody */
	@Example
	public void answersNoOpeningStampedLongerAgoThanAClientWaits() {
		final Rig rig = new Rig();
		final ChainId chain = rig.fleet.chain();
		final long now = rig.fleet.clock.get();
		final Entry stale = opening(rig.stranger(1, chain), now - ChainExit.OPENING_MAX_AGE_MILLIS - 1, EXIT);
		final Entry fresh = opening(rig.stranger(2, chain), now - ChainExit.OPENING_MAX_AGE_MILLIS, EXIT);

		rig.offer(stale);
		rig.offer(fresh);
		assertThat(rig.answers(chain, stale), is(0L));
		assertThat(rig.answers(chain, fresh), is(1L));
		assertThat(ChainExit.OPENING_MAX_AGE_MILLIS, greaterThanOrEqualTo(ClientSession.ACCEPT_TIMEOUT_MILLIS));
	}

	/* An opening stamped further ahead of the exit's clock than the clocks may differ would stay young after the
	   record of the answered openings let go of it */
	@Example
	public void answersNoOpeningStampedFurtherAheadThanTheClocksMayDiffer() {
		final Rig rig = new Rig();
		final ChainId chain = rig.fleet.chain();
		final long now = rig.fleet.clock.get();
		final Entry ahead = opening(rig.stranger(1, chain), now + ChainExit.CLOCK_SKEW_MILLIS + 1000, EXIT);
		final Entry edge = opening(rig.stranger(2, chain), now + ChainExit.CLOCK_SKEW_MILLIS, EXIT);

		rig.offer(ahead);
		rig.offer(edge);
		assertThat(rig.answers(chain, ahead), is(0L));
		assertThat(rig.answers(chain, edge), is(1L));
	}

	/* An opening stamped ahead of the exit's clock stays young that much longer, so the record of the answered ones
	   keeps it as long, however short the retention of its chain */
	@Example
	public void answersAnOpeningStampedAheadOnceWhileItIsYoung() {
		final Rig rig = new Rig(ChainFleet.group(ChainFleet.settings(ChainFleet.MEMBERS, 4, 16, 20, 3)));
		final ChainId chain = rig.fleet.chain();
		final Entry opening = opening(rig.stranger(1, chain), rig.fleet.clock.get() + ChainExit.CLOCK_SKEW_MILLIS,
			EXIT);

		rig.offer(opening);
		assertThat(rig.exit.heldRelays(), is(1));

		rig.fleet.clock.addAndGet(21_000);
		rig.exit.appended(chain, opening);
		assertThat(rig.exit.heldRelays(), is(1));
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

	/* Only an entry of the session's own client under the tag of its next message is opened by the session */
	@Property(tries = 40)
	public void leavesASessionWholeWhateverElseTheChainCarries(@ForAll @Size(max = 96) byte[] payload,
		@ForAll boolean ofItsClient, @ForAll boolean underItsOpeningsTag) {

		final Rig rig = new Rig();
		final Caller caller = rig.caller(1);

		caller.session().open();

		final Tag opening = caller.posted.get(0).header().tag();

		rig.drive();

		final FakeConnector.FakeTarget target = rig.connector.connect();
		final Tag tag = underItsOpeningsTag ? opening : Tag.random(RANDOM);
		final Entry entry = ofItsClient ? caller.write(tag, padded(payload))
			: rig.stranger(9, rig.fleet.chain()).write(rig.fleet.clock.get(), tag, padded(payload));

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
		/* The opening, the answer, the Connect and the Connected fill the chain */
		final Rig rig = new Rig(ChainFleet.group(ChainFleet.smallChains(4)));
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

	/* Openings, answers and short messages fill the smallest class, for which a chain full of larger entries keeps a
	   reserve: a sixteenth of these chains, room for the opening, the answer, the Connect and the Connected */
	@Example
	public void opensASessionOnAChainFullOfLargerEntries() {
		final int largest = 16 * 1024;
		final Rig rig = new Rig(ChainFleet.group(new ProxySettings(ChainFleet.MEMBERS, 3600, 4, 16, 4L * largest, 30,
			largest, 300, 3)));
		final FeedWriter stranger = rig.stranger(9, rig.fleet.chain());

		IntStream.range(0, 4).forEach(number -> rig.offer(stranger.write(rig.fleet.clock.get(), ChainFleet.TAG,
			ChainFleet.padded(new byte[largest - Entry.OVERHEAD], largest))));
		assertThat(rig.fleet.host(EXIT).chain(rig.fleet.chain()).orElseThrow().bytes(), is(4L * largest));

		final Caller caller = rig.caller(1);

		caller.open();
		rig.connector.connect();
		rig.drive();

		assertThat(caller.received, contains(CONNECTED));
		assertThat(rig.entries(rig.fleet.chain()), hasSize(8));
	}

	/* A member had no room for an entry of one session's writer, which pauses that writer alone: a session that
	   opens meanwhile writes as a writer of its own and reads its destination at once */
	@Example
	public void startsARelayUnpausedWhileAMemberRefusedTheWriterOfAnotherSession() {
		final Rig rig = new Rig();

		rig.caller(1).open();
		rig.fleet.host(EXIT).refused(rig.stream, EntryRefused.of(rig.answers(rig.fleet.chain())
			.get(0), Admission.FULL));
		rig.caller(2).open();

		final FakeConnector.FakeTarget target = rig.connector.connect();

		assertThat(target.readable, is(true));
	}

	/* A session that opens on the chain the host rebuilt writes as a writer of its own, and the tick ends the sessions
	   on the old chain */
	@Example
	public void startsAFeedOfItsOwnOnAChainTheHostRebuiltAndEndsTheSessionsOfTheOldOne() {
		final Rig rig = new Rig();

		rig.caller(1).open();

		final FakeConnector.FakeTarget old = rig.connector.connect();
		final WriterId feed = rig.answers(rig.fleet.chain()).get(0).header().writer();

		rig.fleet.settings.set(Optional.of(ChainFleet.settings(ChainFleet.MEMBERS, 4, 16, 31, 3)));
		rig.fleet.host(EXIT).refresh();

		final Caller caller = rig.caller(2);

		caller.open();
		rig.connector.connect();
		rig.drive();

		assertThat(caller.received, contains(CONNECTED));
		assertThat(rig.answers(rig.fleet.chain()).get(0).header().writer(), not(feed));

		rig.exit.tick();
		assertThat(old.closed, is(true));
		assertThat(rig.exit.heldRelays(), is(1));
	}

	/* What the destination sent waited for room longer than the chain keeps entries, so the chain will never take it:
	   the tick ends the session of that feed as it does those of a chain the host dropped, and the next OPEN gets a
	   feed of its own */
	@Example
	public void endsTheSessionsOfAFeedWhoseEntryWaitedTooLongAndStartsAFeedOfItsOwn() {
		/* The opening, the answer, the Connect and the Connected fill the chain */
		final Rig rig = new Rig(ChainFleet.group(ChainFleet.smallChains(4)));

		rig.caller(1).open();

		final FakeConnector.FakeTarget old = rig.connector.connect();
		final WriterId feed = rig.answers(rig.fleet.chain()).get(0).header().writer();

		rig.drive();
		rig.fleet.clock.addAndGet(15_000);
		rig.connector.requests.get(0).listener().received(new byte[300]);
		rig.exit.tick();
		rig.fleet.clock.addAndGet(31_000);
		rig.fleet.host(EXIT).maintain();
		rig.exit.tick();
		rig.exit.tick();

		assertThat(old.closed, is(true));
		assertThat(rig.exit.heldRelays(), is(0));

		final Caller caller = rig.caller(2);

		caller.open();
		rig.connector.connect();
		rig.drive();

		assertThat(caller.received, contains(CONNECTED));
		assertThat(rig.answers(rig.fleet.chain()).stream().map(accept -> accept.header().writer())
			.toList(), everyItem(not(feed)));
	}

	/* A session's writer holds the latest time it stamped, so once the clock stepped back further than the chain keeps
	   entries the chain takes nothing more of that feed and the tick ends the session. A session that opens meanwhile
	   writes as a writer of its own. */
	@Example
	public void endsTheSessionWhoseWriterTheClockSteppedBackFurtherThanTheChainKeepsEntries() {
		final Rig rig = new Rig();
		final Caller first = rig.caller(1);

		first.open();

		final FakeConnector.FakeTarget old = rig.connector.connect();
		final TargetListener destination = rig.connector.requests.get(0).listener();
		final WriterId feed = rig.answers(rig.fleet.chain()).get(0).header().writer();

		rig.drive();
		rig.fleet.clock.addAndGet(40_000);
		destination.received(new byte[] { 1 });
		rig.drive();
		rig.fleet.clock.addAndGet(-31_000);
		destination.received(new byte[] { 2 });

		final Caller caller = rig.caller(2);

		caller.open();
		rig.connector.connect();
		rig.drive();

		assertThat(first.received, contains(CONNECTED, new Data(new byte[] { 1 })));
		assertThat(caller.received, contains(CONNECTED));
		assertThat(rig.answers(rig.fleet.chain()).stream().map(accept -> accept.header().writer())
			.toList(), everyItem(not(feed)));

		rig.exit.tick();
		assertThat(old.closed, is(true));
		assertThat(rig.exit.heldRelays(), is(1));
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
			ChainFleet.sealingKey(EXIT)));
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
		rig.exit.gapped(rig.fleet.chain(), new Gap(caller.writer(), 0, 1, Set.of(caller.nextTag())));
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
			exit = new ChainExit(host, fleet.settings::get, ChainFleet.sealingKey(EXIT), fleet.clock::get,
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

		private long answers(final ChainId chainId, final Entry opening) {
			final Tag tag = ChainFleet.answerTagOf(opening, EXIT);

			return host.chain(chainId).orElseThrow().entries().stream()
				.filter(entry -> entry.header().tag().equals(tag)).count();
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
			final Queue<Entry> posted = new ConcurrentLinkedQueue<>();
			final List<ClientSession> sessions = IntStream.range(0, 4).mapToObj(number -> new ClientSession(
				new SessionContext(new FeedWriter(ChainFleet.key(300 + number), chain), posted::add,
				node.fleet.clock::get, new SecureRandom(), ProxySettings.DEFAULTS),
				excluded -> Optional.of(candidate(EXIT)), CONNECT)).toList();
			/* The replies of the destinations are posted from the other threads while this one hands entries over, so
			   an entry is taken off the queue as it is offered and none can be cleared unoffered */
			final Runnable post = () -> {
				for (Entry entry = posted.poll(); entry != null; entry = posted.poll()) {
					node.offer(entry);
				}
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

	/* Every chain drains on the thread that hands it entries, so openings of one writer on four chains reach the exit
	   at once */
	@Example
	public void keepsTheShareOfAWriterWhenItsOpensArriveOnSeveralChainsAtOnce() throws Exception {
		try (Threaded node = new Threaded()) {
			final List<FeedWriter> feeds = IntStream.range(0, 4)
				.mapToObj(chain -> new FeedWriter(ChainFleet.key(1), node.chain(chain))).toList();

			IntStream.range(0, 62)
				.forEach(open -> node.offer(opening(feeds.get(0), ChainFleet.NOW, EXIT)));
			node.atOnce(feeds.stream().map(feed -> opening(feed, ChainFleet.NOW, EXIT))
				.<Runnable>map(open -> () -> node.offer(open)).toList());
			node.awaitWork();

			assertThat(node.exit.heldRelays(), is(64));
		}
	}

	/* A chain the host rebuilt drains on two threads while the old one is swapped out, so the same opening may be on
	   its way to an answer twice before either has one */
	@Example
	public void answersAnOpeningOnceWhenItArrivesTwiceAtOnce() throws Exception {
		try (Threaded node = new Threaded()) {
			final ChainId chain = node.chain(0);
			final Entry opening = opening(new FeedWriter(ChainFleet.key(1), chain), ChainFleet.NOW, EXIT);

			node.atOnce(List.of(() -> node.exit.appended(chain, opening), () -> node.exit.appended(chain, opening)));
			node.awaitWork();

			assertThat(node.answers(chain, opening), is(1L));
			assertThat(node.exit.heldRelays(), is(1));
		}
	}
}
