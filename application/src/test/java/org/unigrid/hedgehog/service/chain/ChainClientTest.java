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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.jqwik.Expect;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.Correlated;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.group.ExitChoice;
import org.unigrid.hedgehog.proxy.group.GroupView;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

public class ChainClientTest {
	private static final String CLIENT = "client";
	private static final long EPOCH_MILLIS = 3_600_000;
	private static final int TIMEOUT_SECONDS = 10;
	private static final JoinReply.Status JOINED = JoinReply.Status.JOINED;

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
			return new ChainClient(() -> source.current(), directory(), id -> transport.streamTo(id), host,
				pending, clock::get, random, joins);
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

	private static ChainClient clientOf(final ChainFleet fleet, final PendingRequests pending, final Random random) {
		return new ChainClient(fleet.settings::get, fleet.directory(CLIENT), fleet.transport(CLIENT),
			fleet.host(CLIENT), pending, fleet.clock::get, random, Runnable::run);
	}

	private static ChainClient clientOf(final ChainFleet fleet) {
		return clientOf(fleet, fleet.pending(CLIENT), new Random(1));
	}

	private static ChainAttachment attached(final ChainFleet fleet, final ChainClient client) {
		final CompletableFuture<ChainAttachment> attachment = client.attachment();

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
		final ChainFleet fleet = new ChainFleet(8);

		fleet.settings.set(Optional.of(ChainFleet.settings(4, chainsPerGroup, clientsPerChain, 30, 3)));
		IntStream.range(0, 8).forEach(number -> fleet.host("gn-" + number));
		onDuty.forEach(group -> fleet.view().groups().members(group)
			.forEach(member -> fleet.host(member).refresh()));
		return fleet;
	}

	/* A random generator whose first choice of a group is the given one */
	static Random choosing(final ChainFleet fleet, final int group) {
		return choosing(fleet.view(), group);
	}

	static Random choosing(final GroupView view, final int group) {
		final int seed = IntStream.iterate(0, next -> next + 1)
			.filter(next -> view.joinCandidate(new Random(next), Set.of()).getAsInt() == group)
			.findFirst().orElseThrow();

		return new Random(seed);
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
		scripted.client(new Random(seed), Runnable::run).attachment();
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

	@Example
	public void givesUpWhenEveryGroupRefuses() {
		final ChainFleet fleet = twoGroups(16, 4, Set.of(0, 1));

		fleet.activeIds().forEach(fleet::offline);

		assertThat(clientOf(fleet).attachment().isCompletedExceptionally(), is(true));
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

	@Example
	public void keepsItsAttachmentWithinAnEpochAndJoinsAgainInTheNext() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainClient client = clientOf(fleet);
		final ChainAttachment first = attached(fleet, client);

		assertThat(done(client.attachment()), sameInstance(first));
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
		final ChainClient client = new ChainClient(fleet.settings::get, fleet.directory(own), fleet.transport(own),
			fleet.host(own), fleet.pending(own), fleet.clock::get, choosing(fleet, hosted), Runnable::run);

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
		final ChainClient client = new ChainClient(fleet.settings::get, fleet.directory(own), fleet.transport(own),
			fleet.host(own), fleet.pending(own), fleet.clock::get, choosing(fleet, hosted), Runnable::run);

		assertThat(attached(fleet, client).group(), is(stale));
		assertThat(joinsTo(fleet, fleet.view().groups().members(hosted)), is(0L));
	}

	@Example
	public void failsWhileTheProxyIsOff() {
		final ChainFleet fleet = ChainFleet.group();

		fleet.settings.set(Optional.empty());
		assertThat(clientOf(fleet).attachment().isCompletedExceptionally(), is(true));
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
		assertThat(client.attachment().isCompletedExceptionally(), is(true));

		fleet.activeIds().forEach(fleet::online);
		assertThat(attached(fleet, client).streams().size(), is(2));
	}

	@Example
	public void sharesAJoinUnderWayAndKeepsEachCallerApart() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainClient client = clientOf(fleet);
		final CompletableFuture<ChainAttachment> one = client.attachment();
		final CompletableFuture<ChainAttachment> other = client.attachment();

		one.cancel(true);
		fleet.deliver();

		assertThat(done(other), sameInstance(done(client.attachment())));
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

	@Example
	public void choosesTheExitAmongEveryMemberOfTheGroupConnectedOrNot() {
		final ChainFleet fleet = ChainFleet.group();
		final String unconnected = "gn-3";

		fleet.offline(unconnected);

		final ChainAttachment attachment = attached(fleet, clientOf(fleet));
		final Fingerprint fingerprint = IntStream.range(0, 1000)
			.mapToObj(number -> new Fingerprint(ByteBuffer.allocate(Fingerprint.SIZE).putInt(number).array()))
			.filter(candidate -> ExitChoice.exit(candidate, attachment.members(), Set.of())
				.equals(Optional.of(unconnected))).findFirst().orElseThrow();

		assertThat(attachment.members(), hasItem(unconnected));
		assertThat(attachment.exit(fingerprint, Set.of()), equalTo(Optional.of(unconnected)));
		assertThat(attachment.exit(fingerprint, Set.of(unconnected)).orElseThrow(), not(equalTo(unconnected)));
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
				.attachment();

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
				joins).attachment();

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

		done(scripted.client().attachment());

		assertThat(scripted.host.chain(chainId).orElseThrow().heads().get(ChainFleet.writer(9)),
			equalTo(new Head(5, feed.get(5).hash())));
	}

