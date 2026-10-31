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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.jqwik.Expect;
import org.unigrid.hedgehog.model.geo.CountryLookup;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.Correlated;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.group.GroupChoice;
import org.unigrid.hedgehog.proxy.group.GroupView;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.ExitCandidate;

public class ChainClientTest {
	private static final String CLIENT = "client";
	private static final long EPOCH_MILLIS = 3_600_000;
	private static final int TIMEOUT_SECONDS = 10;
	private static final JoinReply.Status JOINED = JoinReply.Status.JOINED;
	private static final JoinReply.Status FULL = JoinReply.Status.FULL;
	private static final String FAILURE_DETAIL = "10.9.8.7:52883 turned the stream down";

	/* Lets the requests with the given numbers time out at once and leaves the others to the node's own */
	private static final class Flaky extends PendingRequests {
		private final PendingRequests own;
		private final Set<Integer> timeouts;
		private int requests;

		private Flaky(final PendingRequests own, final Set<Integer> timeouts) {
			this.own = own;
			this.timeouts = timeouts;
		}

		@Override
		public <T extends Correlated> CompletableFuture<T> register(final long requestId, final Channel channel,
			final Class<T> type) {

			return timeouts.contains(requests++) ? CompletableFuture.failedFuture(new TimeoutException())
				: own.register(requestId, channel, type);
		}

		@Override
		public void complete(final Channel channel, final Correlated response) {
			own.complete(channel, response);
		}
	}

	/* What a member sends back at once when it is asked to join a writer */
	@FunctionalInterface
	private interface Answer {
		List<Object> to(String member, JoinChain request);
	}

	private record Written(String member, Object packet) {
		/* A packet the node wrote to a member */
	}

	private record Followed(ChainId chainId, Channel stream, String thread) {
		/* A follow the node made, and the thread it made it on */
	}

	/* A client node and members that answer it on the thread that writes to them: before a join is written, the
	   answer of the member is completed and the packets after it are taken in, as an event loop that reads them
	   right away would. What the node writes and every follow it makes are logged in the order they happen. */
	private static final class Scripted {
		final AtomicReference<Optional<ProxySettings>> settings;
		final AtomicLong clock = new AtomicLong(ChainFleet.NOW);
		final List<String> ids;
		final List<Gridnode> listed = new CopyOnWriteArrayList<>();
		final Map<String, EmbeddedChannel> streams = new LinkedHashMap<>();
		final Map<String, Answer> answers = new ConcurrentHashMap<>();
		final Set<String> unwritable = ConcurrentHashMap.newKeySet();
		final List<Object> log = new CopyOnWriteArrayList<>();
		final PendingRequests pending;
		final ChainHost host;
		volatile ProxySettingsSource source;
		volatile ChainTransport transport = id -> Optional.ofNullable(streams.get(id));
		volatile Runnable afterFollow = () -> { };
		volatile boolean chainFails;

		/* One chain for each group, so that every member of a group answers the same one */
		Scripted(final int members, final int groupSize, final PendingRequests pending) {
			this.pending = pending;
			settings = new AtomicReference<>(Optional.of(ChainFleet.settings(groupSize, 1, 16, 30, 3)));
			source = settings::get;
			ids = IntStream.range(0, members).mapToObj(number -> "gn-" + number).toList();
			ids.forEach(id -> streams.put(id, new EmbeddedChannel(new Member(id))));
			ids.forEach(id -> listed.add(ChainFleet.gridnode(id, Gridnode.Status.ACTIVE)));

			host = new ChainHost(settings::get, directory(), id -> Optional.empty(), clock::get, new Random(1),
				new ManualScheduler()) {

				@Override
				public boolean follow(final ChainId chainId, final Channel member,
					final Map<WriterId, Head> anchors) {

					final boolean followed = super.follow(chainId, member, anchors);

					log.add(new Followed(chainId, member, Thread.currentThread().getName()));
					afterFollow.run();
					return followed;
				}

				@Override
				public Optional<Chain> chain(final ChainId id) {
					if (chainFails) {
						throw new IllegalStateException(FAILURE_DETAIL);
					}

					return super.chain(id);
				}
			};
		}

		/* The member end of a stream */
		private final class Member extends ChannelOutboundHandlerAdapter {
			private final String id;

			private Member(final String id) {
				this.id = id;
			}

			@Override
			public void write(final ChannelHandlerContext context, final Object packet,
				final ChannelPromise promise) {

				if (unwritable.contains(id)) {
					promise.setFailure(new ClosedChannelException());
					return;
				}

				log.add(new Written(id, packet));
				promise.setSuccess();

				if (packet instanceof JoinChain request) {
					answers.getOrDefault(id, joined(Map.of())).to(id, request)
						.forEach(answer -> receive(context.channel(), answer));
				}
			}
		}

		private void receive(final Channel stream, final Object packet) {
			if (packet instanceof JoinReply reply) {
				pending.complete(stream, reply);
			} else {
				host.receive(stream, (ChainEntry) packet);
			}
		}

		GridnodeDirectory directory() {
			return new TopologyGridnodeDirectory(() -> listed, () -> CLIENT);
		}

		ChainClient client(final RandomGenerator random, final Executor joins) {
			return new ChainClient(() -> source.current(), directory(), CountryLookup.NONE,
				id -> transport.streamTo(id), host, pending, clock::get, random, joins);
		}

		ChainClient client() {
			return client(new Random(1), Runnable::run);
		}

		GroupView view() {
			return new GroupView(settings.get().orElseThrow(), clock.get(), ids);
		}

		ChainId chainOf(final String member) {
			final GroupView view = view();

			return view.chains(view.groups().groupOf(member).orElseThrow()).get(0);
		}

		/* Admits the writer to the chain asked for, else to the chain of the member's group */
		Answer joined(final Map<WriterId, Head> heads) {
			return (member, request) -> List.of(reply(request, JOINED, request.getChainId()
				.orElse(chainOf(member)), heads));
		}

		static Answer refusing(final JoinReply.Status status) {
			return (member, request) -> List.of(reply(request, status, null, Map.of()));
		}

		static JoinReply reply(final JoinChain request, final JoinReply.Status status, final ChainId chainId,
			final Map<WriterId, Head> heads) {

			return JoinReply.builder().requestId(request.getRequestId()).status(status).chainId(chainId)
				.heads(heads).build();
		}

		/* Holds back the answer to a join for any chain, for the test to give, and admits a writer at once to a
		   chain asked for by name */
		Answer holdingTheFirstIn(final BlockingQueue<Written> waiting) {
			return (member, request) -> {
				if (request.getChainId().isPresent()) {
					return joined(Map.of()).to(member, request);
				}

				waiting.add(new Written(member, request));
				return List.of();
			};
		}

		/* Answers a join that waited, as the event loop of its stream would, and tells how many follows the node
		   had made by the time the answer was through */
		int answer(final Written asked) {
			final JoinChain request = (JoinChain) asked.packet();

			pending.complete(streams.get(asked.member()), reply(request, JOINED, request.getChainId()
				.orElse(chainOf(asked.member())), Map.of()));

			return follows().size();
		}

		List<Written> written(final Class<?> type) {
			return log.stream().filter(Written.class::isInstance).map(Written.class::cast)
				.filter(written -> type.isInstance(written.packet())).toList();
		}

		List<String> asked() {
			return written(JoinChain.class).stream().map(Written::member).toList();
		}

		List<Object> leaves(final String member) {
			return written(LeaveChain.class).stream().filter(written -> written.member().equals(member))
				.map(Written::packet).toList();
		}

		List<Followed> follows() {
			return log.stream().filter(Followed.class::isInstance).map(Followed.class::cast).toList();
		}

		String memberOf(final Channel stream) {
			return streams.entrySet().stream().filter(entry -> entry.getValue() == stream).findFirst()
				.orElseThrow().getKey();
		}

		/* The members asked and the follows, in the order they happened */
		List<String> order() {
			return log.stream().<String>mapMulti((logged, order) -> {
				if (logged instanceof Written written && written.packet() instanceof JoinChain) {
					order.accept("ask " + written.member());
				} else if (logged instanceof Followed followed) {
					order.accept("follow " + memberOf(followed.stream()));
				}
			}).toList();
		}
	}

