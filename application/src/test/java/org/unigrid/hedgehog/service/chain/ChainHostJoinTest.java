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
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.model.network.codec.ChainArbitraries;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.codec.JoinReplyEncoder;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.group.GroupView;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

public class ChainHostJoinTest {
	private static final int CHAINS = 4;
	private static final int CLIENTS_PER_CHAIN = 4;
	private static final int ATTEMPTS = 256;
	private static final int ROUNDS = 25;
	private static final int THREADS = 16;
	private static final int STREAMS = 1000;
	private static final int TIMEOUT_SECONDS = 30;
	private static final long EPOCH_MILLIS = 3_600_000;
	private static final long[] REQUEST_IDS = {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE};
	private static final ChainId UNHOSTED = new ChainId(new byte[ChainId.SIZE]);

	/* Counts the listeners that are added to the close future of the stream */
	private static final class CountedStream extends EmbeddedChannel {
		private final AtomicInteger listeners = new AtomicInteger();
		private final ChannelFuture counted;

		CountedStream() {
			final ChannelFuture real = super.closeFuture();

			counted = (ChannelFuture) Proxy.newProxyInstance(ChannelFuture.class.getClassLoader(),
				new Class<?>[] { ChannelFuture.class }, (proxy, method, args) -> {
					listeners.addAndGet(method.getName().startsWith("addListener") ? 1 : 0);

					try {
						final Object result = method.invoke(real, args);

						return result == real ? proxy : result;
					} catch (InvocationTargetException ex) {
						throw ex.getCause();
					}
				});
		}

		@Override
		public ChannelFuture closeFuture() {
			return counted;
		}

		int listeners() {
			return listeners.get();
		}
	}

	/* A stream that stops being active before its close has run, as one does while it is closing */
	private static final class FadingStream extends EmbeddedChannel {
		private volatile boolean faded;

		@Override
		public boolean isActive() {
			return !faded && super.isActive();
		}

		void fade() {
			faded = true;
		}
	}

	@Provide
	public Arbitrary<ChainId> chainIds() {
		return ChainArbitraries.chainIds();
	}

	private static ChainHost hostOf(final ChainFleet fleet, final String id, final int chains, final int clients) {
		fleet.settings.set(Optional.of(ChainFleet.settings(8, chains, clients, 30, 3)));

		final ChainHost host = fleet.host(id);

		host.refresh();
		return host;
	}

	private static ChainHost hostOf(final ChainFleet fleet, final String id) {
		return hostOf(fleet, id, CHAINS, CLIENTS_PER_CHAIN);
	}

	private static ChainHost hostOf(final int chains, final int clients) {
		return hostOf(new ChainFleet(20), "gn-3", chains, clients);
	}

	private static JoinReply joinAs(final ChainHost host, final Channel stream, final long requestId, final int writer,
		final ChainId chainId) {

		return host.join(stream, JoinChain.builder().requestId(requestId).writer(ChainFleet.writer(writer))
			.chainId(chainId).build());
	}

	private static JoinReply join(final ChainHost host, final Channel stream, final int writer, final ChainId chainId) {
		return joinAs(host, stream, writer, writer, chainId);
	}

	private static JoinReply join(final ChainHost host, final Channel stream, final int writer) {
		return join(host, stream, writer, null);
	}

	private static void leave(final ChainHost host, final Channel stream, final ChainId chainId, final int writer) {
		host.leave(stream, LeaveChain.builder().chainId(chainId).writer(ChainFleet.writer(writer)).build());
	}

	private static JoinReply refusal(final long requestId, final JoinReply.Status status) {
		return JoinReply.builder().requestId(requestId).status(status).build();
	}

	private static ChainId chainOf(final JoinReply reply) {
		return reply.getChainId().orElseThrow();
	}

	private static int indexOf(final ChainHost host, final JoinReply reply) {
		return host.hostedChains().indexOf(chainOf(reply));
	}

	private static HostedChain hostedOn(final ChainHost host, final ChainId chainId) {
		return host.hosted(chainId).orElseThrow();
	}

	private static int clientsOf(final ChainHost host) {
		return host.hostedChains().stream().mapToInt(chainId -> hostedOn(host, chainId).clientCount()).sum();
	}

	private static int retiredClientsOf(final ChainHost host) {
		return host.snapshot().retired().values().stream().mapToInt(HostedChain::clientCount).sum();
	}

	private static boolean isAttached(final ChainHost host, final int writer) {
		return host.hostedChains().stream()
			.anyMatch(chainId -> hostedOn(host, chainId).isAttached(ChainFleet.writer(writer)));
	}

	private static long clientsOn(final ChainHost host, final Channel stream) {
		return host.snapshot().clientsOn(stream);
	}

	private static WriterId anonymous(final int number) {
		return new WriterId(ByteBuffer.allocate(WriterId.SIZE).putInt(number).array());
	}

	private static Map<ChainId, Long> clientsPerChain(final List<JoinReply> replies) {
		return replies.stream().filter(reply -> reply.getStatus() == JoinReply.Status.JOINED)
			.collect(Collectors.groupingBy(reply -> reply.getChainId().orElseThrow(), Collectors.counting()));
	}

	private static long count(final List<JoinReply> replies, final JoinReply.Status status) {
		return replies.stream().filter(reply -> reply.getStatus() == status).count();
	}

	private static List<EmbeddedChannel> streams(final int count) {
		return IntStream.range(0, count).mapToObj(number -> new EmbeddedChannel()).toList();
	}

	private static List<JoinReply> joinAtOnce(final ChainHost host, final List<JoinChain> requests,
		final List<? extends Channel> streams, final ExecutorService eventLoops) {

		return joinAtOnce(host, requests, streams, eventLoops, () -> { });
	}