	@Example
	public void asksTheSecondMemberOnlyOnceTheFirstIsFollowed() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final ChainAttachment attachment = done(scripted.client().attachment());
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

		final ChainAttachment attachment = done(scripted.client().attachment());
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

		final ChainAttachment attachment = done(scripted.client().attachment());

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

		final ChainAttachment attachment = done(scripted.client().attachment());

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

		final ChainAttachment attachment = done(scripted.client().attachment());

		assertThat(scripted.host.chain(attachment.chainId()).orElseThrow().heads(),
			equalTo(Map.of(ChainFleet.writer(1), new Head(3, 1))));
	}

	@Example
	public void refusesAGroupWhoseFirstMemberItCannotFollow() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());

		scripted.afterFollow = () -> {
			throw new IllegalStateException("A follow that fails");
		};

		final CompletableFuture<ChainAttachment> attachment = scripted.client().attachment();
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

		final ChainAttachment attachment = done(scripted.client().attachment());
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

		final CompletableFuture<ChainAttachment> attachment = scripted.client().attachment();

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
			.attachment());

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

		assertThat(scripted.client().attachment().isCompletedExceptionally(), is(true));
	}

	@Example
	public void refusesAGroupAtOnceWhenItsRequestCannotBeWritten() {
		final Scripted scripted = new Scripted(1, 1, new PendingRequests());

		scripted.unwritable.add("gn-0");

		assertThat(scripted.client().attachment().isCompletedExceptionally(), is(true));
	}

	@Property(tries = 5)
	public void attachesToTwoOfTheMembersItHoldsAnOpenStreamTo(@ForAll @IntRange(max = 4) final int open) {
		final Scripted scripted = new Scripted(4, 4, new PendingRequests());

		scripted.ids.subList(open, 4).forEach(id -> scripted.streams.get(id).close());

		final int streams = done(scripted.client().attachment().handle((attachment, error) -> attachment == null ? 0
			: attachment.streams().size()));

		assertThat(streams, is(Math.min(open, 2)));
		assertThat(scripted.asked().size(), is(Math.min(open, 2)));
		assertThat(scripted.asked(), everyItem(not(in(scripted.ids.subList(open, 4)))));
	}

	@Example
	public void asksNoSecondMemberOnTheStreamOfTheFirst() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final EmbeddedChannel shared = scripted.streams.get("gn-0");

		scripted.transport = id -> Optional.of(shared);

		assertThat(done(scripted.client().attachment()).streams(), contains(shared));
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

		assertThat(done(scripted.client().attachment()).streams(), contains(scripted.streams.get("gn-2")));
	}

	@Property(tries = 5)
	public void givesUpOnceEveryGroupRefusedIt(@ForAll @IntRange(max = 4) final int groups) {
		final Scripted scripted = new Scripted(groups, 1, new PendingRequests());

		scripted.ids.forEach(id -> scripted.answers.put(id, Scripted.refusing(JoinReply.Status.DISABLED)));

		assertThat(scripted.client().attachment().isCompletedExceptionally(), is(true));
		assertThat(scripted.asked().size(), is(groups));
		assertThat(Set.copyOf(scripted.asked()), equalTo(Set.copyOf(scripted.ids)));
	}

	@Example
	public void failsItsJoinWhenTheSettingsCannotBeRead() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final ChainClient client = scripted.client();
		final ChainAttachment held = done(client.attachment());

		scripted.source = () -> {
			throw new IllegalStateException("Settings that cannot be read");
		};

		assertThat(client.isCurrent(held), is(false));
		assertThat(client.attachment().isCompletedExceptionally(), is(true));
	}

	@Example
	public void neitherJoinsNorKeepsAnAttachmentWhileTheClockReadsBeforeTheOrigin() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());
		final ChainClient client = scripted.client();
		final ChainAttachment held = done(client.attachment());

		scripted.clock.set(-1);

		assertThat(client.isCurrent(held), is(false));
		assertThat(client.attachment().isCompletedExceptionally(), is(true));
	}

	/* One member, so that no second follow brings the chain back */
	@Example
	public void keepsFollowingAChainAJoinClaimedWhileAnEarlierAttachmentOnItWasReleased() {
		final Scripted scripted = new Scripted(1, 1, new PendingRequests());
		final ChainClient client = scripted.client();
		final ChainAttachment lost = done(client.attachment());

		scripted.host.unfollow(lost.chainId());
		scripted.afterFollow = once(() -> client.release(lost));

		final ChainAttachment again = done(client.attachment());

		assertThat(again.chainId(), equalTo(lost.chainId()));
		assertThat(client.isCurrent(again), is(true));
		assertThat(scripted.leaves(scripted.memberOf(lost.streams().get(0))), hasItem(leave(lost.chainId(),
			lost.writerId())));
	}

	@Example
	public void joinsWhenTheDirectoryListsAGridnodeWithoutAnId() {
		final Scripted scripted = new Scripted(2, 2, new PendingRequests());

		scripted.listed.add(Gridnode.builder().status(Gridnode.Status.ACTIVE).hostName("nameless:1").build());

		assertThat(done(scripted.client().attachment()).streams().size(), is(2));
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
					return client.attachment();
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

		final CompletableFuture<ChainAttachment> attachment = client.attachment();

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

		final CompletableFuture<ChainAttachment> attachment = scripted.client().attachment();

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
			final CompletableFuture<String> thread = scripted.client(new Random(1), joins).attachment()
				.thenApply(attachment -> Thread.currentThread().getName());

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
			id -> scripted.transport.streamTo(id), scripted.host, scripted.pending, scripted.clock::get,
			new Random(1));

		final Thread thread = client.attachment().thenApply(attachment -> Thread.currentThread())
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

		final ChainAttachment attachment = done(scripted.client().attachment());

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

	@Example
	public void saysWhyAJoinFailed() {
		final Scripted refused = new Scripted(2, 1, new PendingRequests());
		final Scripted off = new Scripted(2, 2, new PendingRequests());

		refused.ids.forEach(id -> refused.answers.put(id, Scripted.refusing(JoinReply.Status.DISABLED)));
		off.settings.set(Optional.empty());

		assertFailsWith(refused.client().attachment(), "No shard group took this node");
		assertFailsWith(off.client().attachment(), "The proxy is off");
	}
}