	/* What the client warns about while the action runs, read as ChainFleet#warningsWhile reads the host's */
	private static List<ILoggingEvent> warningsWhile(final Runnable action) {
		ChainClientTest.class.getModule().addReads(ModuleLayer.boot().findModule("ch.qos.logback.core")
			.orElseThrow());

		final Logger logger = (Logger) LoggerFactory.getLogger(ChainClient.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();

		appender.start();
		logger.addAppender(appender);

		try {
			action.run();
		} finally {
			logger.detachAppender(appender);
		}

		return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
	}

	private static ChainClient clientOf(final ChainFleet fleet, final CountryLookup countries,
		final PendingRequests pending, final RandomGenerator random) {

		return new ChainClient(fleet.settings::get, fleet.directory(CLIENT), countries, fleet.transport(CLIENT),
			fleet.host(CLIENT), pending, fleet.clock::get, random, Runnable::run);
	}

	private static ChainClient clientOf(final ChainFleet fleet, final PendingRequests pending, final Random random) {
		return clientOf(fleet, CountryLookup.NONE, pending, random);
	}

	private static ChainClient clientOf(final ChainFleet fleet, final CountryLookup countries) {
		return clientOf(fleet, countries, fleet.pending(CLIENT), new Random(1));
	}

	private static ChainClient clientOf(final ChainFleet fleet) {
		return clientOf(fleet, CountryLookup.NONE);
	}

	private static ChainAttachment attached(final ChainFleet fleet, final ChainClient client) {
		return attached(fleet, client, Optional.empty());
	}

	private static ChainAttachment attached(final ChainFleet fleet, final ChainClient client,
		final Optional<String> country) {

		final CompletableFuture<ChainAttachment> attachment = client.attachment(country);

		fleet.deliver();
		return done(attachment);
	}

	/* What the fleet and the scripted members set going is through before the call that set it going returns, so
	   a future that is not done then never is, and the test fails instead of waiting for it */
	private static <T> T done(final CompletableFuture<T> future) {
		assertThat(future.isDone(), is(true));
		return future.join();
	}

	/* Eight gridnodes in two groups of four. Only the members of the groups on duty have seen the spork, the
	   others answer every join as disabled. */
	static ChainFleet twoGroups(final int clientsPerChain, final int chainsPerGroup, final Set<Integer> onDuty) {
		return twoGroups(clientsPerChain, chainsPerGroup, onDuty, ChainFleet::seeded);
	}

	/* The same, with the hosts drawing the stem from the generators given for their ids */
	static ChainFleet twoGroups(final int clientsPerChain, final int chainsPerGroup, final Set<Integer> onDuty,
		final Function<String, RandomGenerator> stems) {

		final ChainFleet fleet = new ChainFleet(8);

		fleet.stems = stems;
		fleet.settings.set(Optional.of(ChainFleet.settings(4, chainsPerGroup, clientsPerChain, 30, 3)));
		IntStream.range(0, 8).forEach(number -> fleet.host("gn-" + number));
		onDuty.forEach(group -> fleet.view().groups().members(group)
			.forEach(member -> fleet.host(member).refresh()));
		return fleet;
	}

	/* Twenty gridnodes in five groups of four, all on duty */
	static ChainFleet fiveGroups() {
		final ChainFleet fleet = new ChainFleet(20);

		fleet.settings.set(Optional.of(ChainFleet.settings(4, 1, 16, 30, 3)));
		IntStream.range(0, 20).forEach(number -> fleet.host("gn-" + number));
		fleet.refreshAll();
		return fleet;
	}

	/* A random generator whose first choice of a group for no country is the given one, when no group is passed
	   over */
	static Random choosing(final ChainFleet fleet, final int group) {
		return choosing(fleet.view(), group);
	}

	static Random choosing(final GroupView view, final int group) {
		final int seed = IntStream.iterate(0, next -> next + 1).limit(10_000)
			.filter(next -> GroupChoice.group(view.groups(), Optional.empty(), member -> Optional.empty(), Set.of(),
			new Random(next)).getAsInt() == group).findFirst().orElseThrow();

		return new Random(seed);
	}

	/* Twelve gridnodes in three groups of four, of which only the groups on duty take a client */
	static ChainFleet threeGroups(final Set<Integer> onDuty) {
		final ChainFleet fleet = new ChainFleet(12);

		fleet.settings.set(Optional.of(ChainFleet.settings(4, 1, 16, 30, 3)));
		IntStream.range(0, 12).forEach(number -> fleet.host("gn-" + number));
		onDuty.forEach(group -> fleet.view().groups().members(group)
			.forEach(member -> fleet.host(member).refresh()));
		return fleet;
	}

	/* Draws the given numbers in turn for the choices of a group, the first of whatever else it is offered, and a
	   seed of zero for every shuffle, so that each group is asked through the same pair every time */
	private static RandomGenerator drawing(final long... draws) {
		final Queue<Long> queued = new ArrayDeque<>(LongStream.of(draws).boxed().toList());

		return new RandomGenerator() {
			@Override
			public long nextLong() {
				return 0;
			}

			@Override
			public long nextLong(final long bound) {
				return queued.remove();
			}

			@Override
			public int nextInt(final int bound) {
				return 0;
			}
		};
	}

	/* Draws the last of whatever it is offered, so that of the groups that weigh anything the last one is chosen */
	private static RandomGenerator last() {
		return new RandomGenerator() {
			@Override
			public long nextLong() {
				return 0;
			}

			@Override
			public long nextLong(final long bound) {
				return bound - 1;
			}

			@Override
			public int nextInt(final int bound) {
				return bound - 1;
			}
		};
	}

	static long joinsTo(final ChainFleet fleet, final List<String> members) {
		return fleet.deliveries.stream().filter(delivery -> delivery.packet() instanceof JoinChain
			&& members.contains(delivery.to())).count();
	}

	private static long delivered(final ChainFleet fleet, final Class<?> type) {
		return fleet.deliveries.stream().filter(delivery -> type.isInstance(delivery.packet())).count();
	}

	/* The members that hold the writer of an attachment on its chain, a retired one included */
	private static long attachedAt(final ChainFleet fleet, final ChainAttachment attachment) {
		return fleet.activeIds().stream().filter(member -> fleet.host(member).hosted(attachment.chainId())
			.filter(hosted -> hosted.isAttached(attachment.writerId())).isPresent()).count();
	}

	private static ChainId chainId(final int number) {
		final byte[] id = new byte[ChainId.SIZE];

		id[0] = (byte) number;
		return new ChainId(id);
	}

	private static LeaveChain leave(final ChainId chainId, final WriterId writer) {
		return LeaveChain.builder().chainId(chainId).writer(writer).build();
	}

	private static WriterId writerAsked(final Scripted scripted) {
		return ((JoinChain) scripted.written(JoinChain.class).get(0).packet()).getWriter();
	}

	private static Runnable once(final Runnable action) {
		final AtomicBoolean done = new AtomicBoolean();

		return () -> {
			if (!done.getAndSet(true)) {
				action.run();
			}
		};
	}

	private static ExecutorService named(final String name) {
		return Executors.newSingleThreadExecutor(body -> new Thread(body, name));
	}

	private static String firstAsked(final int seed) {
		final Scripted scripted = new Scripted(4, 4, new PendingRequests());

		scripted.streams.get("gn-3").close();
		scripted.ids.forEach(id -> scripted.answers.put(id, Scripted.refusing(JoinReply.Status.FULL)));
		scripted.client(new Random(seed), Runnable::run).attachment(Optional.empty());
		return scripted.asked().get(0);
	}

	@Provide
	public Arbitrary<Integer> headCounts() {
		return Arbitraries.of(0, 1, HostedChain.MAX_WRITERS);
	}

	@Example
	public void joinsAGroupAndAttachesToTwoOfItsMembers() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainAttachment attachment = attached(fleet, clientOf(fleet));

		assertThat(fleet.view().chains(attachment.group()), hasItem(attachment.chainId()));
		assertThat(attachment.members(), equalTo(fleet.view().groups().members(attachment.group())));
		assertThat(attachment.streams().size(), is(2));
		assertThat(attachment.streams().get(0), not(sameInstance(attachment.streams().get(1))));
		assertThat(fleet.host(CLIENT).chain(attachment.chainId()).isPresent(), is(true));

		for (final Channel stream : attachment.streams()) {
			final String member = fleet.activeIds().stream().filter(id -> fleet.stream(CLIENT, id) == stream)
				.findFirst().orElseThrow();

			assertThat(fleet.host(member).hosted(attachment.chainId()).orElseThrow()
				.isAttached(attachment.writerId()), is(true));
		}
	}