	/* The step runs on each racing thread right before its join */
	@SneakyThrows
	private static List<JoinReply> joinAtOnce(final ChainHost host, final List<JoinChain> requests,
		final List<? extends Channel> streams, final ExecutorService eventLoops, final Runnable beforeEachJoin) {

		final CountDownLatch go = new CountDownLatch(1);
		final List<Future<JoinReply>> pending = new ArrayList<>();

		for (int index = 0; index < requests.size(); index++) {
			final JoinChain request = requests.get(index);
			final Channel stream = streams.get(index);

			pending.add(eventLoops.submit(() -> {
				assertThat(go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
				beforeEachJoin.run();
				return host.join(stream, request);
			}));
		}

		go.countDown();

		final List<JoinReply> replies = new ArrayList<>();

		for (Future<JoinReply> reply : pending) {
			replies.add(reply.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		}

		return replies;
	}

	private static List<JoinChain> requests(final int count, final ChainId chainId) {
		return IntStream.range(0, count).mapToObj(writer -> JoinChain.builder().requestId(writer)
			.writer(ChainFleet.writer(writer)).chainId(chainId).build()).toList();
	}

	@SneakyThrows
	private static void awaitIn(final CountDownLatch latch) {
		assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
	}

	@Property(tries = 20)
	public void refusesEveryJoinWhileTheProxyIsOff(@ForAll long requestId, @ForAll("chainIds") ChainId chainId) {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3");

		fleet.settings.set(Optional.empty());
		host.refresh();

		final JoinReply reply = host.join(new EmbeddedChannel(), JoinChain.builder().requestId(requestId)
			.writer(ChainFleet.writer(1)).chainId(chainId).build());

		assertThat(reply, equalTo(JoinReply.builder().requestId(requestId).status(JoinReply.Status.DISABLED).build()));
	}

	@Property(tries = 20)
	public void refusesAChainThisNodeDoesNotHost(@ForAll("chainIds") ChainId chainId) {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");

		assertThat(join(host, new EmbeddedChannel(), 1, chainId).getStatus(), is(JoinReply.Status.UNKNOWN_CHAIN));
	}

	@Example
	public void hostsNoChainWithoutBeingAnActiveGridnode() {
		final ChainHost host = hostOf(new ChainFleet(20), "stranger");

		assertThat(join(host, new EmbeddedChannel(), 1).getStatus(), is(JoinReply.Status.UNKNOWN_CHAIN));
	}

	@Example
	public void echoesTheRequestIdInEveryAnswer() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3", 1, 1);
		final ChainId only = host.hostedChains().get(0);
		final EmbeddedChannel owner = new EmbeddedChannel();

		for (final long requestId : REQUEST_IDS) {
			assertThat(joinAs(host, owner, requestId, 0, null), equalTo(JoinReply.builder().requestId(requestId)
				.status(JoinReply.Status.JOINED).chainId(only).heads(Map.of()).build()));
			assertThat(joinAs(host, new EmbeddedChannel(), requestId, 1, null),
				equalTo(refusal(requestId, JoinReply.Status.FULL)));
			assertThat(joinAs(host, new EmbeddedChannel(), requestId, 1, UNHOSTED),
				equalTo(refusal(requestId, JoinReply.Status.UNKNOWN_CHAIN)));
		}

		fleet.settings.set(Optional.empty());
		host.refresh();

		for (final long requestId : REQUEST_IDS) {
			for (final ChainId named : new ChainId[] {null, only}) {
				assertThat(joinAs(host, new EmbeddedChannel(), requestId, 1, named),
					equalTo(refusal(requestId, JoinReply.Status.DISABLED)));
			}
		}
	}

	@Example
	public void refusesAChainOfAnotherGroupInTheSameEpoch() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3");
		final GroupView view = fleet.view();
		final String stranger = fleet.activeIds().stream()
			.filter(id -> !view.groups().groupOf(id).equals(host.group())).findFirst().orElseThrow();
		final ChainId foreign = view.chainsOf(stranger).get(0);

		assertThat(join(host, new EmbeddedChannel(), 1, foreign), equalTo(refusal(1, JoinReply.Status.UNKNOWN_CHAIN)));
		assertThat(clientsOf(host), is(0));
	}

	@Example
	public void refusesAChainOfAnEarlierEpochAndKeepsItsClientsUntilTheirStreamCloses() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3");
		final EmbeddedChannel stream = new EmbeddedChannel();
		final ChainId earlier = chainOf(join(host, stream, 0));

		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();

		assertThat(join(host, new EmbeddedChannel(), 1, earlier), equalTo(refusal(1, JoinReply.Status.UNKNOWN_CHAIN)));
		assertThat(clientsOf(host), is(0));
		assertThat(retiredClientsOf(host), is(1));

		final JoinReply later = join(host, stream, 0);

		assertThat(later.getStatus(), is(JoinReply.Status.JOINED));
		assertThat(chainOf(later), not(equalTo(earlier)));
		assertThat(host.hostedChains().contains(chainOf(later)), is(true));
		assertThat(clientsOf(host), is(1));
		assertThat(retiredClientsOf(host), is(1));

