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

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.Close;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.ClientSession;
import org.unigrid.hedgehog.proxy.session.Connect;
import org.unigrid.hedgehog.proxy.session.Connected;
import org.unigrid.hedgehog.proxy.session.Data;
import org.unigrid.hedgehog.proxy.session.SealedCodec;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.Session;
import org.unigrid.hedgehog.proxy.session.SealingCipher;
import org.unigrid.hedgehog.proxy.session.SessionContext;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class ClientSessionsTest {
	private static final String CLIENT = "client";
	private static final long EPOCH_MILLIS = 3_600_000;
	private static final long RETENTION_MILLIS = 30_000;
	private static final long IDLE_MILLIS = ProxySettings.DEFAULTS.sessionIdleMillis();
	private static final int TIMEOUT_SECONDS = 10;
	/* The opening, the answer and the Connect, each an entry of the smallest class */
	private static final int HANDSHAKE_ENTRIES = 3;
	private static final Connect CONNECT = new Connect(Socks5CommandType.CONNECT,
		new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 443));
	private static final Connected CONNECTED = new Connected(Socks5CommandStatus.SUCCESS,
		new SocksAddress(Socks5AddressType.IPv4, "192.0.2.10", 40_000));

	private enum Event { EXIT_SENDS, CLIENT_SENDS, EXIT_CLOSES, IDLES, LOSES_AN_ENTRY, OPENER_CLOSES }

	private static class Listener implements SessionListener {
		private final List<SealedMessage> messages = new CopyOnWriteArrayList<>();
		private final List<Boolean> writability = new CopyOnWriteArrayList<>();

		@Override
		public void received(final SealedMessage message) {
			messages.add(message);
		}

		@Override
		public void writabilityChanged(final boolean writable) {
			writability.add(writable);
		}
	}

	/* A client node over a fleet whose members each hold an exit stub */
	private static final class Rig {
		private final ChainFleet fleet;
		private final List<ExitStub> exits;
		private final ManualScheduler scheduler = new ManualScheduler();
		private final ChainClient client;
		private final ClientSessions sessions;
		private int strangers;

		private Rig(final ChainFleet fleet) {
			this(fleet, fleet.clock::get, fleet.settings::get, new SecureRandom());
		}

		/* The sessions read the time, the settings and their fingerprints from sources of their own, which a test
		   may make fail or fix */
		private Rig(final ChainFleet fleet, final LongSupplier clock, final ProxySettingsSource settings,
			final SecureRandom random) {

			this(fleet, fleet.settings::get, clock, settings, random);
		}

		/* The client reads the settings through a source of its own as well, which a test may watch */
		private Rig(final ChainFleet fleet, final ProxySettingsSource clientSettings, final LongSupplier clock,
			final ProxySettingsSource settings, final SecureRandom random) {

			this.fleet = fleet;
			exits = IntStream.range(0, ChainFleet.MEMBERS).mapToObj(number -> new ExitStub(fleet, number))
				.toList();
			client = new ChainClient(clientSettings, fleet.directory(CLIENT), fleet.transport(CLIENT),
				fleet.host(CLIENT), fleet.pending(CLIENT), fleet.clock::get, new SecureRandom(),
				Runnable::run);
			sessions = new ClientSessions(client, fleet.host(CLIENT), settings, clock, random, scheduler);
		}

		private ExitStub answering() {
			return exits.stream().filter(exit -> !exit.sessions.isEmpty()).findFirst().orElseThrow();
		}

		private List<Session> exitSessions() {
			return exits.stream().flatMap(exit -> exit.sessions.stream()).toList();
		}

		private List<SealedMessage> exitReceived() {
			return exits.stream().flatMap(exit -> exit.received.stream()).toList();
		}

		private void mute(final boolean muted) {
			exits.forEach(exit -> exit.muted = muted);
		}

		private ChainAttachment attachment() {
			return client.attachment().orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
		}

		private boolean follows(final ChainAttachment attachment) {
			return fleet.host(CLIENT).chain(attachment.chainId()).isPresent();
		}

		private long sent(final Class<?> packet) {
			return fleet.deliveries.stream().filter(delivery -> delivery.from().equals(CLIENT))
				.filter(delivery -> packet.isInstance(delivery.packet())).count();
		}

		/* The openings on the chain: the entries that the sealing key of a member opens */
		private List<Entry> opens() {
			return fleet.host(CLIENT).chain(attachment().chainId()).orElseThrow().entries().stream()
				.filter(entry -> opener(entry).isPresent()).toList();
		}

		private static String exitOf(final Entry open) {
			return opener(open).orElseThrow();
		}

		private static Optional<String> opener(final Entry entry) {
			return ChainFleet.openerOf(entry, IntStream.range(0, ChainFleet.MEMBERS).mapToObj(number -> "gn-" + number)
				.toList());
		}

		private static byte[] sealingKeyOf(final String member) {
			return ChainFleet.sealingKey(member).privateKey().orElseThrow();
		}

		private void maintainAll() {
			Stream.concat(fleet.activeIds().stream(), Stream.of(CLIENT))
				.forEach(id -> fleet.host(id).maintain());
		}

		private void after(final long millis) {
			fleet.clock.addAndGet(millis);
			sessions.tick();
			fleet.deliver();
		}

		/* The exit's feed loses an entry of the session, and the chain gives up on it once the entry after it, which
		   belongs to the session too, has waited for the retention window */
		private void lose(final Session exit) {
			final ChainAttachment attachment = attachment();

			if (exit.isOpen()) {
				final Entry later = answering().skipOne(exit);

				fleet.host(CLIENT).receive(attachment.streams().get(0), ChainEntry.builder().entries(List.of(later))
					.build());
			}

			fleet.clock.addAndGet(RETENTION_MILLIS + 1000);
			fleet.host(CLIENT).maintain();
		}

		/* A writer the client has not heard of writes three entries under the tag and the middle one never arrives,
		   until the client's chain gives up on it */
		private void strangerLoses(final Fingerprint tag) {
			final ChainAttachment attachment = attachment();
			final FeedWriter stranger = new FeedWriter(ChainFleet.key(200 + strangers++), attachment.chainId());
			final List<Entry> written = Stream.generate(() -> stranger.write(fleet.clock.get(), tag,
				EntryKind.SEALED, new byte[1])).limit(3).toList();

			fleet.host(CLIENT).receive(attachment.streams().get(0), ChainEntry.builder()
				.entries(List.of(written.get(0), written.get(2))).build());
			fleet.clock.addAndGet(RETENTION_MILLIS + 1000);
			fleet.host(CLIENT).maintain();
		}
	}

	private static Data data(final int number) {
		return new Data(new byte[] { (byte) number });
	}

	@SneakyThrows
	private static void within(final Future<?> future) {
		future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	/* Fingerprints and keys drawn from the seed, so that a try that fails comes back the same */
	@SneakyThrows
	private static SecureRandom seeded(final long seed) {
		final SecureRandom random = SecureRandom.getInstance("SHA1PRNG");

		random.setSeed(seed);
		return random;
	}

	@Example
	public void carriesTheMessagesOfASessionBothWays() {
		final Rig rig = new Rig(ChainFleet.group());
		final Listener listener = new Listener();
		final SessionHandle handle = rig.sessions.open(CONNECT, listener);

		rig.fleet.deliver();

		final ExitStub exit = rig.answering();

		assertThat(exit.received, equalTo(List.of(CONNECT)));
		assertThat(handle.isWritable(), is(true));
		assertThat(handle.maxDataBytes(), is(16 * 1024 - Entry.OVERHEAD - SealingCipher.OVERHEAD
			- SealedCodec.DATA_OVERHEAD));

		exit.session().send(CONNECTED);
		handle.send(new Data(new byte[] { 1, 2, 3 }));
		exit.session().send(new Data(new byte[] { 4, 5 }));
		rig.fleet.deliver();

		assertThat(listener.messages, equalTo(List.of(CONNECTED, new Data(new byte[] { 4, 5 }))));
		assertThat(exit.received, equalTo(List.of(CONNECT, new Data(new byte[] { 1, 2, 3 }))));
		assertThat(rig.scheduler.periodMillis, is(1000L));

		handle.send(new Data(new byte[handle.maxDataBytes()]));
		rig.fleet.deliver();

		assertThat(exit.received.get(2), equalTo(new Data(new byte[handle.maxDataBytes()])));
		assertThrows(IllegalArgumentException.class, () -> handle.send(new Data(
			new byte[handle.maxDataBytes() + 1])));
	}

	/* Every session on the attachment is handed every entry, and each takes its own */
	@Example
	public void carriesTheMessagesOfEverySessionOfAnAttachmentToItsOwnEnd() {
		final Rig rig = new Rig(ChainFleet.group());
		final List<Listener> listeners = Stream.generate(Listener::new).limit(3).toList();
		final List<SessionHandle> handles = listeners.stream().map(listener -> rig.sessions.open(CONNECT, listener))
			.toList();

		rig.fleet.deliver();
		IntStream.range(0, 3).forEach(number -> rig.exitSessions().get(number).send(data(number)));
		IntStream.range(0, 3).forEach(number -> handles.get(number).send(data(10 + number)));
		rig.fleet.deliver();

		assertThat(listeners.stream().map(listener -> listener.messages).toList(), everyItem(hasSize(1)));
		assertThat(listeners.stream().flatMap(listener -> listener.messages.stream()).toList(),
			containsInAnyOrder(data(0), data(1), data(2)));
		assertThat(rig.exitReceived(), containsInAnyOrder(CONNECT, CONNECT, CONNECT, data(10), data(11), data(12)));
		assertThat(rig.opens().stream().map(open -> open.header().writer()).distinct().toList(),
			equalTo(List.of(rig.attachment().writerId())));
		assertThat(rig.sent(JoinChain.class), is(2L));
		assertThat(rig.scheduler.schedules, is(1));
	}

	/* The members the client attaches through see its address, so none of them is ever its exit; the others all are
	   in time */
	@Example
	public void exitIsNeverAnAttachMember() {
		final Rig rig = new Rig(ChainFleet.group());

		IntStream.range(0, 24).forEach(number -> rig.sessions.open(CONNECT, new Listener()));
		rig.fleet.deliver();

		final ChainAttachment attachment = rig.attachment();
		final List<String> attachMembers = rig.fleet.attachMembers(CLIENT, attachment);

		assertThat(attachMembers, hasSize(2));
		assertThat(rig.opens(), hasSize(24));
		assertThat(rig.opens().stream().map(Rig::exitOf).collect(Collectors.toSet()), equalTo(attachment.members()
			.stream().filter(member -> !attachMembers.contains(member)).collect(Collectors.toSet())));
	}

	@Example
	public void triesAnotherExitWithANewOpeningWhenNoneAnswers() {
		final Rig rig = new Rig(ChainFleet.group());

		rig.mute(true);
		rig.sessions.open(CONNECT, new Listener());
		rig.fleet.deliver();
		rig.mute(false);
		rig.after(ClientSession.ACCEPT_TIMEOUT_MILLIS - 1);
		assertThat(rig.opens().size(), is(1));

		rig.after(1);

		final List<Entry> opens = rig.opens();

		assertThat(opens.size(), is(2));
		assertThat(opens.get(0).header().fingerprint(), not(equalTo(opens.get(1).header().fingerprint())));
		assertThat(Rig.exitOf(opens.get(0)), not(equalTo(Rig.exitOf(opens.get(1)))));
		assertThat(rig.answering().received, equalTo(List.of(CONNECT)));
	}

	/* The clock fails for the session ticked first, which keeps neither the other session from its retry nor the
	   next tick from running */
	@Example
	public void keepsTickingAfterATickFailed() {
		final ChainFleet fleet = ChainFleet.group();
		final AtomicInteger failures = new AtomicInteger();
		final Rig rig = new Rig(fleet, () -> {
			if (failures.getAndDecrement() > 0) {
				throw new IllegalStateException("The clock failed");
			}

			return fleet.clock.get();
		}, fleet.settings::get, new SecureRandom());

		rig.mute(true);
		rig.sessions.open(CONNECT, new Listener());
		rig.sessions.open(CONNECT, new Listener());
		fleet.deliver();
		fleet.clock.addAndGet(ClientSession.ACCEPT_TIMEOUT_MILLIS);

		failures.set(1);
		rig.scheduler.scheduled.run();
		assertThat(rig.opens().size(), is(3));

		rig.scheduler.scheduled.run();
		assertThat(rig.opens().size(), is(4));
	}

	/* The handle takes nothing to send before an exit answered, and nothing once the session ended. Every attempt
	   goes to an exit that was not tried yet, until the attempts or the members other than the attach ones run out. */
	@Property(tries = 10)
	public void tellsTheListenerOnceWhenNoExitAnsweredInTheEnd(@ForAll final long seed) {
		final ChainFleet fleet = ChainFleet.group();
		final Rig rig = new Rig(fleet, fleet.clock::get, fleet.settings::get, seeded(seed));
		final Listener listener = new Listener();
		final SessionHandle handle = rig.sessions.open(CONNECT, listener);

		assertThat(handle.maxDataBytes(), is(0));
		assertThat(handle.isWritable(), is(false));
		assertThrows(IllegalStateException.class, () -> handle.send(data(1)));

		rig.mute(true);
		rig.fleet.deliver();
		assertThrows(IllegalStateException.class, () -> handle.send(data(1)));

		for (int attempt = 0; attempt <= ClientSession.MAX_ATTEMPTS; attempt++) {
			rig.after(ClientSession.ACCEPT_TIMEOUT_MILLIS);
		}

		final int candidates = ChainFleet.MEMBERS - rig.fleet.attachMembers(CLIENT, rig.attachment()).size();

		assertThat(listener.messages, equalTo(List.of(new Close(CloseReason.UNREACHABLE))));
		assertThat(rig.opens(), hasSize(Math.min(ClientSession.MAX_ATTEMPTS, candidates)));
		assertThat(rig.opens().stream().map(Rig::exitOf).distinct().count(), is((long) rig.opens().size()));
		assertThrows(IllegalStateException.class, () -> handle.send(data(1)));
	}

	@Example
	public void tellsTheListenerWhenTheChainCannotBeJoined() {
		final ChainFleet fleet = ChainFleet.group();
		final Rig rig = new Rig(fleet);
		final Listener listener = new Listener();

		fleet.settings.set(Optional.empty());
		rig.sessions.open(CONNECT, listener);

		assertThat(listener.messages, equalTo(List.of(new Close(CloseReason.UNREACHABLE))));
	}

	/* The opener knows that it closed, so a join that fails afterwards is no news to it */
	@Example
	public void tellsNothingOfAJoinThatFailedAfterTheOpenerClosed() {
		final Rig rig = new Rig(ChainFleet.group());
		final Listener listener = new Listener();

		rig.sessions.open(CONNECT, listener).close(CloseReason.DONE);
		rig.fleet.settings.set(Optional.empty());
		rig.fleet.deliver();

		assertThat(listener.messages, equalTo(List.of()));
	}

	@Example
	public void startsNoSessionOnceTheSchedulerIsShutDown() {
		final Rig rig = new Rig(ChainFleet.group());
		final Listener listener = new Listener();

		rig.scheduler.shutdown();
		rig.sessions.open(CONNECT, listener);
		rig.fleet.deliver();

		assertThat(listener.messages, equalTo(List.of(new Close(CloseReason.UNREACHABLE))));
		assertThat(rig.sent(JoinChain.class), is(0L));
		assertThat(rig.scheduler.schedules, is(0));
	}

	@Example
	public void closesASessionThatHasBeenIdleForTooLong() {
		final Rig rig = new Rig(ChainFleet.group());
		final Listener listener = new Listener();

		rig.sessions.open(CONNECT, listener);
		rig.fleet.deliver();
		rig.after(IDLE_MILLIS - 1);
		assertThat(listener.messages, equalTo(List.of()));

		rig.after(1);
		rig.after(IDLE_MILLIS);

		assertThat(listener.messages, equalTo(List.of(new Close(CloseReason.IDLE))));
		assertThat(rig.answering().received, equalTo(List.of(CONNECT, new Close(CloseReason.IDLE))));
	}

	@Example
	public void tellsTheExitWhenItsOpenerClosesTheSession() {
		final Rig rig = new Rig(ChainFleet.group());
		final Listener listener = new Listener();
		final SessionHandle handle = rig.sessions.open(CONNECT, listener);

		rig.fleet.deliver();
		handle.close(CloseReason.DONE);
		handle.close(CloseReason.BROKEN);
		rig.fleet.deliver();

		assertThat(rig.answering().received, equalTo(List.of(CONNECT, new Close(CloseReason.DONE))));
		assertThat(listener.messages, equalTo(List.of()));
		assertThrows(IllegalStateException.class, () -> handle.send(data(1)));
	}

	/* The test plays the exit: it answers, and one of the entries it sends never arrives at the client */
	@Example
	public void closesTheSessionsOfAGapThatTheChainGaveUpOn() {
		final Rig rig = new Rig(ChainFleet.group());
		final Listener listener = new Listener();

		rig.mute(true);
		rig.sessions.open(CONNECT, listener);
		rig.fleet.deliver();

		final ChainAttachment attachment = rig.attachment();
		final Entry open = rig.opens().get(0);
		final List<Entry> posted = new ArrayList<>();
		final Session exit = Session.tryAccept(new SessionContext(new FeedWriter(ChainFleet.key(200),
			attachment.chainId()), posted::add, rig.fleet.clock::get, new SecureRandom(),
			ProxySettings.DEFAULTS), open, Rig.sealingKeyOf(Rig.exitOf(open))).orElseThrow();

		exit.send(CONNECTED);
		exit.send(new Data(new byte[] { 1 }));
		exit.send(new Data(new byte[] { 2 }));
		exit.send(new Data(new byte[] { 3 }));

		final List<Entry> arrived = List.of(posted.get(0), posted.get(1), posted.get(2), posted.get(4));

		rig.fleet.host(CLIENT).receive(attachment.streams().get(0), ChainEntry.builder().entries(arrived).build());
		assertThat(listener.messages, equalTo(List.of(CONNECTED, new Data(new byte[] { 1 }))));

		rig.fleet.clock.addAndGet(RETENTION_MILLIS + 1000);
		rig.fleet.host(CLIENT).maintain();

		assertThat(listener.messages, equalTo(List.of(CONNECTED, new Data(new byte[] { 1 }),
			new Close(CloseReason.BROKEN))));
	}

	/* The exit loses an entry of the second session only, and the chain gives up on it: the first is whole */
	@Example
	public void closesOnlyTheSessionsWhoseEntriesTheGapOfTheirExitDropped() {
		final Rig rig = new Rig(ChainFleet.group());
		final Listener kept = new Listener();
		final Listener lost = new Listener();

		rig.mute(true);
		rig.sessions.open(CONNECT, kept);
		rig.sessions.open(CONNECT, lost);
		rig.fleet.deliver();

		final ChainAttachment attachment = rig.attachment();
		final List<Entry> posted = new ArrayList<>();
		final SessionContext exit = new SessionContext(new FeedWriter(ChainFleet.key(200), attachment.chainId()),
			posted::add, rig.fleet.clock::get, new SecureRandom(), ProxySettings.DEFAULTS);
		final List<Session> sessions = rig.opens().stream()
			.map(open -> Session.tryAccept(exit, open, Rig.sealingKeyOf(Rig.exitOf(open))).orElseThrow()).toList();

		sessions.forEach(session -> session.send(CONNECTED));
		sessions.get(0).send(data(1));
		sessions.get(1).send(data(2));
		sessions.get(1).send(data(3));

		final List<Entry> arrived = Stream.of(0, 1, 2, 3, 4, 6).map(posted::get).toList();

		rig.fleet.host(CLIENT).receive(attachment.streams().get(0), ChainEntry.builder().entries(arrived).build());
		rig.fleet.clock.addAndGet(RETENTION_MILLIS + 1000);
		rig.fleet.host(CLIENT).maintain();

		assertThat(List.of(kept.messages, lost.messages), containsInAnyOrder(List.of(CONNECTED, data(1)),
			List.of(CONNECTED, new Close(CloseReason.BROKEN))));
	}

	/* A gap names a session only by a tag the session looks out for, so the gap of a writer under the tag of an
	   opening breaks nothing, be the session opening, which a retry replaces, or established */
	@Example
	public void leavesASessionAloneForAGapOfAWriterThatIsNotItsExit() {
		final Rig rig = new Rig(ChainFleet.group());
		final Listener listener = new Listener();

		rig.mute(true);
		rig.sessions.open(CONNECT, listener);
		rig.fleet.deliver();
		rig.strangerLoses(rig.opens().get(0).header().fingerprint());
		assertThat(listener.messages, equalTo(List.of()));

		rig.mute(false);
		rig.after(ClientSession.ACCEPT_TIMEOUT_MILLIS);
		rig.strangerLoses(rig.opens().get(0).header().fingerprint());
		rig.fleet.deliver();

		assertThat(listener.messages, equalTo(List.of()));
		assertThat(rig.answering().received, equalTo(List.of(CONNECT)));
	}

	/* Whatever ends a session, the listener hears of it once and last, and of nothing once the opener closed */
	@Property(tries = 25)
	public void tellsTheListenerOfEachEndOnceAndOfNothingAfterTheOpenerClosed(
		@ForAll @Size(max = 8) final List<Event> events) {

		final Rig rig = new Rig(ChainFleet.group());
		final Listener listener = new Listener();
		final SessionHandle handle = rig.sessions.open(CONNECT, listener);
		final List<SealedMessage> told = new ArrayList<>();
		final List<SealedMessage> sent = new ArrayList<>(List.of(CONNECT));
		boolean live = true;

		rig.fleet.deliver();

		final Session exit = rig.answering().session();

		for (int number = 0; number < events.size(); number++) {
			final Outcome outcome = play(rig, handle, exit, events.get(number), number, live);

			rig.fleet.deliver();

			if (live) {
				outcome.told().ifPresent(told::add);
				outcome.sent().ifPresent(sent::add);
				live = !outcome.ends();
			}
		}

		assertThat(listener.messages, equalTo(told));
		assertThat(rig.answering().received, equalTo(sent));
	}

	/* What one event tells the listener and the exit while the session is live, and whether it ends it */
	private record Outcome(Optional<SealedMessage> told, Optional<SealedMessage> sent, boolean ends) {
		private static Outcome told(final SealedMessage message, final boolean ends) {
			return new Outcome(Optional.of(message), Optional.empty(), ends);
		}

		private static Outcome sent(final SealedMessage message, final boolean ends) {
			return new Outcome(Optional.empty(), Optional.of(message), ends);
		}

		private static Outcome both(final Close close) {
			return new Outcome(Optional.of(close), Optional.of(close), true);
		}
	}

	private static Outcome play(final Rig rig, final SessionHandle handle, final Session exit, final Event event,
		final int number, final boolean live) {

		return switch (event) {
			case EXIT_SENDS -> exitSends(exit, data(number), live, false);
			case CLIENT_SENDS -> clientSends(handle, data(number), live);
			case EXIT_CLOSES -> exitSends(exit, new Close(CloseReason.DONE), live, true);
			case IDLES -> {
				rig.after(IDLE_MILLIS);
				yield Outcome.both(new Close(CloseReason.IDLE));
			}
			case LOSES_AN_ENTRY -> {
				rig.lose(exit);
				yield Outcome.both(new Close(CloseReason.BROKEN));
			}
			case OPENER_CLOSES -> {
				handle.close(CloseReason.DONE);
				yield Outcome.sent(new Close(CloseReason.DONE), true);
			}
		};
	}

	private static Outcome exitSends(final Session exit, final SealedMessage message, final boolean live,
		final boolean ends) {

		if (live) {
			exit.send(message);
		}

		return Outcome.told(message, ends);
	}

	private static Outcome clientSends(final SessionHandle handle, final SealedMessage message, final boolean live) {
		if (live) {
			handle.send(message);
		} else {
			assertThrows(IllegalStateException.class, () -> handle.send(message));
		}

		return Outcome.sent(message, false);
	}

	@Example
	public void leavesTheChainOfAnEarlierEpochOnceItsSessionsAreOver() {
		final Rig rig = new Rig(ChainFleet.group());
		final SessionHandle first = rig.sessions.open(CONNECT, new Listener());

		rig.fleet.deliver();

		final ChainAttachment earlier = rig.attachment();
		final Session exit = rig.answering().session();

		rig.fleet.clock.addAndGet(EPOCH_MILLIS);
		rig.fleet.refreshAll();
		rig.sessions.open(CONNECT, new Listener());
		rig.fleet.deliver();

		final ChainAttachment later = rig.attachment();

		assertThat(later.chainId(), not(equalTo(earlier.chainId())));

		exit.send(CONNECTED);
		rig.fleet.deliver();
		rig.after(0);
		assertThat(rig.follows(earlier), is(true));

		first.send(new Close(CloseReason.DONE));
		rig.after(0);

		assertThat(rig.follows(earlier), is(false));
		assertThat(rig.follows(later), is(true));
		assertThat(rig.sent(LeaveChain.class), is((long) earlier.streams().size()));
	}

	/* The way every stream really ends: the exit's Close is told once, and the tick then forgets the handle */
	@Example
	public void leavesTheChainOfAnEarlierEpochOnceTheExitEndedItsSession() {
		final Rig rig = new Rig(ChainFleet.group());
		final Listener listener = new Listener();

		rig.sessions.open(CONNECT, listener);
		rig.fleet.deliver();

		final ChainAttachment earlier = rig.attachment();
		final Session exit = rig.answering().session();

		rig.fleet.clock.addAndGet(EPOCH_MILLIS);
		rig.fleet.refreshAll();
		rig.sessions.open(CONNECT, new Listener());
		rig.fleet.deliver();

		exit.send(CONNECTED);
		exit.send(new Close(CloseReason.DONE));
		rig.fleet.deliver();
		rig.after(0);

		assertThat(listener.messages, equalTo(List.of(CONNECTED, new Close(CloseReason.DONE))));
		assertThat(rig.follows(earlier), is(false));
	}

	@Example
	public void tellsNoWritabilityChangeToASessionTheExitEnded() {
		/* The handshake and Close of the first session and the handshake of the second fill the chain */
		final Rig rig = new Rig(ChainFleet.group(ChainFleet.smallChains(7)));
		final Listener ended = new Listener();
		final Listener running = new Listener();

		rig.sessions.open(CONNECT, ended);
		rig.fleet.deliver();
		rig.answering().session().send(new Close(CloseReason.DONE));
		rig.fleet.deliver();
		rig.after(0);

		final SessionHandle handle = rig.sessions.open(CONNECT, running);

		rig.fleet.deliver();
		rig.fleet.clock.addAndGet(15_000);
		handle.send(new Data(new byte[300]));
		rig.after(1000);

		assertThat(ended.messages, equalTo(List.of(new Close(CloseReason.DONE))));
		assertThat(ended.writability, is(empty()));
		assertThat(running.writability, equalTo(List.of(false)));
	}

	/* A handle closed before its chain was joined runs no session, and the chain is kept for the next one while it
	   is the one in use */
	@Example
	public void keepsAChainThatNoSessionRunsOnWhileItIsTheOneInUse() {
		final Rig rig = new Rig(ChainFleet.group());

		rig.sessions.open(CONNECT, new Listener()).close(CloseReason.DONE);
		rig.fleet.deliver();

		final ChainAttachment attachment = rig.attachment();

		rig.after(0);
		assertThat(rig.opens(), empty());
		assertThat(rig.follows(attachment), is(true));

		rig.fleet.clock.addAndGet(EPOCH_MILLIS);
		rig.after(0);

		assertThat(rig.follows(attachment), is(false));
		assertThat(rig.sent(LeaveChain.class), is((long) attachment.streams().size()));
	}

	/* Whichever of two handles that share a join is told first, the one closed meanwhile does not take the chain
	   from under the other */
	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void startsTheOtherSessionsOfAJoinThatOneClosedWhileWaiting(@ForAll final boolean closedFirst) {
		final Rig rig = new Rig(ChainFleet.group());
		final List<Boolean> closing = closedFirst ? List.of(true, false) : List.of(false, true);

		closing.forEach(closed -> {
			final SessionHandle handle = rig.sessions.open(CONNECT, new Listener());

			if (closed) {
				handle.close(CloseReason.DONE);
			}
		});

		rig.fleet.deliver();

		assertThat(rig.opens().size(), is(1));
		assertThat(rig.answering().received, equalTo(List.of(CONNECT)));
	}

	/* Whether the attachment is still in use is asked on every tick, sessions or not, as that is where the node
	   notices that the proxy turned off */
	@Example
	public void leavesTheChainWhenTheProxyTurnsOffWhileASessionRunsOnIt() {
		final Rig rig = new Rig(ChainFleet.group());
		final SessionHandle handle = rig.sessions.open(CONNECT, new Listener());

		rig.fleet.deliver();

		final ChainAttachment attachment = rig.attachment();

		rig.fleet.settings.set(Optional.empty());
		rig.after(0);
		assertThat(rig.follows(attachment), is(false));
		assertThat(rig.sent(LeaveChain.class), is(0L));

		handle.close(CloseReason.DONE);
		rig.after(0);

		assertThat(rig.sent(LeaveChain.class), is((long) attachment.streams().size()));
	}

	/* The proxy turned off, or its settings could not be read, by the time the joined chain came back */
	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void letsGoOfAChainAtOnceWhenNoSessionCanStartOnIt(@ForAll final boolean failing) {
		final ChainFleet fleet = ChainFleet.group();
		final AtomicReference<Optional<ProxySettings>> proxy = new AtomicReference<>(fleet.settings.get());
		final Rig rig = new Rig(fleet, fleet.clock::get, () -> {
			if (failing && proxy.get().isEmpty()) {
				throw new IllegalStateException("The settings cannot be read");
			}

			return proxy.get();
		}, new SecureRandom());
		final Listener listener = new Listener();

		rig.sessions.open(CONNECT, listener);
		proxy.set(Optional.empty());
		fleet.deliver();

		assertThat(listener.messages, equalTo(List.of(new Close(CloseReason.UNREACHABLE))));
		assertThat(rig.sent(LeaveChain.class), is(2L));
		assertThat(fleet.view().chains(0).stream().map(fleet.host(CLIENT)::chain).flatMap(Optional::stream).toList(),
			empty());
	}

	/* The outbox is shared, so a pause is one for every session of the attachment, one opened meanwhile too */
	@Example
	public void pausesWhileTheChainHasNoRoomAndGoesOnWhenItHas() {
		final Rig rig = new Rig(ChainFleet.group(ChainFleet.smallChains(HANDSHAKE_ENTRIES)));
		final Listener listener = new Listener();
		final SessionHandle handle = rig.sessions.open(CONNECT, listener);

		rig.fleet.deliver();

		final ExitStub exit = rig.answering();

		rig.fleet.clock.addAndGet(15_000);
		handle.send(new Data(new byte[300]));

		assertThat(handle.isWritable(), is(false));

		rig.after(1000);
		assertThat(listener.writability, equalTo(List.of(false)));

		final Listener late = new Listener();
		final SessionHandle joined = rig.sessions.open(CONNECT, late);

		assertThat(joined.isWritable(), is(false));

		rig.fleet.clock.addAndGet(15_000);
		rig.maintainAll();
		rig.sessions.tick();

		assertThat(handle.isWritable(), is(true));
		assertThat(joined.isWritable(), is(true));
		assertThat(listener.writability, equalTo(List.of(false, true)));
		assertThat(late.writability, equalTo(List.of(true)));

		rig.fleet.deliver();
		assertThat(exit.received, equalTo(List.of(CONNECT, new Data(new byte[300]))));
	}

	/* The openers read from their handles that the chain has no room, the one right after the post the chain refused
	   and the other as it opened, and the chain takes the post before any tick saw it refused */
	@Example
	public void tellsTheOpenersThatReadThatTheChainHadNoRoomWhenItTakesThePostAtTheNextTick() {
		final Rig rig = new Rig(ChainFleet.group(ChainFleet.smallChains(HANDSHAKE_ENTRIES)));
		final Listener listener = new Listener();
		final SessionHandle handle = rig.sessions.open(CONNECT, listener);

		rig.fleet.deliver();
		rig.fleet.clock.addAndGet(15_000);
		handle.send(new Data(new byte[300]));

		final Listener late = new Listener();
		final SessionHandle joined = rig.sessions.open(CONNECT, late);

		assertThat(handle.isWritable(), is(false));
		assertThat(joined.isWritable(), is(false));

		rig.fleet.clock.addAndGet(16_000);
		rig.maintainAll();
		rig.sessions.tick();

		assertThat(handle.isWritable(), is(true));
		assertThat(listener.writability, equalTo(List.of(true)));
		assertThat(late.writability, equalTo(List.of(true)));
	}

	/* What a session sent waited for room longer than the chain keeps entries, so the chain will never take it: the
	   tick lets the attachment go as it does one whose chain was lost, its session ends on its idle limit, and the
	   next session joins with a writer of its own and carries on */
	@Example
	public void joinsWithANewWriterOnceAnEntryWaitedForRoomLongerThanTheChainKeepsEntries() {
		final ProxySettings tiny = ChainFleet.smallChains(HANDSHAKE_ENTRIES + 1);
		final Rig rig = new Rig(ChainFleet.group(tiny));
		final Listener stuck = new Listener();
		final SessionHandle handle = rig.sessions.open(CONNECT, stuck);

		rig.fleet.deliver();

		final ChainAttachment earlier = rig.attachment();
		final List<Session> answered = rig.exitSessions();
		final Entry filler = new FeedWriter(ChainFleet.key(400), earlier.chainId()).write(rig.fleet.clock.get(),
			Fingerprint.NONE, EntryKind.SEALED, new byte[1024 - Entry.OVERHEAD]);

		/* Another writer takes the room left after the handshake, which the next session's exit needs for its
		   Connected once both have gone past the retention window */
		rig.fleet.host(CLIENT).receive(earlier.streams().get(0), ChainEntry.builder().entries(List.of(filler))
			.build());

		rig.fleet.clock.addAndGet(15_000);
		handle.send(new Data(new byte[300]));
		rig.fleet.clock.addAndGet(RETENTION_MILLIS + 1000);
		rig.maintainAll();
		rig.after(0);
		rig.after(0);

		assertThat(rig.sent(LeaveChain.class), is((long) earlier.streams().size()));
		assertThat(handle.isWritable(), is(false));

		carriesOnWithANewWriter(rig, earlier, answered, new Listener());
		assertThat(rig.exitReceived(), equalTo(List.of(CONNECT, CONNECT)));

		rig.after(tiny.sessionIdleMillis());
		assertThat(stuck.messages, equalTo(List.of(new Close(CloseReason.IDLE))));
	}

	/* The client's writer holds the latest time it stamped, so once the clock stepped back further than the chain
	   keeps entries the chain takes nothing more of that feed: a session opened before the next tick already joins
	   with a writer of its own */
	@Example
	public void joinsWithANewWriterAtOnceWhenTheClockSteppedBackFurtherThanTheChainKeepsEntries() {
		final Rig rig = new Rig(ChainFleet.group());
		final SessionHandle handle = rig.sessions.open(CONNECT, new Listener());

		rig.fleet.deliver();

		final ChainAttachment earlier = rig.attachment();
		final List<Session> answered = rig.exitSessions();

		rig.fleet.clock.addAndGet(40_000);
		handle.send(data(1));
		rig.fleet.deliver();
		rig.fleet.clock.addAndGet(-RETENTION_MILLIS - 1000);
		handle.send(data(2));
		carriesOnWithANewWriter(rig, earlier, answered, new Listener());

		assertThat(rig.exitReceived(), containsInAnyOrder(CONNECT, data(1), CONNECT));
		assertThat(rig.sent(LeaveChain.class), is((long) earlier.streams().size()));
	}

	/* A session opened now joins with a writer of its own, whose CONNECT reaches an exit and whose exit's reply
	   reaches it */
	private static void carriesOnWithANewWriter(final Rig rig, final ChainAttachment earlier,
		final List<Session> answered, final Listener listener) {

		rig.sessions.open(CONNECT, listener);
		rig.fleet.deliver();
		assertThat(rig.attachment().writerId(), not(equalTo(earlier.writerId())));

		rig.exitSessions().stream().filter(exit -> !answered.contains(exit)).forEach(exit -> exit.send(CONNECTED));
		rig.fleet.deliver();

		assertThat(listener.messages, equalTo(List.of(CONNECTED)));
	}

	/* The listener that throws is enrolled before the one told after it, and one whose opener closed it before the
	   pause is told nothing. The chains hold the three openings, the answer and the Connect. */
	@Example
	public void tellsTheOtherListenersWhenOneOfThemThrows() {
		final Rig rig = new Rig(ChainFleet.group(ChainFleet.smallChains(5)));
		final AtomicBoolean thrown = new AtomicBoolean();
		final Listener closed = new Listener();
		final Listener listener = new Listener();

		rig.mute(true);
		rig.sessions.open(CONNECT, new Listener() {
			@Override
			public void writabilityChanged(final boolean writable) {
				thrown.set(true);
				throw new IllegalStateException("The listener failed");
			}
		});

		final SessionHandle closing = rig.sessions.open(CONNECT, closed);

		rig.fleet.deliver();
		closing.close(CloseReason.DONE);
		rig.mute(false);

		final SessionHandle handle = rig.sessions.open(CONNECT, listener);

		rig.fleet.deliver();
		handle.send(new Data(new byte[800]));
		rig.sessions.tick();
		rig.sessions.tick();

		assertThat(thrown.get(), is(true));
		assertThat(listener.writability, equalTo(List.of(false, false)));
		assertThat(closed.writability, equalTo(List.of()));
	}

	/* The network thread tells the listener with no lock of the handle held, so the tick thread can end the session
	   meanwhile. The Close that leaves is told right after, though the listener threw, unless the listener closed the
	   session meanwhile. */
	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void tellsTheListenerWithNoLockHeldAndInOrder(@ForAll final boolean closes) {
		final Rig rig = new Rig(ChainFleet.group());
		final AtomicReference<SessionHandle> handle = new AtomicReference<>();
		final AtomicReference<Throwable> stuck = new AtomicReference<>();
		final Listener listener = new Listener() {
			@Override
			public void received(final SealedMessage message) {
				super.received(message);

				if (message.equals(CONNECTED)) {
					rig.fleet.clock.addAndGet(IDLE_MILLIS);
					endMeanwhile(rig.sessions, stuck);
					closeOrFail(handle.get(), closes);
				}
			}
		};

		handle.set(rig.sessions.open(CONNECT, listener));
		rig.fleet.deliver();
		rig.answering().session().send(CONNECTED);
		rig.fleet.deliver();

		assertThat(stuck.get(), nullValue());
		assertThat(listener.messages, equalTo(closes ? List.of(CONNECTED)
			: List.of(CONNECTED, new Close(CloseReason.IDLE))));
		assertThat(rig.answering().received, equalTo(List.of(CONNECT, new Close(CloseReason.IDLE))));
	}

	private static void closeOrFail(final SessionHandle handle, final boolean closes) {
		if (!closes) {
			throw new IllegalStateException("The listener failed");
		}

		handle.close(CloseReason.DONE);
	}

	private static void endMeanwhile(final ClientSessions sessions, final AtomicReference<Throwable> stuck) {
		try {
			CompletableFuture.runAsync(sessions::tick).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (Exception ex) {
			stuck.set(ex);
		}
	}

	@Example
	public void schedulesTheTickOnceWhenSessionsOpenAtOnce() {
		final int openers = 8;
		final Rig rig = new Rig(ChainFleet.group());
		final CyclicBarrier start = new CyclicBarrier(openers);
		final ExecutorService threads = Executors.newFixedThreadPool(openers);
		final Callable<SessionHandle> open = () -> {
			start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return rig.sessions.open(CONNECT, new Listener());
		};

		try {
			Stream.generate(() -> threads.submit(open)).limit(openers).toList()
				.forEach(ClientSessionsTest::within);
		} finally {
			threads.shutdownNow();
		}

		rig.fleet.deliver();

		assertThat(rig.scheduler.schedules, is(1));
		assertThat(rig.exitReceived(), equalTo(Collections.nCopies(openers, CONNECT)));
	}

	/* An idle limit longer than an epoch and its grace, so that the grace and not the idle check ends the session */
	private static ProxySettings epochOf(final long periodSeconds) {
		return new ProxySettings(ChainFleet.MEMBERS, periodSeconds, 4, 16, 32L * 1024 * 1024, 30, 16 * 1024, 10_000,
			ProxySettings.CONNECT | ProxySettings.UDP);
	}

	@Example
	public void letsTheSessionsOfAnEarlierEpochRunOnForTheGraceAndThenEndsThem() {
		final Rig rig = new Rig(ChainFleet.group(epochOf(3600)));
		final Listener draining = new Listener();
		final Listener fresh = new Listener();

		rig.sessions.open(CONNECT, draining);
		rig.fleet.deliver();

		final ChainAttachment earlier = rig.attachment();

		rig.fleet.clock.addAndGet(EPOCH_MILLIS);
		rig.fleet.refreshAll();
		rig.sessions.tick();
		rig.sessions.open(CONNECT, fresh);
		rig.fleet.deliver();

		assertThat(rig.attachment().chainId(), not(equalTo(earlier.chainId())));

		rig.after(ChainHost.DRAIN_GRACE_MILLIS - 1);
		assertThat(draining.messages, equalTo(List.of()));
		assertThat(rig.fleet.host(CLIENT).chain(earlier.chainId()).isPresent(), is(true));

		rig.after(1);
		assertThat(draining.messages, equalTo(List.of(new Close(CloseReason.DONE))));
		assertThat(fresh.messages, equalTo(List.of()));
		assertThat(rig.fleet.host(CLIENT).chain(earlier.chainId()), equalTo(Optional.empty()));
		assertThat(rig.fleet.host(CLIENT).chain(rig.attachment().chainId()).isPresent(), is(true));
	}

	@Example
	public void followsNoMoreThanTheCurrentChainAndTheOneThatDrainsWhenEpochsAreShort() {
		final Rig rig = new Rig(ChainFleet.group(epochOf(60)));
		final List<ChainAttachment> attachments = new ArrayList<>();

		for (int epoch = 0; epoch < 6; epoch++) {
			rig.sessions.open(CONNECT, new Listener());
			rig.fleet.deliver();
			attachments.add(rig.attachment());
			rig.fleet.clock.addAndGet(60_000);
			rig.fleet.refreshAll();
			rig.sessions.tick();
			rig.fleet.deliver();

			assertThat(attachments.stream().filter(attachment -> rig.fleet.host(CLIENT).chain(attachment.chainId())
				.isPresent()).count() <= 2, is(true));
		}

		assertThat(rig.fleet.host(CLIENT).chain(attachments.get(0).chainId()), equalTo(Optional.empty()));
	}

	/* The sessions read the spork through a source the test sets, the members and the client through the fleet's */
	private static Rig withSpork(final ChainFleet fleet, final AtomicReference<Optional<ProxySettings>> spork) {
		return new Rig(fleet, fleet.clock::get, spork::get, new SecureRandom());
	}

	/* The epoch turns, and the tick right after it finds the attachment of the earlier one no longer in use */
	private static void turnTheEpoch(final Rig rig) {
		rig.fleet.clock.addAndGet(EPOCH_MILLIS);
		rig.fleet.refreshAll();
		rig.after(0);
	}

	/* The grace ends a session once, as the close of its opener would, and its listener is told with no lock of the
	   handle held; a session its opener closed meanwhile is told nothing, and the attachment is let go */
	@Example
	public void endsTheSessionsOfAnEarlierAttachmentOnceTheGraceIsOverAndTellsEachOnce() {
		final Rig rig = new Rig(ChainFleet.group(epochOf(3600)));
		final AtomicReference<SessionHandle> handle = new AtomicReference<>();
		final List<Boolean> locked = new CopyOnWriteArrayList<>();
		final Listener ending = new Listener() {
			@Override
			public void received(final SealedMessage message) {
				super.received(message);
				locked.add(Thread.holdsLock(handle.get()));
			}
		};
		final Listener closing = new Listener();

		handle.set(rig.sessions.open(CONNECT, ending));

		final SessionHandle closed = rig.sessions.open(CONNECT, closing);

		rig.fleet.deliver();

		final ChainAttachment earlier = rig.attachment();

		turnTheEpoch(rig);
		rig.after(ChainHost.DRAIN_GRACE_MILLIS - 1);
		assertThat(ending.messages, is(empty()));
		assertThat(rig.follows(earlier), is(true));

		closed.close(CloseReason.DONE);
		rig.after(1);
		assertThat(ending.messages, equalTo(List.of(new Close(CloseReason.DONE))));
		assertThat(locked, equalTo(List.of(false)));
		assertThat(closing.messages, is(empty()));
		assertThat(rig.exitReceived(), containsInAnyOrder(CONNECT, CONNECT, new Close(CloseReason.DONE),
			new Close(CloseReason.DONE)));

		rig.after(1000);
		assertThat(ending.messages, equalTo(List.of(new Close(CloseReason.DONE))));
		assertThat(rig.follows(earlier), is(false));
		assertThat(rig.sent(LeaveChain.class), is((long) earlier.streams().size()));
	}

	/* The grace is the lesser of ten minutes and the epoch of the spork as it reads at each tick */
	@Example
	public void endsTheSessionsByTheGraceOfTheSporkAsItReadsAtTheTick() {
		final ChainFleet fleet = ChainFleet.group(epochOf(3600));
		final AtomicReference<Optional<ProxySettings>> spork = new AtomicReference<>(fleet.settings.get());
		final Rig rig = withSpork(fleet, spork);
		final Listener listener = new Listener();

		rig.sessions.open(CONNECT, listener);
		rig.fleet.deliver();
		turnTheEpoch(rig);
		spork.set(Optional.of(epochOf(120)));

		rig.after(119_999);
		assertThat(listener.messages, is(empty()));

		rig.after(1);
		assertThat(listener.messages, equalTo(List.of(new Close(CloseReason.DONE))));
	}

	/* While the spork is absent the proxy is off and no grace runs out, but the time the attachment went out of use is
	   kept: once the spork is back, a grace that is over ends the sessions at once */
	@Example
	public void endsNoSessionByTheGraceWhileTheSporkIsAbsent() {
		final ChainFleet fleet = ChainFleet.group(epochOf(3600));
		final AtomicReference<Optional<ProxySettings>> spork = new AtomicReference<>(fleet.settings.get());
		final Rig rig = withSpork(fleet, spork);
		final Listener listener = new Listener();

		rig.sessions.open(CONNECT, listener);
		rig.fleet.deliver();
		spork.set(Optional.empty());
		turnTheEpoch(rig);
		rig.after(ChainHost.DRAIN_GRACE_MILLIS);
		assertThat(listener.messages, is(empty()));

		spork.set(fleet.settings.get());
		rig.after(0);
		assertThat(listener.messages, equalTo(List.of(new Close(CloseReason.DONE))));
	}

	/* A spork that cannot be read counts as one that is absent, and the rest of the tick goes on */
	@Example
	public void endsNoSessionByTheGraceWhileTheSporkCannotBeReadAndGoesOnTicking() {
		final ChainFleet fleet = ChainFleet.group(epochOf(3600));
		final AtomicBoolean unreadable = new AtomicBoolean();
		final Rig rig = new Rig(fleet, fleet.clock::get, () -> {
			if (unreadable.get()) {
				throw new IllegalStateException("The spork cannot be read");
			}

			return fleet.settings.get();
		}, new SecureRandom());
		final Listener listener = new Listener();
		final SessionHandle handle = rig.sessions.open(CONNECT, listener);

		rig.fleet.deliver();

		final ChainAttachment earlier = rig.attachment();

		unreadable.set(true);
		turnTheEpoch(rig);
		rig.after(ChainHost.DRAIN_GRACE_MILLIS);
		assertThat(listener.messages, is(empty()));

		handle.close(CloseReason.DONE);
		rig.after(0);
		assertThat(rig.follows(earlier), is(false));
		assertThat(rig.sent(LeaveChain.class), is((long) earlier.streams().size()));
	}

	/* An attachment in use again, as when the clock stepped back over the turn of the epoch, starts its grace over */
	@Example
	public void startsTheGraceOverForAnAttachmentThatIsInUseAgain() {
		final Rig rig = new Rig(ChainFleet.group(epochOf(3600)));
		final Listener listener = new Listener();

		rig.sessions.open(CONNECT, listener);
		rig.fleet.deliver();
		rig.after(EPOCH_MILLIS);
		rig.after(-EPOCH_MILLIS);
		rig.after(EPOCH_MILLIS + 1000);

		rig.after(ChainHost.DRAIN_GRACE_MILLIS - 1);
		assertThat(listener.messages, is(empty()));

		rig.after(1);
		assertThat(listener.messages, equalTo(List.of(new Close(CloseReason.DONE))));
	}

	/* The client reads the spork and its clock to tell whether an attachment is in use, so a tick asks it once for
	   each attachment */
	@Example
	public void asksWhetherEachAttachmentIsInUseOnceATick() {
		final ChainFleet fleet = ChainFleet.group(epochOf(3600));
		final AtomicInteger reads = new AtomicInteger();
		final Rig rig = new Rig(fleet, () -> {
			reads.incrementAndGet();
			return fleet.settings.get();
		}, fleet.clock::get, fleet.settings::get, new SecureRandom());

		rig.sessions.open(CONNECT, new Listener());
		rig.fleet.deliver();
		reads.set(0);
		rig.sessions.tick();
		assertThat(reads.get(), is(1));

		rig.fleet.clock.addAndGet(EPOCH_MILLIS);
		rig.fleet.refreshAll();
		rig.sessions.open(CONNECT, new Listener());
		rig.fleet.deliver();
		reads.set(0);
		rig.sessions.tick();
		assertThat(reads.get(), is(2));
	}
}