	@Example
	public void anchorsTheFeedsOfTheChainAtTheHeadsOfTheReply() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.settings(ChainFleet.MEMBERS, 1, 16, 30, 3));
		final List<Entry> earlier = ChainFleet.entries(ChainFleet.key(9), fleet.chain(), 5, ChainFleet.NOW);

		fleet.host("gn-1").receive(fleet.client("gn-1", 9), ChainEntry.builder().entries(earlier).build());
		fleet.deliver();

		final ChainAttachment attachment = attached(fleet, clientOf(fleet));

		assertThat(fleet.host(CLIENT).chain(attachment.chainId()).orElseThrow().heads().get(ChainFleet.writer(9)),
			is(fleet.host("gn-0").chain(fleet.chain()).orElseThrow().heads().get(ChainFleet.writer(9))));
	}

	@Example
	public void triesAnotherGroupWhenTheFirstIsDisabled() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(1));
		final ChainClient client = clientOf(fleet, fleet.pending(CLIENT), choosing(fleet, 0));

		assertThat(attached(fleet, client).group(), is(1));
		assertThat(joinsTo(fleet, fleet.view().groups().members(0)), is(1L));
	}

	@Example
	public void triesAnotherGroupWhenEveryChainOfTheFirstIsFull() {
		final ChainFleet fleet = twoGroups(1, 1, Set.of(0, 1));
		final List<String> full = fleet.view().groups().members(0);

		full.forEach(member -> fleet.host(member).join(new EmbeddedChannel(), JoinChain.builder().requestId(1)
			.writer(ChainFleet.writer(9)).build()));

		final ChainClient client = clientOf(fleet, fleet.pending(CLIENT), choosing(fleet, 0));

		assertThat(attached(fleet, client).group(), is(1));
		assertThat(joinsTo(fleet, full), is(1L));
	}

	@Example
	public void triesAnotherGroupWhenAMemberDoesNotAnswer() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(0, 1));
		final ChainClient client = clientOf(fleet, new Flaky(fleet.pending(CLIENT), Set.of(0)), choosing(fleet, 0));

		assertThat(attached(fleet, client).group(), is(1));
		assertThat(joinsTo(fleet, fleet.view().groups().members(0)), is(1L));
	}

	@Example
	public void triesAnotherGroupWhenNoMemberIsConnected() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(0, 1));

		fleet.view().groups().members(0).forEach(fleet::offline);

		final ChainClient client = clientOf(fleet, fleet.pending(CLIENT), choosing(fleet, 0));

		assertThat(attached(fleet, client).group(), is(1));
		assertThat(joinsTo(fleet, fleet.view().groups().members(0)), is(0L));
	}

	/* Each country is given a group with members there and keeps it, and a choice that lands on a group joined
	   already, as one for any country does, takes the attachment of that group */
	@Example
	public void twoCountriesUseTwoAttachments() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(0, 1));
		final Map<String, String> countries = new ConcurrentHashMap<>();
		final ChainClient client = clientOf(fleet, fleet.located(countries));

		fleet.view().groups().members(0).forEach(member -> countries.put(member, "SE"));
		fleet.view().groups().members(1).forEach(member -> countries.put(member, "NO"));

		final ChainAttachment swedish = attached(fleet, client, Optional.of("SE"));
		final ChainAttachment norwegian = attached(fleet, client, Optional.of("NO"));

		assertThat(List.of(swedish.group(), norwegian.group()), equalTo(List.of(0, 1)));
		assertThat(done(client.attachment(Optional.of("SE"))), sameInstance(swedish));
		assertThat(done(client.attachment(Optional.of("NO"))), sameInstance(norwegian));
		assertThat(done(client.attachment(Optional.empty())), is(in(List.of(swedish, norwegian))));
		assertThat(List.of(client.isCurrent(swedish), client.isCurrent(norwegian)), equalTo(List.of(true, true)));
		assertThat(joinsTo(fleet, fleet.view().groups().members(0)), is(2L));
		assertThat(joinsTo(fleet, fleet.view().groups().members(1)), is(2L));
		assertThat(fleet.host(CLIENT).chain(swedish.chainId()).isPresent(), is(true));
		assertThat(fleet.host(CLIENT).chain(norwegian.chainId()).isPresent(), is(true));
	}

	/* Four countries fill the attachments the node may hold, one group each, though it may follow twice as many
	   chains. Of the three groups with members in Germany, the last is chosen, which holds four of them but is not
	   joined, so the country is given the joined group with the most of them, two. */
	@Example
	public void atMostFourAttachments() {
		final ChainFleet fleet = fiveGroups();
		final Map<String, String> countries = new ConcurrentHashMap<>();
		final ChainClient client = clientOf(fleet, fleet.located(countries), fleet.pending(CLIENT), last());
		final List<List<String>> placed = List.of(List.of("SE", "SE", "SE", "SE"), List.of("NO", "NO", "NO", "NO"),
			List.of("DK", "DK", "DK", "DE"), List.of("FI", "FI", "DE", "DE"), List.of("DE", "DE", "DE", "DE"));

		assertThat(fleet.view().groups().groupCount(), is(5));
		IntStream.range(0, 5).forEach(group -> IntStream.range(0, 4).forEach(member -> countries.put(fleet.view()
			.groups().members(group).get(member), placed.get(group).get(member))));

		final List<ChainAttachment> attachments = Stream.of("SE", "NO", "DK", "FI")
			.map(country -> attached(fleet, client, Optional.of(country))).toList();

		assertThat(attachments.stream().map(ChainAttachment::group).toList(), equalTo(List.of(0, 1, 2, 3)));
		assertThat(attached(fleet, client, Optional.of("DE")), sameInstance(attachments.get(3)));
		assertThat(joinsTo(fleet, fleet.view().groups().members(4)), is(0L));
		assertThat(delivered(fleet, JoinChain.class), is(8L));
		assertThat(attachments.stream().map(client::isCurrent).toList(), everyItem(is(true)));
	}

	/* The four chains of an epoch that turned stay followed while their sessions drain, as nothing released them,
	   and the four chains the same countries join in the new epoch are followed next to them */
	@Example
	public void followsTheChainsOfANewEpochWhileTheOldOnesDrain() {
		final ChainFleet fleet = fiveGroups();
		final Map<String, String> countries = new ConcurrentHashMap<>();
		final ChainClient client = clientOf(fleet, fleet.located(countries));
		final List<String> named = List.of("SE", "NO", "DK", "FI", "DE");
		final Runnable place = () -> IntStream.range(0, 5).forEach(group -> fleet.view().groups().members(group)
			.forEach(member -> countries.put(member, named.get(group))));

		place.run();

		final List<ChainAttachment> earlier = named.subList(0, 4).stream()
			.map(country -> attached(fleet, client, Optional.of(country))).toList();

		fleet.clock.addAndGet(EPOCH_MILLIS);
		fleet.refreshAll();
		place.run();

		final List<ChainAttachment> later = named.subList(0, 4).stream()
			.map(country -> attached(fleet, client, Optional.of(country))).toList();

		assertThat(later.stream().map(ChainAttachment::group).toList(), equalTo(List.of(0, 1, 2, 3)));
		assertThat(later.stream().map(ChainAttachment::epoch).distinct().toList(),
			equalTo(List.of(fleet.view().epoch())));
		assertThat(later.stream().map(client::isCurrent).toList(), everyItem(is(true)));
		assertThat(Stream.concat(earlier.stream(), later.stream()).map(attachment -> fleet.host(CLIENT)
			.chain(attachment.chainId()).isPresent()).toList(), everyItem(is(true)));
		assertThat(ChainHost.MAX_FOLLOWED, is(2 * ChainClient.MAX_ATTACHMENTS));
	}

	/* The last group is drawn for any country and again for Sweden, where its only members are the two the node
	   attached through. It is passed over for the first group, which has three members there, and still serves
	   the sessions that name no country. */
	@Example
	public void passesOverAGroupWhoseOnlyMembersInTheCountryAreItsAttachMembers() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(0, 1));
		final Map<String, String> countries = new ConcurrentHashMap<>();
		final ChainClient client = clientOf(fleet, fleet.located(countries), fleet.pending(CLIENT), last());
		final ChainAttachment any = attached(fleet, client, Optional.empty());
		final List<String> swedes = fleet.view().groups().members(0).subList(0, 3);

		fleet.activeIds().forEach(member -> countries.put(member, "NO"));
		fleet.attachMembers(CLIENT, any).forEach(member -> countries.put(member, "SE"));
		swedes.forEach(member -> countries.put(member, "SE"));

		final ChainAttachment swedish = attached(fleet, client, Optional.of("SE"));

		assertThat(List.of(any.group(), swedish.group()), equalTo(List.of(1, 0)));
		assertThat(any.exit(Optional.of("SE"), Set.of()), equalTo(Optional.empty()));
		assertThat(drawnExits(swedish, Optional.of("SE"), Set.of()), everyItem(is(in(swedes))));
		assertThat(done(client.attachment(Optional.of("SE"))), sameInstance(swedish));
		assertThat(done(client.attachment(Optional.empty())), sameInstance(any));
		assertThat(List.of(client.isCurrent(any), client.isCurrent(swedish)), equalTo(List.of(true, true)));
	}

	/* The node holds streams to two members of the last group alone, the only members it has in Sweden, so they
	   would be its attach pair and no exit could be left there: the group is passed over before either is asked,
	   and the first group, with three members in Sweden, takes the node */
	@Example
	public void asksNoMemberOfAGroupWhereTheAttachPairHoldsAllOfTheCountry() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(0, 1));
		final Map<String, String> countries = new ConcurrentHashMap<>();
		final ChainClient client = clientOf(fleet, fleet.located(countries), fleet.pending(CLIENT), last());
		final List<String> paired = fleet.view().groups().members(1);
		final List<String> swedes = fleet.view().groups().members(0).subList(0, 3);

		fleet.activeIds().forEach(member -> countries.put(member, "NO"));
		paired.subList(2, 4).forEach(fleet::offline);
		paired.subList(0, 2).forEach(member -> countries.put(member, "SE"));
		swedes.forEach(member -> countries.put(member, "SE"));

		final ChainAttachment swedish = attached(fleet, client, Optional.of("SE"));

		assertThat(swedish.group(), is(0));
		assertThat(joinsTo(fleet, paired), is(0L));
		assertThat(drawnExits(swedish, Optional.of("SE"), Set.of()), everyItem(is(in(swedes))));
	}

	@Example
	public void failsWithoutAskingAGroupWhereTheAttachPairHoldsAllOfTheCountry() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(0, 1));
		final Map<String, String> countries = new ConcurrentHashMap<>();
		final ChainClient client = clientOf(fleet, fleet.located(countries), fleet.pending(CLIENT), last());
		final List<String> paired = fleet.view().groups().members(1);

		fleet.activeIds().forEach(member -> countries.put(member, "NO"));
		paired.subList(2, 4).forEach(fleet::offline);
		paired.subList(0, 2).forEach(member -> countries.put(member, "SE"));

		assertFailsWith(client.attachment(Optional.of("SE")),
			"No shard group has a gridnode in the country beside the pair this node would attach through");
		assertThat(delivered(fleet, JoinChain.class), is(0L));
	}

	/* The first group drawn refuses the node, and the attach pair holds all of the country in the other, drawn next:
	   a group that refused is why no group took the node, whatever the check found in the others */
	@Example
	public void saysNoGroupTookTheNodeWhenOneRefusedBeforeTheOthersWerePassedOver() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(1));
		final Map<String, String> countries = new ConcurrentHashMap<>();
		final ChainClient client = clientOf(fleet, fleet.located(countries), fleet.pending(CLIENT), drawing(0, 0));
		final List<String> refusing = fleet.view().groups().members(0);
		final List<String> paired = fleet.view().groups().members(1);

		fleet.activeIds().forEach(member -> countries.put(member, "NO"));
		refusing.subList(0, 3).forEach(member -> countries.put(member, "SE"));
		paired.subList(2, 4).forEach(fleet::offline);
		paired.subList(0, 2).forEach(member -> countries.put(member, "SE"));

		final CompletableFuture<ChainAttachment> swedish = client.attachment(Optional.of("SE"));

		fleet.deliver();
		assertFailsWith(swedish, "No shard group took this node");
		assertThat(joinsTo(fleet, refusing), greaterThan(0L));
		assertThat(joinsTo(fleet, paired), is(0L));
	}

	/* Every member of the last group is in Sweden when the node asks it, and none of them is by the time it took
	   the node, nor is any other gridnode: the attachment reaches no caller. It leaves its chain at the turn of the
	   epoch, as a join no session was ever handed has nobody else to release it. */
	@Example
	public void releasesAJoinItHandedToNoCallerOnceItIsNoLongerInUse() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(0, 1));
		final Map<String, String> countries = new ConcurrentHashMap<>();
		final ChainClient client = clientOf(fleet, fleet.located(countries), fleet.pending(CLIENT), last());
		final List<String> moved = fleet.view().groups().members(1);
		final List<ChainId> chains = fleet.view().chains(1);

		fleet.activeIds().forEach(member -> countries.put(member, "NO"));
		moved.forEach(member -> countries.put(member, "SE"));

		final CompletableFuture<ChainAttachment> swedish = client.attachment(Optional.of("SE"));

		moved.forEach(member -> countries.put(member, "NO"));
		fleet.deliver();

		final WriterId writer = writerJoining(fleet, moved);

		assertFailsWith(swedish, "No shard group has a gridnode in the country");
		assertThat(chains.stream().filter(chainId -> fleet.host(CLIENT).chain(chainId).isPresent()).count(), is(1L));

		fleet.clock.addAndGet(EPOCH_MILLIS);
		fleet.refreshAll();
		attached(fleet, client);

		final List<LeaveChain> left = fleet.deliveries.stream().filter(delivery -> delivery.from().equals(CLIENT))
			.map(ChainFleet.Delivery::packet).filter(LeaveChain.class::isInstance).map(LeaveChain.class::cast)
			.filter(leave -> leave.getWriter().equals(writer)).toList();

		assertThat(left, hasSize(2));
		assertThat(left.stream().map(LeaveChain::getChainId).distinct().toList(), hasSize(1));
		assertThat(chains, hasItem(left.get(0).getChainId()));
		assertThat(fleet.host(CLIENT).chain(left.get(0).getChainId()).isPresent(), is(false));
	}

	/* The writer of the join the node asked the given members for */
	private static WriterId writerJoining(final ChainFleet fleet, final List<String> members) {
		return fleet.deliveries.stream().filter(delivery -> members.contains(delivery.to()))
			.map(ChainFleet.Delivery::packet).filter(JoinChain.class::isInstance).map(JoinChain.class::cast)
			.map(JoinChain::getWriter).findFirst().orElseThrow();
	}

	/* The first group is held for any country, through the two members it has in Sweden. A join for Norway is
	   under way in the second group, which Sweden is drawn next and shares, but the second group refuses, and the
	   join ends in the first one too, through the same pair. Sweden is drawn once more and lands in the third. */
	@Example
	public void drawsOnWhenAJoinItSharedEndsInAGroupPassedOver() {
		final ChainFleet fleet = threeGroups(Set.of(0, 2));
		final Map<String, String> countries = new ConcurrentHashMap<>();
		final ChainClient client = clientOf(fleet, fleet.located(countries), fleet.pending(CLIENT),
			drawing(0, 2, 0, 0, 0, 1));
		final ChainAttachment any = attached(fleet, client, Optional.empty());
		final List<String> pair = fleet.attachMembers(CLIENT, any);
		final List<String> refusing = fleet.view().groups().members(1);
		final List<String> swedes = fleet.view().groups().members(2).subList(0, 3);

		fleet.activeIds().forEach(member -> countries.put(member, "NO"));
		pair.forEach(member -> countries.put(member, "SE"));
		countries.put(refusing.get(0), "SE");
		swedes.forEach(member -> countries.put(member, "SE"));
		countries.put(fleet.view().groups().members(2).get(3), "DK");

		final CompletableFuture<ChainAttachment> norwegian = client.attachment(Optional.of("NO"));
		final CompletableFuture<ChainAttachment> swedish = client.attachment(Optional.of("SE"));

		fleet.deliver();

		assertThat(List.of(any.group(), done(norwegian).group(), done(swedish).group()), equalTo(List.of(0, 0, 2)));
		assertThat(joinsTo(fleet, refusing), is(1L));
		assertThat(drawnExits(done(swedish), Optional.of("SE"), Set.of()), everyItem(is(in(swedes))));
	}

	/* No group is left once the only one with a member in the country has it at an attach member alone */
	@Example
	public void failsWhenTheCountryIsLeftOnlyAtAttachMembers() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(0, 1));
		final Map<String, String> countries = new ConcurrentHashMap<>();
		final ChainClient client = clientOf(fleet, fleet.located(countries), fleet.pending(CLIENT), last());
		final ChainAttachment any = attached(fleet, client, Optional.empty());
		final long joins = delivered(fleet, JoinChain.class);

		fleet.activeIds().forEach(member -> countries.put(member, "NO"));
		countries.put(fleet.attachMembers(CLIENT, any).get(0), "SE");

		assertFailsWith(client.attachment(Optional.of("SE")), "No shard group has a gridnode in the country");
		assertThat(delivered(fleet, JoinChain.class), is(joins));
		assertThat(client.isCurrent(any), is(true));
	}

	/* Iceland is only in the fifth group, which the node cannot join with four attachments held, and none of those
	   has a member there to fall back on: the country fails with the one draw that found the fifth group */
	@Example
	public void failsAtOnceWhenNoAttachmentHeldHasAMemberInTheCountry() {
		final ChainFleet fleet = fiveGroups();
		final Map<String, String> countries = new ConcurrentHashMap<>();
		final ChainClient client = clientOf(fleet, fleet.located(countries), fleet.pending(CLIENT),
			drawing(0, 0, 0, 0, 0));
		final List<String> named = List.of("SE", "NO", "DK", "FI", "IS");

		IntStream.range(0, 5).forEach(group -> fleet.view().groups().members(group)
			.forEach(member -> countries.put(member, named.get(group))));
		named.subList(0, 4).forEach(country -> attached(fleet, client, Optional.of(country)));

		assertFailsWith(client.attachment(Optional.of("IS")), "No shard group has a gridnode in the country");
		assertThat(joinsTo(fleet, fleet.view().groups().members(4)), is(0L));
	}

	@Example
	public void givesUpWhenEveryGroupRefuses() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(0, 1));

		fleet.activeIds().forEach(fleet::offline);

		assertThat(clientOf(fleet).attachment(Optional.empty()).isCompletedExceptionally(), is(true));
	}

	/* The second member admitted the writer all the same, and is told to leave */
	@Example
	public void staysWithOneMemberWhenTheSecondDoesNotAnswer() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainAttachment attachment = attached(fleet, clientOf(fleet,
			new Flaky(fleet.pending(CLIENT), Set.of(1)), new Random(1)));

		assertThat(attachment.streams().size(), is(1));
		assertThat(fleet.host(CLIENT).chain(attachment.chainId()).isPresent(), is(true));
		assertThat(joinsTo(fleet, fleet.activeIds()), is(2L));
		assertThat(attachedAt(fleet, attachment), is(1L));
	}

	/* A member asked to be the spare path learns the node's writer, and the stream tells it the node's address,
	   whether it answers or not, so it is never the exit */
	@Example
	public void neverChoosesTheSpareThatDidNotAnswerAsTheExit() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainAttachment attachment = attached(fleet, clientOf(fleet,
			new Flaky(fleet.pending(CLIENT), Set.of(1)), new Random(1)));
		final Set<String> asked = fleet.deliveries.stream().filter(delivery -> delivery.packet() instanceof JoinChain
			&& delivery.from().equals(CLIENT)).map(ChainFleet.Delivery::to).collect(Collectors.toSet());

		assertThat(attachment.streams().size(), is(1));
		assertThat(asked, hasSize(2));
		assertThat(drawnExits(attachment), equalTo(attachment.members().stream().filter(member -> !asked.contains(member))
			.collect(Collectors.toSet())));
	}

	@Example
	public void neverChoosesTheSpareThatRefusedAsTheExit() {
		final Scripted scripted = new Scripted(4, 4, new PendingRequests());

		scripted.ids.forEach(id -> scripted.answers.put(id, (member, request) -> List.of(Scripted.reply(request,
			request.getChainId().isPresent() ? FULL : JOINED, scripted.chainOf(member), Map.of()))));

		final ChainAttachment attachment = done(scripted.client().attachment(Optional.empty()));
		final List<String> asked = scripted.asked();

		assertThat(attachment.streams().size(), is(1));
		assertThat(asked, hasSize(2));
		assertThat(drawnExits(attachment), equalTo(scripted.ids.stream().filter(member -> !asked.contains(member))
			.collect(Collectors.toSet())));
	}

	private static Set<String> drawnExits(final ChainAttachment attachment) {
		return drawnExits(attachment, Optional.empty(), Set.of());
	}

	private static Set<String> drawnExits(final ChainAttachment attachment, final Optional<String> country,
		final Set<String> excluded) {

		return IntStream.range(0, 200).mapToObj(draw -> attachment.exit(country, excluded).orElseThrow().id())
			.collect(Collectors.toSet());
	}

	@Example
	public void keepsItsAttachmentWithinAnEpochAndJoinsAgainInTheNext() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainClient client = clientOf(fleet);
		final ChainAttachment first = attached(fleet, client);

		assertThat(done(client.attachment(Optional.empty())), sameInstance(first));
		assertThat(client.isCurrent(first), is(true));

		fleet.clock.addAndGet(EPOCH_MILLIS);
		fleet.refreshAll();

		assertThat(client.isCurrent(first), is(false));

		final ChainAttachment second = attached(fleet, client);

		assertThat(second.chainId(), not(equalTo(first.chainId())));
		assertThat(client.isCurrent(first), is(false));
		assertThat(client.isCurrent(second), is(true));
	}

	@Example
	public void joinsAgainWhenItsChainIsGone() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainClient client = clientOf(fleet);
		final ChainAttachment first = attached(fleet, client);

		fleet.host(CLIENT).unfollow(first.chainId());

		assertThat(attached(fleet, client), not(sameInstance(first)));
	}

	@Example
	public void joinsAgainThroughOpenStreamsWhenEveryStreamOfItsChainClosed() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainClient client = clientOf(fleet);
		final ChainAttachment first = attached(fleet, client);

		first.streams().forEach(Channel::close);

		final ChainAttachment second = attached(fleet, client);

		assertThat(client.isCurrent(first), is(false));
		assertThat(second.streams().size(), is(2));
		assertThat(second.streams().stream().map(Channel::isActive).toList(), everyItem(is(true)));
	}

	@Example
	public void neverJoinsTheGroupItHosts() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(0, 1));
		final String own = "gn-0";
		final int hosted = fleet.view().groups().groupOf(own).orElseThrow();
		final ChainClient client = new ChainClient(fleet.settings::get, fleet.directory(own), CountryLookup.NONE,
			fleet.transport(own), fleet.host(own), fleet.pending(own), fleet.clock::get, choosing(fleet, hosted),
			Runnable::run);

		assertThat(attached(fleet, client).group(), is(1 - hosted));
		assertThat(joinsTo(fleet, fleet.view().groups().members(hosted)), is(0L));
	}

	/* At the turn of an epoch the host may be a refresh behind, and the group it hosts then is another one */
	@Example
	public void passesOverItsOwnGroupOfTheEpochItJoinsIn() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(0, 1));
		final String own = "gn-0";
		final int stale = fleet.host(own).group().orElseThrow();
		final long epochs = LongStream.range(1, 64).filter(count -> new GroupView(fleet.settings.get()
			.orElseThrow(), fleet.clock.get() + count * EPOCH_MILLIS, fleet.activeIds()).groups().groupOf(own)
			.orElseThrow() != stale).findFirst().orElseThrow();

		fleet.clock.addAndGet(epochs * EPOCH_MILLIS);
		fleet.activeIds().stream().filter(id -> !id.equals(own)).forEach(id -> fleet.host(id).refresh());

		final int hosted = fleet.view().groups().groupOf(own).orElseThrow();
		final ChainClient client = new ChainClient(fleet.settings::get, fleet.directory(own), CountryLookup.NONE,
			fleet.transport(own), fleet.host(own), fleet.pending(own), fleet.clock::get, choosing(fleet, hosted),
			Runnable::run);

		assertThat(attached(fleet, client).group(), is(stale));
		assertThat(joinsTo(fleet, fleet.view().groups().members(hosted)), is(0L));
	}

	@Example
	public void failsWhileTheProxyIsOff() {
		final ChainFleet fleet = ChainFleet.group();

		fleet.settings.set(Optional.empty());
		assertThat(clientOf(fleet).attachment(Optional.empty()).isCompletedExceptionally(), is(true));
	}

	@Example
	public void dropsItsChainOnceItSeesTheProxyOffAndJoinsAgainWhenItIsBack() {
		final ChainFleet fleet = ChainFleet.group();
		final Optional<ProxySettings> on = fleet.settings.get();
		final ChainClient client = clientOf(fleet);
		final ChainAttachment held = attached(fleet, client);

		fleet.settings.set(Optional.empty());

		assertThat(client.isCurrent(held), is(false));
		assertThat(fleet.host(CLIENT).chain(held.chainId()), equalTo(Optional.empty()));

		fleet.settings.set(on);

		assertThat(attached(fleet, client), not(sameInstance(held)));
	}

	@Example
	public void joinsAgainAfterAJoinFailed() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainClient client = clientOf(fleet);

		fleet.activeIds().forEach(fleet::offline);
		assertThat(client.attachment(Optional.empty()).isCompletedExceptionally(), is(true));

		fleet.activeIds().forEach(fleet::online);
		assertThat(attached(fleet, client).streams().size(), is(2));
	}

	@Example
	public void sharesAJoinUnderWayAndKeepsEachCallerApart() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainClient client = clientOf(fleet);
		final CompletableFuture<ChainAttachment> one = client.attachment(Optional.empty());
		final CompletableFuture<ChainAttachment> other = client.attachment(Optional.empty());

		one.cancel(true);
		fleet.deliver();

		assertThat(done(other), sameInstance(done(client.attachment(Optional.empty()))));
		assertThat(delivered(fleet, JoinChain.class), is(2L));
	}

	@Example
	public void writesWithAFreshWriterOnEveryAttachment() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainClient client = clientOf(fleet);
		final ChainAttachment first = attached(fleet, client);

		client.release(first);

		final ChainAttachment next = attached(fleet, client);
		final ChainAttachment ofAnotherNode = attached(fleet, clientOf(fleet));

		assertThat(next.writerId(), not(equalTo(first.writerId())));
		assertThat(ofAnotherNode.writerId(), not(equalTo(first.writerId())));
	}

	@Example
	public void leavesTheChainWhenAnAttachmentIsReleased() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainClient client = clientOf(fleet);
		final ChainAttachment attachment = attached(fleet, client);

		client.release(attachment);
		fleet.deliver();

		assertThat(fleet.host(CLIENT).chain(attachment.chainId()), equalTo(Optional.empty()));

		for (final String member : fleet.activeIds()) {
			assertThat(fleet.host(member).hosted(attachment.chainId()).orElseThrow()
				.isAttached(attachment.writerId()), is(false));
		}

		assertThat(attached(fleet, client), not(sameInstance(attachment)));
	}

	@Example
	public void releasesAnAttachmentOnce() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.settings(ChainFleet.MEMBERS, 1, 16, 30, 3));
		final ChainClient client = clientOf(fleet);
		final ChainAttachment first = attached(fleet, client);

		client.release(first);

		final ChainAttachment second = attached(fleet, client);

		client.release(first);
		client.release(null);
		fleet.deliver();

		assertThat(second.chainId(), equalTo(first.chainId()));
		assertThat(delivered(fleet, LeaveChain.class), is(2L));
		assertThat(client.isCurrent(second), is(true));
	}

	/* The writer of the earlier attachment leaves all the same, as it holds a place on the stream */
	@Example
	public void keepsFollowingAChainItJoinedAgainWhenTheEarlierAttachmentIsReleased() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.settings(ChainFleet.MEMBERS, 1, 16, 30, 3));
		final ChainClient client = clientOf(fleet);
		final ChainAttachment lost = attached(fleet, client);

		fleet.host(CLIENT).unfollow(lost.chainId());

		final ChainAttachment again = attached(fleet, client);

		client.release(lost);
		fleet.deliver();

		assertThat(again.chainId(), equalTo(lost.chainId()));
		assertThat(client.isCurrent(again), is(true));
		assertThat(attachedAt(fleet, lost), is(0L));
		assertThat(attachedAt(fleet, again), is(2L));
	}

	/* A stream holds a few writers and a node follows a few chains, so a client whose streams outlive many epochs
	   joins in every one of them only because it leaves the retired chains */
	@Example
	public void leavesTheRetiredChainsSoThatItsStreamsTakeAWriterInEveryEpoch() {
		final ChainFleet fleet = new ChainFleet(2);

		fleet.settings.set(Optional.of(ChainFleet.settings(2, 1, 16, 30, 3)));
		fleet.host("gn-0");
		fleet.host("gn-1");
		fleet.refreshAll();

		final ChainClient client = clientOf(fleet);
		ChainAttachment held = attached(fleet, client);
		ChainAttachment released = held;

		for (int epoch = 1; epoch <= ChainHost.MAX_WRITERS_PER_STREAM + 1; epoch++) {
			fleet.clock.addAndGet(EPOCH_MILLIS);
			fleet.refreshAll();

			final ChainAttachment next = attached(fleet, client);

			client.release(held);
			fleet.deliver();

			assertThat(next.streams().size(), is(2));
			released = held;
			held = next;
		}

		assertThat(fleet.host("gn-0").retiredChains(), hasItem(released.chainId()));
		assertThat(attachedAt(fleet, released), is(0L));
	}

	/* Every draw takes a member at random, connected or not, but never one the node attached through, as those see
	   its address; and it hands over the key the member announced */
	@Example
	public void choosesTheExitAtRandomAmongTheMembersOtherThanTheAttachOnes() {
		final ChainFleet fleet = ChainFleet.group();
		final String unconnected = "gn-3";

		fleet.offline(unconnected);

		final ChainAttachment attachment = attached(fleet, clientOf(fleet));
		final List<String> attachMembers = fleet.attachMembers(CLIENT, attachment);
		final List<ExitCandidate> drawn = IntStream.range(0, 200).mapToObj(draw -> attachment.exit(Optional.empty(),
			Set.of()).orElseThrow()).toList();

		assertThat(attachment.members(), hasItem(unconnected));
		assertThat(attachMembers, hasSize(2));
		assertThat(drawn.stream().map(ExitCandidate::id).collect(Collectors.toSet()),
			equalTo(attachment.members().stream().filter(member -> !attachMembers.contains(member))
			.collect(Collectors.toSet())));
		drawn.forEach(exit -> assertThat(exit.sealingKey(), equalTo(ChainFleet.sealingKey(exit.id()).publicKey()
			.orElseThrow())));
	}

	/* Only a member in the country asked for is drawn, never an attach member there, and none for a country no
	   member left is in */
	@Example
	public void choosesTheExitAmongTheMembersInTheAskedCountry() {
		final ChainFleet fleet = ChainFleet.group();
		final Map<String, String> countries = new ConcurrentHashMap<>();
		final ChainAttachment attachment = attached(fleet, clientOf(fleet, fleet.located(countries)));
		final List<String> attachMembers = fleet.attachMembers(CLIENT, attachment);
		final List<String> others = attachment.members().stream().filter(member -> !attachMembers.contains(member))
			.toList();

		attachment.members().forEach(member -> countries.put(member, "SE"));
		countries.put(others.get(1), "NO");

		assertThat(drawnExits(attachment, Optional.of("SE"), Set.of()), equalTo(Set.of(others.get(0))));
		assertThat(drawnExits(attachment, Optional.of("NO"), Set.of()), equalTo(Set.of(others.get(1))));
		assertThat(drawnExits(attachment, Optional.empty(), Set.of()), equalTo(Set.copyOf(others)));
		assertThat(attachment.exit(Optional.of("SE"), Set.of(others.get(0))), equalTo(Optional.empty()));
		assertThat(attachment.exit(Optional.of("DK"), Set.of()), equalTo(Optional.empty()));
	}

	/* Of a group of eight, the node attaches through two and passes over three more */
	@Example
	public void passesOverMembersThatAreExcludedInactiveOrWithoutASealingKey() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.settings(8, 4, 16, 30, 3));

		IntStream.range(ChainFleet.MEMBERS, 8).forEach(number -> {
			fleet.gridnodes.add(ChainFleet.gridnode("gn-" + number, Gridnode.Status.ACTIVE));
			fleet.host("gn-" + number);
		});
		fleet.refreshAll();

		final ChainAttachment attachment = attached(fleet, clientOf(fleet));
		final List<String> attachMembers = fleet.attachMembers(CLIENT, attachment);
		final List<String> others = attachment.members().stream().filter(member -> !attachMembers.contains(member))
			.toList();

		fleet.gridnodes.replaceAll(gridnode -> gridnode.getId().equals(others.get(1))
			? ChainFleet.gridnode(others.get(1), Gridnode.Status.INACTIVE) : gridnode);
		fleet.gridnodes.stream().filter(gridnode -> gridnode.getId().equals(others.get(2)))
			.forEach(gridnode -> gridnode.setSealingKey(null));

		for (int draw = 0; draw < 50; draw++) {
			assertThat(attachment.exit(Optional.empty(), Set.of(others.get(3), others.get(4), others.get(5)))
				.map(ExitCandidate::id), equalTo(Optional.of(others.get(0))));
		}

		assertThat(attachment.exit(Optional.empty(), Set.of(others.get(0), others.get(3), others.get(4),
			others.get(5))), equalTo(Optional.empty()));
	}

	@Example
	public void followsOnTheThreadThatCompletesEachReply() throws Exception {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final BlockingQueue<Written> waiting = new LinkedBlockingQueue<>();
		final List<ExecutorService> threads = List.of(named("joins"), named("first-loop"), named("second-loop"));

		scripted.ids.forEach(id -> scripted.answers.put(id, (member, request) -> {
			waiting.add(new Written(member, request));
			return List.of();
		}));

		try {
			final CompletableFuture<ChainAttachment> attachment = scripted.client(new Random(1), threads.get(0))
				.attachment(Optional.empty());

			for (int member = 1; member <= 2; member++) {
				final int followsBefore = member - 1;

				assertThat(threads.get(member).submit(() -> scripted.answer(waiting.poll(TIMEOUT_SECONDS,
					TimeUnit.SECONDS)) - followsBefore).get(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(1));
			}

			assertThat(attachment.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).streams().size(), is(2));
			assertThat(scripted.follows().stream().map(Followed::thread).toList(), contains("first-loop",
				"second-loop"));
		} finally {
			threads.forEach(ExecutorService::shutdownNow);
		}
	}

	@Example
	public void looksStreamsUpOnItsExecutorAndNeverOnAThreadThatAnswers() throws Exception {
		final Scripted scripted = new Scripted(4, 2, new PendingRequests());
		final List<String> refusing = scripted.view().groups().members(0);
		final BlockingQueue<Written> waiting = new LinkedBlockingQueue<>();
		final Set<String> lookups = ConcurrentHashMap.newKeySet();
		final ExecutorService joins = named("joins");
		final ExecutorService loop = named("loop");

		refusing.forEach(id -> scripted.answers.put(id, (member, request) -> {
			waiting.add(new Written(member, request));
			return List.of();
		}));

		scripted.transport = id -> {
			lookups.add(Thread.currentThread().getName());
			return Optional.of(scripted.streams.get(id));
		};

		try {
			final CompletableFuture<ChainAttachment> attachment = scripted.client(choosing(scripted.view(), 0),
				joins).attachment(Optional.empty());

			loop.submit(() -> {
				final Written asked = waiting.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);

				scripted.pending.complete(scripted.streams.get(asked.member()),
					Scripted.reply((JoinChain) asked.packet(), JoinReply.Status.FULL, null, Map.of()));
				return asked;
			}).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

			assertThat(attachment.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).group(), is(1));
			assertThat(lookups, equalTo(Set.of("joins")));
		} finally {
			Stream.of(joins, loop).forEach(ExecutorService::shutdownNow);
		}
	}

	@Example
	public void followsBeforeTheNextPacketOfTheStreamIsTakenIn() {
		final Scripted scripted = new Scripted(1, 1, new PendingRequests());
		final ChainId chainId = scripted.chainOf("gn-0");
		final List<Entry> feed = ChainFleet.entries(ChainFleet.key(9), chainId, 6, ChainFleet.NOW);

		scripted.answers.put("gn-0", (member, request) -> List.of(Scripted.reply(request, JOINED, chainId,
			Map.of(ChainFleet.writer(9), new Head(4, feed.get(4).hash()))),
			ChainEntry.builder().entries(List.of(feed.get(5))).build()));

		done(scripted.client().attachment(Optional.empty()));

		assertThat(scripted.host.chain(chainId).orElseThrow().heads().get(ChainFleet.writer(9)),
			equalTo(new Head(5, feed.get(5).hash())));
	}

	@Example
	public void asksTheSecondMemberOnlyOnceTheFirstIsFollowed() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final ChainAttachment attachment = done(scripted.client().attachment(Optional.empty()));
		final String first = scripted.memberOf(attachment.streams().get(0));
		final String second = scripted.memberOf(attachment.streams().get(1));

		assertThat(scripted.order(), contains("ask " + first, "follow " + first, "ask " + second,
			"follow " + second));
	}

	@Example
	public void refusesASecondMemberThatNamesAnotherChain() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final ChainId other = chainId(99);

		scripted.ids.forEach(id -> scripted.answers.put(id, (member, request) -> List.of(Scripted.reply(request,
			JOINED, request.getChainId().isPresent() ? other : scripted.chainOf(member), Map.of()))));

		final ChainAttachment attachment = done(scripted.client().attachment(Optional.empty()));
		final String second = scripted.asked().get(1);

		assertThat(attachment.streams().size(), is(1));
		assertThat(scripted.follows().stream().map(Followed::chainId).toList(), contains(attachment.chainId()));
		assertThat(scripted.host.chain(other), equalTo(Optional.empty()));
		assertThat(scripted.leaves(second), contains(leave(attachment.chainId(), attachment.writerId())));
	}

	@Property(tries = 3)
	public void anchorsEveryHeadOfTheReplies(@ForAll("headCounts") final int count) {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final Map<WriterId, Head> heads = IntStream.range(0, count).boxed()
			.collect(Collectors.toMap(ChainFleet::writer, number -> new Head(3, number)));

		scripted.ids.forEach(id -> scripted.answers.put(id, scripted.joined(heads)));

		final ChainAttachment attachment = done(scripted.client().attachment(Optional.empty()));

		assertThat(attachment.streams().size(), is(2));
		assertThat(scripted.host.chain(attachment.chainId()).orElseThrow().heads(), equalTo(heads));
	}

	@Example
	public void joinsWhateverHeadsAMemberNames() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final Map<WriterId, Head> hostile = new HashMap<>();

		hostile.put(null, new Head(3, 1));
		hostile.put(ChainFleet.writer(1), null);
		hostile.put(ChainFleet.writer(2), new Head(Long.MAX_VALUE, 2));
		hostile.put(ChainFleet.writer(3), new Head(3, 3));

		scripted.ids.forEach(id -> scripted.answers.put(id, (member, request) -> {
			final JoinReply reply = Scripted.reply(request, JOINED, scripted.chainOf(member), hostile);

			reply.setHeads(request.getChainId().isPresent() ? null : hostile);
			return List.of(reply);
		}));

		final ChainAttachment attachment = done(scripted.client().attachment(Optional.empty()));

		assertThat(attachment.streams().size(), is(2));
		assertThat(scripted.host.chain(attachment.chainId()).orElseThrow().heads(),
			equalTo(Map.of(ChainFleet.writer(3), new Head(3, 3))));
	}

	@Example
	public void takesNoHeadAMemberNamesForTheWriterOfTheNode() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());

		scripted.ids.forEach(id -> scripted.answers.put(id, (member, request) -> List.of(Scripted.reply(request,
			JOINED, scripted.chainOf(member), Map.of(request.getWriter(), new Head(5, 1), ChainFleet.writer(1),
			new Head(3, 1))))));

		final ChainAttachment attachment = done(scripted.client().attachment(Optional.empty()));

		assertThat(scripted.host.chain(attachment.chainId()).orElseThrow().heads(),
			equalTo(Map.of(ChainFleet.writer(1), new Head(3, 1))));
	}

	@Example
	public void refusesAGroupWhoseFirstMemberItCannotFollow() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());

		scripted.afterFollow = () -> {
			throw new IllegalStateException("A follow that fails");
		};

		final CompletableFuture<ChainAttachment> attachment = scripted.client().attachment(Optional.empty());
		final String first = scripted.asked().get(0);
		final ChainId chainId = scripted.chainOf(first);

		assertThat(attachment.isCompletedExceptionally(), is(true));
		assertThat(scripted.asked(), contains(first));
		assertThat(scripted.leaves(first), contains(leave(chainId, writerAsked(scripted))));
		assertThat(scripted.host.chain(chainId), equalTo(Optional.empty()));
	}

	@Example
	public void staysWithTheFirstMemberWhenItCannotFollowTheSecond() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final AtomicInteger follows = new AtomicInteger();

		scripted.afterFollow = () -> {
			if (follows.incrementAndGet() == 2) {
				throw new IllegalStateException("A follow that fails");
			}
		};

		final ChainAttachment attachment = done(scripted.client().attachment(Optional.empty()));
		final String second = scripted.asked().get(1);

		assertThat(attachment.streams().size(), is(1));
		assertThat(scripted.leaves(second), contains(leave(attachment.chainId(), attachment.writerId())));
		assertThat(scripted.host.chain(attachment.chainId()).isPresent(), is(true));
	}

	@Example
	public void passesOverEveryGroupWhileTheNodeFollowsNoMoreChains() {
		final Scripted scripted = new Scripted(4, 2, new PendingRequests());

		IntStream.range(1, ChainHost.MAX_FOLLOWED + 1).forEach(number -> scripted.host.follow(chainId(number),
			new EmbeddedChannel(), Map.of()));

		final CompletableFuture<ChainAttachment> attachment = scripted.client().attachment(Optional.empty());

		assertThat(attachment.isCompletedExceptionally(), is(true));
		assertThat(scripted.asked().size(), is(2));
		assertThat(scripted.written(LeaveChain.class).stream().map(Written::member).toList(),
			equalTo(scripted.asked()));
	}

	@Property
	public void passesOverAGroupThatAnswersAnythingButJoined(@ForAll final JoinReply.Status status) {
		Assume.that(status != JOINED);

		final Scripted scripted = new Scripted(4, 2, new PendingRequests());
		final List<String> refusing = scripted.view().groups().members(0);

		refusing.forEach(id -> scripted.answers.put(id, (member, request) -> List.of(Scripted.reply(request, status,
			scripted.chainOf(member), Map.of()))));

		final ChainAttachment attachment = done(scripted.client(choosing(scripted.view(), 0), Runnable::run)
			.attachment(Optional.empty()));

		assertThat(attachment.group(), is(1));
		assertThat(scripted.asked().stream().filter(refusing::contains).count(), is(1L));
		assertThat(scripted.host.chain(scripted.chainOf(refusing.get(0))), equalTo(Optional.empty()));
	}

	@Example
	public void refusesAGroupThatAnswersWithNoReplyAtAll() {
		final Scripted scripted = new Scripted(1, 1, new PendingRequests() {
			@Override
			public <T extends Correlated> CompletableFuture<T> register(final long requestId,
				final Channel channel, final Class<T> type) {

				return CompletableFuture.completedFuture(null);
			}
		});

		assertThat(scripted.client().attachment(Optional.empty()).isCompletedExceptionally(), is(true));
	}

	@Example
	public void refusesAGroupAtOnceWhenItsRequestCannotBeWritten() {
		final Scripted scripted = new Scripted(1, 1, new PendingRequests());

		scripted.unwritable.add("gn-0");

		assertThat(scripted.client().attachment(Optional.empty()).isCompletedExceptionally(), is(true));
	}

	@Property(tries = 5)
	public void attachesToTwoOfTheMembersItHoldsAnOpenStreamTo(@ForAll @IntRange(max = 4) final int open) {
		final Scripted scripted = new Scripted(4, 4, new PendingRequests());

		scripted.ids.subList(open, 4).forEach(id -> scripted.streams.get(id).close());

		final int streams = done(scripted.client().attachment(Optional.empty()).handle((attachment, error) ->
			attachment == null ? 0 : attachment.streams().size()));

		assertThat(streams, is(Math.min(open, 2)));
		assertThat(scripted.asked().size(), is(Math.min(open, 2)));
		assertThat(scripted.asked(), everyItem(not(in(scripted.ids.subList(open, 4)))));
	}

	@Example
	public void asksNoSecondMemberOnTheStreamOfTheFirst() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final EmbeddedChannel shared = scripted.streams.get("gn-0");

		scripted.transport = id -> Optional.of(shared);

		assertThat(done(scripted.client().attachment(Optional.empty())).streams(), contains(shared));
		assertThat(scripted.asked(), contains("gn-0"));
	}

	@Example
	public void asksAnyMemberItHoldsAnOpenStreamToFirst() {
		final List<String> first = IntStream.range(0, 64).mapToObj(ChainClientTest::firstAsked).toList();

		assertThat(Set.copyOf(first), equalTo(Set.of("gn-0", "gn-1", "gn-2")));
		assertThat(IntStream.range(0, 64).mapToObj(ChainClientTest::firstAsked).toList(), equalTo(first));
	}

	@Example
	public void passesOverAMemberWhoseStreamCannotBeLookedUp() {
		final Scripted scripted = new Scripted(3, 3, new PendingRequests());

		scripted.transport = id -> switch (id) {
			case "gn-0" -> throw new IllegalStateException("A lookup that fails");
			case "gn-1" -> null;
			default -> Optional.of(scripted.streams.get(id));
		};

		assertThat(done(scripted.client().attachment(Optional.empty())).streams(),
			contains(scripted.streams.get("gn-2")));
	}

	@Property(tries = 5)
	public void givesUpOnceEveryGroupRefusedIt(@ForAll @IntRange(max = 4) final int groups) {
		final Scripted scripted = new Scripted(groups, 1, new PendingRequests());

		scripted.ids.forEach(id -> scripted.answers.put(id, Scripted.refusing(JoinReply.Status.DISABLED)));

		assertThat(scripted.client().attachment(Optional.empty()).isCompletedExceptionally(), is(true));
		assertThat(scripted.asked().size(), is(groups));
		assertThat(Set.copyOf(scripted.asked()), equalTo(Set.copyOf(scripted.ids)));
	}

	@Example
	public void failsItsJoinWhenTheSettingsCannotBeRead() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final ChainClient client = scripted.client();
		final ChainAttachment held = done(client.attachment(Optional.empty()));

		scripted.source = () -> {
			throw new IllegalStateException("Settings that cannot be read");
		};

		assertThat(client.isCurrent(held), is(false));
		assertThat(client.attachment(Optional.empty()).isCompletedExceptionally(), is(true));
	}

	@Example
	public void logsTheClassOfSettingsThatCannotBeReadAndNothingMoreOfIt() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final ChainClient client = scripted.client();
		final ChainAttachment held = done(client.attachment(Optional.empty()));

		scripted.source = () -> {
			throw new IllegalStateException(FAILURE_DETAIL);
		};

		final List<ILoggingEvent> warnings = warningsWhile(() -> client.isCurrent(held));

		assertThat(warnings, hasSize(1));
		assertThat(warnings.get(0).getFormattedMessage(), containsString(IllegalStateException.class.getName()));
		assertThat(warnings.get(0).getFormattedMessage(), not(containsString(FAILURE_DETAIL)));
		assertThat(warnings.get(0).getThrowableProxy(), is(nullValue()));
	}

	/* A chain that cannot be looked up leaves its attachment out of use */
	@Example
	public void logsTheClassOfAChainThatCannotBeLookedUpAndNothingMoreOfIt() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final ChainClient client = scripted.client();
		final ChainAttachment held = done(client.attachment(Optional.empty()));

		scripted.chainFails = true;

		final List<ILoggingEvent> warnings = warningsWhile(() -> assertThat(client.isCurrent(held), is(false)));

		assertThat(warnings, hasSize(1));
		assertThat(warnings.get(0).getFormattedMessage(), containsString(IllegalStateException.class.getName()));
		assertThat(warnings.get(0).getFormattedMessage(), not(containsString(FAILURE_DETAIL)));
		assertThat(warnings.get(0).getThrowableProxy(), is(nullValue()));
	}

	@Example
	public void neitherJoinsNorKeepsAnAttachmentWhileTheClockReadsBeforeTheOrigin() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final ChainClient client = scripted.client();
		final ChainAttachment held = done(client.attachment(Optional.empty()));

		scripted.clock.set(-1);

		assertThat(client.isCurrent(held), is(false));
		assertThat(client.attachment(Optional.empty()).isCompletedExceptionally(), is(true));
	}

	/* One member, so that no second follow brings the chain back */
	@Example
	public void keepsFollowingAChainAJoinClaimedWhileAnEarlierAttachmentOnItWasReleased() {
		final Scripted scripted = new Scripted(1, 1, new PendingRequests());
		final ChainClient client = scripted.client();
		final ChainAttachment lost = done(client.attachment(Optional.empty()));

		scripted.host.unfollow(lost.chainId());
		scripted.afterFollow = once(() -> client.release(lost));

		final ChainAttachment again = done(client.attachment(Optional.empty()));

		assertThat(again.chainId(), equalTo(lost.chainId()));
		assertThat(client.isCurrent(again), is(true));
		assertThat(scripted.leaves(scripted.memberOf(lost.streams().get(0))), hasItem(leave(lost.chainId(),
			lost.writerId())));
	}

	@Example
	public void joinsWhenTheDirectoryListsAGridnodeWithoutAnId() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());

		scripted.listed.add(Gridnode.builder().status(Gridnode.Status.ACTIVE).hostName("nameless:1").build());

		assertThat(done(scripted.client().attachment(Optional.empty())).streams().size(), is(2));
	}

	@Example
	public void startsOneJoinForCallersOnManyThreads() throws Exception {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final ChainClient client = scripted.client();
		final CountDownLatch start = new CountDownLatch(1);
		final ExecutorService callers = Executors.newFixedThreadPool(8);

		scripted.ids.forEach(id -> scripted.answers.put(id, (member, request) -> List.of()));

		try {
			final List<Future<CompletableFuture<ChainAttachment>>> calls = IntStream.range(0, 8)
				.mapToObj(caller -> callers.submit(() -> {
					start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
					return client.attachment(Optional.empty());
				})).toList();

			start.countDown();

			for (final Future<CompletableFuture<ChainAttachment>> call : calls) {
				call.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
		} finally {
			callers.shutdownNow();
		}

		assertThat(scripted.asked().size(), is(1));
	}

	/* What a reply sets going never waits for the client, which a caller may hold */
	@Example
	public void completesAJoinWhileACallerHoldsTheClient() throws Exception {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final BlockingQueue<Written> waiting = new LinkedBlockingQueue<>();
		final ChainClient client = scripted.client();
		final ExecutorService loop = named("loop");

		scripted.ids.forEach(id -> scripted.answers.put(id, scripted.holdingTheFirstIn(waiting)));

		final CompletableFuture<ChainAttachment> attachment = client.attachment(Optional.empty());

		try {
			synchronized (client) {
				loop.submit(() -> scripted.answer(waiting.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)))
					.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
		} finally {
			loop.shutdownNow();
		}

		assertThat(done(attachment).streams().size(), is(2));
	}

	/* The earlier writer's claim keeps the chain followed, but the attachment that was released is not handed out */
	@Example
	public void joinsAfreshWhenTheCurrentAttachmentWasReleasedWhileAnOlderOneKeepsItsChain() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.settings(ChainFleet.MEMBERS, 1, 16, 30, 3));
		final ChainClient client = clientOf(fleet);
		final ChainAttachment lost = attached(fleet, client);

		fleet.host(CLIENT).unfollow(lost.chainId());

		final ChainAttachment again = attached(fleet, client);

		client.release(again);
		fleet.deliver();

		final ChainAttachment fresh = attached(fleet, client);

		assertThat(client.isCurrent(again), is(false));
		assertThat(client.isCurrent(fresh), is(true));
		assertThat(fresh.writerId(), not(equalTo(again.writerId())));
		assertThat(fresh.writerId(), not(equalTo(lost.writerId())));
	}

	@Example
	public void refusesAFirstMemberThatNamesAChainOutsideItsGroup() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final ChainId outside = chainId(99);

		scripted.ids.forEach(id -> scripted.answers.put(id, (member, request) -> List.of(Scripted.reply(request,
			JOINED, outside, Map.of()))));

		final CompletableFuture<ChainAttachment> attachment = scripted.client().attachment(Optional.empty());

		assertThat(attachment.isCompletedExceptionally(), is(true));
		assertThat(scripted.follows().size(), is(0));
		assertThat(scripted.host.chain(outside), equalTo(Optional.empty()));
		assertThat(scripted.leaves(scripted.asked().get(0)),
			contains(leave(outside, writerAsked(scripted))));
	}

	/* A reply is completed on the event loop of its stream, which a caller's work must not run on */
	@Example
	public void runsWhatACallerChainsOnAThreadOfTheJoinExecutorAndNotOnTheLoopThatAnswered() throws Exception {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final BlockingQueue<Written> waiting = new LinkedBlockingQueue<>();
		final ExecutorService joins = named("joins");
		final ExecutorService loop = named("loop");

		scripted.ids.forEach(id -> scripted.answers.put(id, scripted.holdingTheFirstIn(waiting)));

		try {
			final CompletableFuture<String> thread = scripted.client(new Random(1), joins)
				.attachment(Optional.empty()).thenApply(attachment -> Thread.currentThread().getName());

			loop.submit(() -> scripted.answer(waiting.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)))
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

			assertThat(thread.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), is("joins"));
		} finally {
			joins.shutdownNow();
			loop.shutdownNow();
		}
	}

	/* The joins of a client built without an executor run on daemon threads of their own, which a lookup may block */
	@Example
	public void joinsOnDaemonThreadsOfItsOwnWhenNoExecutorIsGiven() throws Exception {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final ChainClient client = new ChainClient(() -> scripted.source.current(), scripted.directory(),
			CountryLookup.NONE, id -> scripted.transport.streamTo(id), scripted.host, scripted.pending,
			scripted.clock::get, new Random(1));

		final Thread thread = client.attachment(Optional.empty()).thenApply(attachment -> Thread.currentThread())
			.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		assertThat(thread.getName(), is("chain-client-join"));
		assertThat(thread.isDaemon(), is(true));
	}

	@Example
	public void anchorsTheFeedsAtTheHeadsOfBothRepliesWhenTheyDiffer() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final Map<WriterId, Head> ofTheFirst = Map.of(ChainFleet.writer(1), new Head(3, 1));
		final Map<WriterId, Head> ofTheSecond = Map.of(ChainFleet.writer(2), new Head(3, 2));

		scripted.ids.forEach(id -> scripted.answers.put(id, (member, request) -> List.of(Scripted.reply(request,
			JOINED, request.getChainId().orElse(scripted.chainOf(member)),
			request.getChainId().isPresent() ? ofTheSecond : ofTheFirst))));

		final ChainAttachment attachment = done(scripted.client().attachment(Optional.empty()));

		assertThat(attachment.streams().size(), is(2));
		assertThat(scripted.host.chain(attachment.chainId()).orElseThrow().heads(),
			equalTo(Map.of(ChainFleet.writer(1), new Head(3, 1), ChainFleet.writer(2), new Head(3, 2))));
	}

	/* The cause is told, not only the failure: it is what the owner of the future reports to its client */
	private static void assertFailsWith(final CompletableFuture<?> future, final String reason) {
		assertThat(future.isCompletedExceptionally(), is(true));

		final Throwable cause = Expect.assertThrows(CompletionException.class, future::join).getCause();

		assertThat(cause, instanceOf(IllegalStateException.class));
		assertThat(cause.getMessage(), is(reason));
	}

	/* Without a GeoIP database no gridnode is anywhere, and with one none of them may be in the country */
	@Example
	public void saysWhenNoGroupHasAGridnodeInTheCountry() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(0, 1));
		final Map<String, String> countries = new ConcurrentHashMap<>();
		final ChainClient located = clientOf(fleet, fleet.located(countries));

		fleet.activeIds().forEach(member -> countries.put(member, "SE"));

		assertFailsWith(located.attachment(Optional.of("DK")), "No shard group has a gridnode in the country");
		assertFailsWith(clientOf(fleet).attachment(Optional.of("SE")), "No shard group has a gridnode in the country");
		assertThat(delivered(fleet, JoinChain.class), is(0L));
		assertThat(attached(fleet, located, Optional.of("SE")).streams().size(), is(2));
	}

	@Example
	public void saysWhyAJoinFailed() {
		final Scripted refused = new Scripted(2, 1, new PendingRequests());
		final Scripted off = new Scripted(2, 2, new PendingRequests());

		refused.ids.forEach(id -> refused.answers.put(id, Scripted.refusing(JoinReply.Status.DISABLED)));
		off.settings.set(Optional.empty());

		assertFailsWith(refused.client().attachment(Optional.empty()), "No shard group took this node");
		assertFailsWith(off.client().attachment(Optional.empty()), "The proxy is off");
	}
}