		stream.close();
		assertThat(clientsOf(host), is(0));
		assertThat(retiredClientsOf(host), is(0));
	}

	@Property(tries = 16)
	public void spreadsTheClientsOverTheChainsWithTheFewestFeeds(
		@ForAll @IntRange(min = 1, max = CHAINS * CLIENTS_PER_CHAIN) int clients) {

		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final List<JoinReply> replies = IntStream.range(0, clients)
			.mapToObj(writer -> join(host, new EmbeddedChannel(), writer)).toList();
		final Map<ChainId, Long> counts = clientsPerChain(replies);

		assertThat(counts.values().stream().mapToLong(Long::longValue).sum(), is((long) clients));
		assertThat(counts.values().stream().mapToLong(Long::longValue).max().orElse(0),
			lessThanOrEqualTo((long) (clients + CHAINS - 1) / CHAINS));
		assertThat(host.hostedChains().containsAll(counts.keySet()), is(true));
	}

	@Example
	public void placesClientsOnTheLowestChainWhileTheFeedsTie() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final List<Integer> placed = IntStream.range(0, CHAINS * 2)
			.mapToObj(writer -> indexOf(host, join(host, new EmbeddedChannel(), writer))).toList();

		assertThat(placed, contains(0, 1, 2, 3, 0, 1, 2, 3));
	}

	@Example
	public void placesAClientOnTheFirstChainOfTheFewestFeeds() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final List<EmbeddedChannel> streams = streams(CHAINS * 2);
		final List<JoinReply> replies = IntStream.range(0, streams.size())
			.mapToObj(writer -> join(host, streams.get(writer), writer)).toList();

		leave(host, streams.get(7), chainOf(replies.get(7)), 7);
		leave(host, streams.get(5), chainOf(replies.get(5)), 5);

		final List<Integer> placed = IntStream.range(10, 13)
			.mapToObj(writer -> indexOf(host, join(host, new EmbeddedChannel(), writer))).toList();

		assertThat(placed, contains(1, 3, 0));
	}

	@Example
	public void placesAClientOnExactlyTheChainItNames() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final List<ChainId> chains = host.hostedChains();
		final EmbeddedChannel stream = new EmbeddedChannel();
		final JoinReply onThird = join(host, stream, 0, chains.get(3));
		final JoinReply onSecond = join(host, stream, 1, chains.get(2));

		assertThat(chainOf(onThird), equalTo(chains.get(3)));
		assertThat(chainOf(onSecond), equalTo(chains.get(2)));
		assertThat(hostedOn(host, chains.get(0)).clientCount(), is(0));
		assertThat(hostedOn(host, chains.get(1)).clientCount(), is(0));
		assertThat(chainOf(join(host, stream, 0)), equalTo(chains.get(3)));
		assertThat(chainOf(join(host, stream, 0, chains.get(0))), equalTo(chains.get(0)));
	}

	@Example
	public void refusesJoinsWhileEveryChainIsFullUntilAStreamCloses() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final List<EmbeddedChannel> streams = streams(CHAINS * CLIENTS_PER_CHAIN);

		IntStream.range(0, streams.size()).forEach(writer -> join(host, streams.get(writer), writer));

		final JoinReply full = join(host, new EmbeddedChannel(), 100);

		assertThat(full, equalTo(JoinReply.builder().requestId(100).status(JoinReply.Status.FULL).build()));

		streams.get(5).close();
		assertThat(join(host, new EmbeddedChannel(), 100).getStatus(), is(JoinReply.Status.JOINED));
	}

	@Example
	public void attachesNobodyToAChainOfAnEarlierEpoch() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3");
		final ChainId earlier = host.hostedChains().get(0);

		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();

		assertThat(join(host, new EmbeddedChannel(), 1, earlier).getStatus(), is(JoinReply.Status.UNKNOWN_CHAIN));
		assertThat(join(host, new EmbeddedChannel(), 1).getStatus(), is(JoinReply.Status.JOINED));
	}

	@Example
	public void placesNewClientsOnTheCurrentChainsEvenWhenARetiredOneHasFewer() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3");

		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();
		IntStream.range(0, CHAINS).forEach(writer -> join(host, new EmbeddedChannel(), writer));

		final JoinReply reply = join(host, new EmbeddedChannel(), CHAINS);

		assertThat(reply.getStatus(), is(JoinReply.Status.JOINED));
		assertThat(host.hostedChains().contains(chainOf(reply)), is(true));
		assertThat(retiredClientsOf(host), is(0));
	}

	@Example
	public void attachesAWriterToACurrentChainAndKeepsItOnTheRetiredOneUntilItsStreamCloses() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3");
		final EmbeddedChannel stream = new EmbeddedChannel();
		final ChainId earlier = chainOf(join(host, stream, 1));

		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();

		final JoinReply again = join(host, stream, 1);

		assertThat(again.getStatus(), is(JoinReply.Status.JOINED));
		assertThat(host.hostedChains().contains(chainOf(again)), is(true));
		assertThat(hostedOn(host, chainOf(again)).isAttached(ChainFleet.writer(1)), is(true));
		assertThat(hostedOn(host, earlier).isAttached(ChainFleet.writer(1)), is(true));

		stream.close();

		assertThat(hostedOn(host, chainOf(again)).isAttached(ChainFleet.writer(1)), is(false));
		assertThat(hostedOn(host, earlier).isAttached(ChainFleet.writer(1)), is(false));
	}

	@Example
	public void countsTheClientsOnRetiredChainsAgainstTheirStream() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3");
		final EmbeddedChannel stream = new EmbeddedChannel();

		host.hostedChains().forEach(chainId -> join(host, stream, host.hostedChains().indexOf(chainId), chainId));
		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();

		assertThat(clientsOn(host, stream), is((long) CHAINS));
		assertThat(join(host, stream, CHAINS).getStatus(), is(JoinReply.Status.FULL));
		assertThat(join(host, new EmbeddedChannel(), CHAINS).getStatus(), is(JoinReply.Status.JOINED));
	}

	@Example
	public void detachesTheClientsOfRetiredChainsWhenTheirStreamsClose() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3", CHAINS, STREAMS);
		final List<EmbeddedChannel> streams = streams(STREAMS);

		IntStream.range(0, STREAMS).forEach(writer -> join(host, streams.get(writer), writer));
		assertThat(clientsOf(host), is(STREAMS));

		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();

		assertThat(clientsOf(host), is(0));
		assertThat(retiredClientsOf(host), is(STREAMS));
		assertThat(clientsOn(host, streams.get(0)), is(1L));

		streams.forEach(EmbeddedChannel::close);

		assertThat(retiredClientsOf(host), is(0));
		assertThat(clientsOn(host, streams.get(0)), is(0L));
	}

	@Example
	public void keepsAWriterOnTheChainItJoined() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final EmbeddedChannel stream = new EmbeddedChannel();
		final JoinReply first = join(host, stream, 0);

		IntStream.range(1, 8).forEach(writer -> join(host, new EmbeddedChannel(), writer));

		assertThat(join(host, stream, 0).getChainId(), equalTo(first.getChainId()));
	}

	@Example
	public void attachesToTheChainThatWasNamedAsLongAsItHasRoom() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final ChainId named = host.hostedChains().get(2);
		final List<JoinReply> replies = IntStream.range(0, CLIENTS_PER_CHAIN + 1)
			.mapToObj(writer -> join(host, new EmbeddedChannel(), writer, named)).toList();

		assertThat(clientsPerChain(replies), equalTo(Map.of(named, (long) CLIENTS_PER_CHAIN)));
		assertThat(replies.get(CLIENTS_PER_CHAIN).getStatus(), is(JoinReply.Status.FULL));
		assertThat(join(host, new EmbeddedChannel(), 100).getStatus(), is(JoinReply.Status.JOINED));
	}

	@Example
	public void takesOneClientWhereTheLimitIsOne() {
		final ChainHost host = hostOf(1, 1);
		final ChainId only = host.hostedChains().get(0);
		final EmbeddedChannel owner = new EmbeddedChannel();

		assertThat(join(host, owner, 0, only).getStatus(), is(JoinReply.Status.JOINED));
		assertThat(join(host, new EmbeddedChannel(), 1, only).getStatus(), is(JoinReply.Status.FULL));
		assertThat(join(host, new EmbeddedChannel(), 1).getStatus(), is(JoinReply.Status.FULL));
		assertThat(clientsOf(host), is(1));

		owner.close();
		assertThat(join(host, new EmbeddedChannel(), 1, only).getStatus(), is(JoinReply.Status.JOINED));
	}

	@Example
	public void refusesTheJoinOverTheLimitAndRenewsTheClientsAtTheLimit() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final ChainId named = host.hostedChains().get(1);
		final List<EmbeddedChannel> streams = streams(CLIENTS_PER_CHAIN);

		IntStream.range(0, CLIENTS_PER_CHAIN)
			.forEach(writer -> assertThat(join(host, streams.get(writer), writer, named).getStatus(),
				is(JoinReply.Status.JOINED)));
		assertThat(hostedOn(host, named).clientCount(), is(CLIENTS_PER_CHAIN));
		assertThat(join(host, new EmbeddedChannel(), CLIENTS_PER_CHAIN, named).getStatus(), is(JoinReply.Status.FULL));
		assertThat(hostedOn(host, named).clientCount(), is(CLIENTS_PER_CHAIN));

		for (int writer = 0; writer < CLIENTS_PER_CHAIN; writer++) {
			assertThat(chainOf(join(host, streams.get(writer), writer, named)), equalTo(named));
			assertThat(chainOf(join(host, streams.get(writer), writer)), equalTo(named));
		}

		assertThat(hostedOn(host, named).clientCount(), is(CLIENTS_PER_CHAIN));
	}

	@Example
	public void answersWithTheHeadsOfTheChainItJoined() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final Chain chain = host.chain(host.hostedChains().get(0)).orElseThrow();
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(50), chain.id(), 3, ChainFleet.NOW);

		entries.forEach(chain::offer);

		final JoinReply reply = join(host, new EmbeddedChannel(), 1, chain.id());

		assertThat(reply.getHeads(), equalTo(chain.heads()));
		assertThat(reply.getHeads().size(), is(1));
	}

	@Example @SneakyThrows
	public void refusesAChainWhoseHeadsNoPacketCanCarry() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final Chain chain = host.chain(host.hostedChains().get(0)).orElseThrow();
		final EmbeddedChannel first = new EmbeddedChannel();

		IntStream.range(0, ChainCodecs.MAX_HEADS_PER_PACKET)
			.forEach(number -> chain.anchor(anonymous(number), new Head(number, number)));

		final JoinReply fits = join(host, first, 1, chain.id());

		assertThat(fits.getStatus(), is(JoinReply.Status.JOINED));
		assertThat(fits.getHeads().size(), is(ChainCodecs.MAX_HEADS_PER_PACKET));
		assertThat(new JoinReplyEncoder().encode(null, fits).isPresent(), is(true));

		chain.anchor(anonymous(ChainCodecs.MAX_HEADS_PER_PACKET), new Head(0, 0));

		assertThat(join(host, new EmbeddedChannel(), 2, chain.id()), equalTo(refusal(2, JoinReply.Status.FULL)));
		assertThat(join(host, first, 1, chain.id()), equalTo(refusal(1, JoinReply.Status.FULL)));
		assertThat(clientsOf(host), is(0));
	}

	@Example
	public void refusesAWriterThatIsAttachedThroughAnotherLiveStream() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final EmbeddedChannel owner = new EmbeddedChannel();
		final EmbeddedChannel claimant = new EmbeddedChannel();

		join(host, owner, 1);
		assertThat(join(host, claimant, 1).getStatus(), is(JoinReply.Status.FULL));

		owner.close();
		assertThat(join(host, claimant, 1).getStatus(), is(JoinReply.Status.JOINED));
	}

	@Example
	public void keepsTheStreamAWriterAttachedThrough() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final EmbeddedChannel owner = new EmbeddedChannel();
		final EmbeddedChannel claimant = new EmbeddedChannel();
		final ChainId chainId = chainOf(join(host, owner, 1));

		assertThat(join(host, claimant, 1), equalTo(refusal(1, JoinReply.Status.FULL)));
		assertThat(join(host, claimant, 1, chainId), equalTo(refusal(1, JoinReply.Status.FULL)));
		assertThat(clientsOn(host, owner), is(1L));
		assertThat(clientsOn(host, claimant), is(0L));
		assertThat(hostedOn(host, chainId).clientCount(), is(1));

		assertThat(chainOf(join(host, owner, 1)), equalTo(chainId));
		owner.close();
		assertThat(clientsOf(host), is(0));
		assertThat(chainOf(join(host, claimant, 1)), equalTo(chainId));
		assertThat(clientsOn(host, claimant), is(1L));
	}

	@Example
	public void handsTheWriterOfAStreamThatIsGoneOverAndKeepsItWhenTheOldStreamCloses() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final FadingStream gone = new FadingStream();
		final EmbeddedChannel claimant = new EmbeddedChannel();
		final ChainId chainId = chainOf(join(host, gone, 1));

		gone.fade();
		assertThat(chainOf(join(host, claimant, 1)), equalTo(chainId));
		assertThat(clientsOn(host, gone), is(0L));
		assertThat(clientsOn(host, claimant), is(1L));

		gone.close();
		assertThat(hostedOn(host, chainId).clientCount(), is(1));
		assertThat(isAttached(host, 1), is(true));
		assertThat(clientsOn(host, claimant), is(1L));
	}

	@Example
	public void attachesAStreamAsAtMostAFewWriters() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final EmbeddedChannel stream = new EmbeddedChannel();
		final List<JoinReply.Status> statuses = IntStream.range(0, ChainHost.MAX_WRITERS_PER_STREAM + 2)
			.mapToObj(writer -> join(host, stream, writer).getStatus()).toList();

		assertThat(statuses.subList(0, ChainHost.MAX_WRITERS_PER_STREAM), everyItem(is(JoinReply.Status.JOINED)));
		assertThat(statuses.subList(ChainHost.MAX_WRITERS_PER_STREAM, statuses.size()),
			everyItem(is(JoinReply.Status.FULL)));
		assertThat(join(host, stream, 0).getStatus(), is(JoinReply.Status.JOINED));
		assertThat(join(host, new EmbeddedChannel(), ChainHost.MAX_WRITERS_PER_STREAM).getStatus(),
			is(JoinReply.Status.JOINED));
	}

	@Example
	public void takesTheLastWriterAStreamMayHave() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final EmbeddedChannel stream = new EmbeddedChannel();

		IntStream.range(0, ChainHost.MAX_WRITERS_PER_STREAM - 1)
			.forEach(writer -> assertThat(join(host, stream, writer).getStatus(), is(JoinReply.Status.JOINED)));
		assertThat(clientsOn(host, stream), is((long) ChainHost.MAX_WRITERS_PER_STREAM - 1));

		assertThat(join(host, stream, 3).getStatus(), is(JoinReply.Status.JOINED));
		assertThat(clientsOn(host, stream), is((long) ChainHost.MAX_WRITERS_PER_STREAM));
	}

	@Example
	public void refusesTheNextWriterOfAStreamAtItsLimitButRenewsTheOnesItHas() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3");
		final EmbeddedChannel stream = new EmbeddedChannel();
		final List<JoinReply> attached = IntStream.range(0, ChainHost.MAX_WRITERS_PER_STREAM)
			.mapToObj(writer -> join(host, stream, writer)).toList();

		assertThat(join(host, stream, 4), equalTo(refusal(4, JoinReply.Status.FULL)));

		/* A stream may join only 8 times a second, so each burst of joins comes in a second of its own: 5 joins in
		   the first, 4 and a renewal in the second and 8 renewals in the third */
		fleet.clock.addAndGet(1000);

		for (final ChainId chainId : host.hostedChains()) {
			assertThat(join(host, stream, 4, chainId), equalTo(refusal(4, JoinReply.Status.FULL)));
		}

		assertThat(clientsOn(host, stream), is((long) ChainHost.MAX_WRITERS_PER_STREAM));
		assertThat(isAttached(host, 4), is(false));

		/* The second still has joins left, so the refusals above came from the limit of the stream */
		assertThat(join(host, stream, 0).getStatus(), is(JoinReply.Status.JOINED));

		fleet.clock.addAndGet(1000);

		for (int writer = 0; writer < ChainHost.MAX_WRITERS_PER_STREAM; writer++) {
			assertThat(join(host, stream, writer).getStatus(), is(JoinReply.Status.JOINED));
			assertThat(chainOf(join(host, stream, writer, chainOf(attached.get(writer)))),
				equalTo(chainOf(attached.get(writer))));
		}

		assertThat(clientsOn(host, stream), is((long) ChainHost.MAX_WRITERS_PER_STREAM));
	}

	@Example
	public void refusesTheWritersBeyondTheLimitOfAStreamUntilOneLeaves() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final EmbeddedChannel stream = new EmbeddedChannel();
		final List<JoinReply> attached = IntStream.range(0, ChainHost.MAX_WRITERS_PER_STREAM)
			.mapToObj(writer -> join(host, stream, writer)).toList();

		assertThat(join(host, stream, 4).getStatus(), is(JoinReply.Status.FULL));
		assertThat(join(host, stream, 5).getStatus(), is(JoinReply.Status.FULL));

		leave(host, stream, chainOf(attached.get(0)), 0);
		assertThat(join(host, stream, 4).getStatus(), is(JoinReply.Status.JOINED));
		assertThat(join(host, stream, 5).getStatus(), is(JoinReply.Status.FULL));
		assertThat(clientsOn(host, stream), is((long) ChainHost.MAX_WRITERS_PER_STREAM));
	}

	@Example
	public void makesRoomWhenAWriterLeavesThroughItsOwnStream() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final List<EmbeddedChannel> streams = streams(CHAINS * CLIENTS_PER_CHAIN);
		final List<JoinReply> replies = IntStream.range(0, streams.size())
			.mapToObj(writer -> join(host, streams.get(writer), writer)).toList();
		final JoinReply leaving = replies.get(3);

		host.leave(new EmbeddedChannel(), LeaveChain.builder().chainId(leaving.getChainId().orElseThrow())
			.writer(ChainFleet.writer(3)).build());
		assertThat(join(host, new EmbeddedChannel(), 100).getStatus(), is(JoinReply.Status.FULL));

		host.leave(streams.get(3), LeaveChain.builder().chainId(leaving.getChainId().orElseThrow())
			.writer(ChainFleet.writer(3)).build());
		assertThat(join(host, new EmbeddedChannel(), 100).getStatus(), is(JoinReply.Status.JOINED));
	}

	@Example
	public void believesALeaveOnlyOnTheStreamTheWriterAttachedThrough() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final EmbeddedChannel owner = new EmbeddedChannel();
		final EmbeddedChannel other = new EmbeddedChannel();
		final ChainId chainId = chainOf(join(host, owner, 1));

		join(host, owner, 2, chainId);
		leave(host, other, chainId, 1);
		leave(host, owner, host.hostedChains().stream().filter(id -> !id.equals(chainId)).findFirst().orElseThrow(), 1);
		leave(host, owner, UNHOSTED, 1);
		leave(host, owner, chainId, 3);

		assertThat(isAttached(host, 1), is(true));
		assertThat(isAttached(host, 2), is(true));
		assertThat(clientsOn(host, owner), is(2L));

		leave(host, owner, chainId, 1);

		assertThat(isAttached(host, 1), is(false));
		assertThat(isAttached(host, 2), is(true));
		assertThat(clientsOn(host, owner), is(1L));
	}

	@Property(tries = 10)
	public void ignoresALeaveFromAChainItDoesNotHost(@ForAll("chainIds") ChainId chainId) {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");

		host.leave(new EmbeddedChannel(), LeaveChain.builder().chainId(chainId).writer(ChainFleet.writer(1)).build());
		assertThat(join(host, new EmbeddedChannel(), 1).getStatus(), is(JoinReply.Status.JOINED));
	}

	@Example
	public void toleratesPacketsThatNameNoWriterOrChain() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final EmbeddedChannel stream = new EmbeddedChannel();
		final ChainId chainId = chainOf(join(host, stream, 1));

		assertThat(host.join(stream, new JoinChain()), equalTo(refusal(0, JoinReply.Status.FULL)));
		host.leave(stream, new LeaveChain());
		host.leave(stream, LeaveChain.builder().chainId(chainId).build());
		host.leave(stream, LeaveChain.builder().writer(ChainFleet.writer(1)).build());

		assertThat(isAttached(host, 1), is(true));
		assertThat(clientsOf(host), is(1));
	}

	@Example
	public void toleratesLeavesThatNameNoWriterOrChainWhileTheProxyIsOff() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3");
		final ChainId chainId = host.hostedChains().get(0);

		fleet.settings.set(Optional.empty());
		host.refresh();

		host.leave(new EmbeddedChannel(), new LeaveChain());
		host.leave(new EmbeddedChannel(), LeaveChain.builder().chainId(chainId).build());
		host.leave(new EmbeddedChannel(), LeaveChain.builder().writer(ChainFleet.writer(1)).build());
		host.leave(new EmbeddedChannel(), LeaveChain.builder().chainId(chainId).writer(ChainFleet.writer(1)).build());
		assertThat(host.hostedChains().isEmpty(), is(true));
	}

	@Example
	public void attachesNothingForAStreamThatIsAlreadyClosed() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final CountedStream closed = new CountedStream();

		closed.close();

		assertThat(join(host, closed, 1), equalTo(refusal(1, JoinReply.Status.FULL)));
		assertThat(join(host, closed, 2, host.hostedChains().get(0)), equalTo(refusal(2, JoinReply.Status.FULL)));
		assertThat(clientsOf(host), is(0));
		assertThat(closed.listeners(), is(0));
		assertThat(join(host, new EmbeddedChannel(), 1).getStatus(), is(JoinReply.Status.JOINED));
	}

	@Example
	public void dropsEveryAttachmentOfAStreamWhenItCloses() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final EmbeddedChannel closing = new EmbeddedChannel();
		final EmbeddedChannel staying = new EmbeddedChannel();

		host.hostedChains().forEach(chainId -> join(host, closing, host.hostedChains().indexOf(chainId), chainId));
		join(host, staying, 10);

		assertThat(clientsOn(host, closing), is((long) CHAINS));
		closing.close();

		assertThat(clientsOn(host, closing), is(0L));
		assertThat(clientsOf(host), is(1));
		assertThat(isAttached(host, 10), is(true));
		IntStream.range(0, CHAINS).forEach(writer -> assertThat(isAttached(host, writer), is(false)));
	}

	@Example
	public void holdsNothingForStreamsThatClose() {
		final ChainHost host = hostOf(CHAINS, STREAMS);
		final List<CountedStream> streams = IntStream.range(0, STREAMS).mapToObj(number -> new CountedStream()).toList();

		final List<JoinReply> replies = IntStream.range(0, STREAMS)
			.mapToObj(writer -> join(host, streams.get(writer), writer)).toList();

		assertThat(count(replies, JoinReply.Status.JOINED), is((long) STREAMS));
		assertThat(clientsOf(host), is(STREAMS));

		streams.forEach(EmbeddedChannel::close);

		assertThat(clientsOf(host), is(0));
		assertThat(streams.stream().map(CountedStream::listeners).toList(), everyItem(is(1)));
	}

	@Example
	public void watchesAStreamOnceHoweverOftenItJoinsAndLeaves() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3");
		final CountedStream stream = new CountedStream();

		for (int round = 0; round < ROUNDS; round++) {
			fleet.clock.addAndGet(1000);

			final JoinReply first = join(host, stream, 0);
			final JoinReply second = join(host, stream, 1);

			leave(host, stream, chainOf(first), 0);
			leave(host, stream, chainOf(second), 1);
		}

		assertThat(stream.listeners(), is(1));
		assertThat(clientsOf(host), is(0));

		join(host, stream, 0);
		stream.close();
		assertThat(clientsOf(host), is(0));
	}

	@Example
	public void keepsAnAttachmentWhenARefreshFindsNothingChanged() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final EmbeddedChannel stream = new EmbeddedChannel();
		final ChainId chainId = chainOf(join(host, stream, 1));
		final HostedChain before = hostedOn(host, chainId);
		final Hosting hosting = host.snapshot();

		host.refresh();
		host.refresh();

		assertThat(host.snapshot(), sameInstance(hosting));
		assertThat(hostedOn(host, chainId), sameInstance(before));
		assertThat(isAttached(host, 1), is(true));
		assertThat(chainOf(join(host, stream, 1)), equalTo(chainId));
		assertThat(clientsOf(host), is(1));

		stream.close();
		assertThat(clientsOf(host), is(0));
	}

	@Example
	public void keepsAnAttachmentWhenTheHostingIsRebuiltAroundTheSameChain() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3");
		final EmbeddedChannel stream = new EmbeddedChannel();
		final ChainId chainId = chainOf(join(host, stream, 1));
		final HostedChain before = hostedOn(host, chainId);
		final Hosting hosting = host.snapshot();

		fleet.settings.set(Optional.of(ChainFleet.settings(8, CHAINS, CLIENTS_PER_CHAIN, 30, ProxySettings.CONNECT)));
		host.refresh();

		assertThat(host.snapshot(), not(sameInstance(hosting)));
		assertThat(hostedOn(host, chainId), sameInstance(before));
		assertThat(isAttached(host, 1), is(true));

		stream.close();
		assertThat(clientsOf(host), is(0));
	}

	@Example
	public void appliesALoweredLimitToTheJoinsThatFollowAndKeepsWhoIsAttached() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3");
		final ChainId named = host.hostedChains().get(0);
		final EmbeddedChannel first = new EmbeddedChannel();

		join(host, first, 1, named);
		join(host, new EmbeddedChannel(), 2, named);
		fleet.settings.set(Optional.of(ChainFleet.settings(8, CHAINS, 2, 30, 3)));
		host.refresh();

		assertThat(join(host, new EmbeddedChannel(), 3, named).getStatus(), is(JoinReply.Status.FULL));
		assertThat(join(host, first, 1, named).getStatus(), is(JoinReply.Status.JOINED));
		assertThat(hostedOn(host, named).clientCount(), is(2));
	}

	@Example
	public void dropsTheAttachmentsOfAChainThatIsRebuiltForNewLimits() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3");
		final EmbeddedChannel stream = new EmbeddedChannel();
		final ChainId chainId = chainOf(join(host, stream, 1));
		final HostedChain before = hostedOn(host, chainId);

		fleet.settings.set(Optional.of(ChainFleet.settings(8, CHAINS, CLIENTS_PER_CHAIN, 31, 3)));
		host.refresh();

		assertThat(hostedOn(host, chainId), not(sameInstance(before)));
		assertThat(clientsOf(host), is(0));
		assertThat(chainOf(join(host, new EmbeddedChannel(), 1, chainId)), equalTo(chainId));

		stream.close();
		assertThat(clientsOf(host), is(1));
	}

	@Example
	public void dropsTheAttachmentsWhenTheProxyGoesOffAndComesBack() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = hostOf(fleet, "gn-3");
		final EmbeddedChannel stream = new EmbeddedChannel();
		final ChainId chainId = chainOf(join(host, stream, 1));

		fleet.settings.set(Optional.empty());
		host.refresh();
		assertThat(join(host, stream, 1).getStatus(), is(JoinReply.Status.DISABLED));

		fleet.settings.set(Optional.of(ChainFleet.settings(8, CHAINS, CLIENTS_PER_CHAIN, 30, 3)));
		host.refresh();

		assertThat(clientsOf(host), is(0));
		assertThat(chainOf(join(host, new EmbeddedChannel(), 1, chainId)), equalTo(chainId));
	}

	@Example @SneakyThrows
	public void keepsAnsweringJoinsWhileARefreshWaitsForAStream() {
		final ChainFleet fleet = new ChainFleet(20);
		final AtomicBoolean holding = new AtomicBoolean();
		final CountDownLatch inLookup = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final ChainHost host = fleet.hostOver("gn-3", member -> {
			if (holding.get()) {
				inLookup.countDown();
				awaitIn(release);
			}

			return Optional.empty();
		});
		final ExecutorService callers = Executors.newFixedThreadPool(2);

		try {
			host.refresh();

			final EmbeddedChannel stream = new EmbeddedChannel();
			final JoinReply first = join(host, stream, 1);

			holding.set(true);

			final Future<?> refreshing = callers.submit(host::refresh);

			assertThat(inLookup.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));

			final Future<JoinReply> renewed = callers.submit(() -> join(host, stream, 1));

			assertThat(renewed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getChainId(), equalTo(first.getChainId()));

			release.countDown();
			refreshing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertThat(join(host, stream, 1).getChainId(), equalTo(first.getChainId()));
			assertThat(clientsOf(host), is(1));
		} finally {
			release.countDown();
			callers.shutdownNow();
		}
	}

	@Example @SneakyThrows
	public void neverAdmitsMoreThanTheLimitWhenEventLoopsJoinAtOnce() {
		final List<JoinChain> requests = requests(ATTEMPTS, null);
		final ExecutorService eventLoops = Executors.newFixedThreadPool(THREADS);

		try {
			for (int round = 0; round < ROUNDS; round++) {
				final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
				final List<JoinReply> replies = joinAtOnce(host, requests, streams(ATTEMPTS), eventLoops);

				assertThat(clientsPerChain(replies).values(), contains(4L, 4L, 4L, 4L));
				assertThat(count(replies, JoinReply.Status.FULL), is((long) ATTEMPTS - CHAINS * CLIENTS_PER_CHAIN));
				assertThat(clientsOf(host), is(CHAINS * CLIENTS_PER_CHAIN));
			}
		} finally {
			eventLoops.shutdownNow();
		}
	}

	@Example @SneakyThrows
	public void neverAdmitsMoreThanOneClientToAChainThatTakesOneWhenEveryWriterNamesIt() {
		final ExecutorService eventLoops = Executors.newFixedThreadPool(THREADS);

		try {
			for (int round = 0; round < ROUNDS; round++) {
				final ChainHost host = hostOf(1, 1);
				final List<JoinReply> replies = joinAtOnce(host, requests(ATTEMPTS, host.hostedChains().get(0)),
					streams(ATTEMPTS), eventLoops);

				assertThat(count(replies, JoinReply.Status.JOINED), is(1L));
				assertThat(count(replies, JoinReply.Status.FULL), is((long) ATTEMPTS - 1));
				assertThat(clientsOf(host), is(1));
			}
		} finally {
			eventLoops.shutdownNow();
		}
	}

	/* Each join moves the clock on by a second first, so that nearly every join falls in a second of its own and the
	   joins race for the places of the stream rather than for its joins of a second */
	@Example @SneakyThrows
	public void neverAttachesAStreamAsMoreWritersThanItMayWhenItJoinsAtOnce() {
		final ExecutorService eventLoops = Executors.newFixedThreadPool(THREADS);

		try {
			for (int round = 0; round < ROUNDS; round++) {
				final ChainFleet fleet = new ChainFleet(20);
				final ChainHost host = hostOf(fleet, "gn-3", CHAINS, ATTEMPTS);
				final EmbeddedChannel stream = new EmbeddedChannel();
				final List<JoinReply> replies = joinAtOnce(host, requests(ATTEMPTS, null),
					Collections.nCopies(ATTEMPTS, stream), eventLoops, () -> fleet.clock.addAndGet(1000));

				assertThat(count(replies, JoinReply.Status.JOINED), is((long) ChainHost.MAX_WRITERS_PER_STREAM));
				assertThat(clientsOn(host, stream), is((long) ChainHost.MAX_WRITERS_PER_STREAM));
			}
		} finally {
			eventLoops.shutdownNow();
		}
	}

	@Example @SneakyThrows
	public void keepsTheStreamOfAWriterWhenStreamsClaimItAtOnce() {
		final ExecutorService eventLoops = Executors.newFixedThreadPool(THREADS);

		try {
			for (int round = 0; round < ROUNDS; round++) {
				final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
				final List<JoinChain> claims = Collections.nCopies(ATTEMPTS, JoinChain.builder()
					.writer(ChainFleet.writer(1)).build());
				final List<JoinReply> replies = joinAtOnce(host, claims, streams(ATTEMPTS), eventLoops);

				assertThat(count(replies, JoinReply.Status.JOINED), is(1L));
				assertThat(clientsOf(host), is(1));
			}
		} finally {
			eventLoops.shutdownNow();
		}
	}

	/* The monitor of a chain is held by whatever verifies its entries, which a stream that floods forged entries can
	   keep busy: a join that waits for the heads of that chain has attached its writer already, so no entry slips
	   past it, and holds up no join to another chain */
	@Example @SneakyThrows
	public void attachesBeforeItWaitsForTheHeadsOfAChainAndHoldsUpNoJoinToAnother() {
		final ChainHost host = hostOf(new ChainFleet(20), "gn-3");
		final ChainId busy = host.hostedChains().get(0);
		final ChainId other = host.hostedChains().get(1);
		final CountDownLatch held = new CountDownLatch(1);
		final CountDownLatch released = new CountDownLatch(1);
		final ExecutorService eventLoops = Executors.newFixedThreadPool(3);

		try {
			eventLoops.submit(() -> {
				synchronized (hostedOn(host, busy).getChain()) {
					held.countDown();
					return released.await(3 * TIMEOUT_SECONDS, TimeUnit.SECONDS);
				}
			});

			awaitIn(held);

			final Future<JoinReply> waiting = eventLoops.submit(() -> join(host, new EmbeddedChannel(), 1, busy));
			final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

			while (!hostedOn(host, busy).isAttached(ChainFleet.writer(1)) && System.nanoTime() < deadline) {
				Thread.onSpinWait();
			}

			assertThat(hostedOn(host, busy).isAttached(ChainFleet.writer(1)), is(true));
			assertThat(eventLoops.submit(() -> join(host, new EmbeddedChannel(), 2, other))
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatus(), is(JoinReply.Status.JOINED));
			assertThat(waiting.isDone(), is(false));

			released.countDown();
			assertThat(chainOf(waiting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)), equalTo(busy));
		} finally {
			released.countDown();
			eventLoops.shutdownNow();
		}
	}
}
