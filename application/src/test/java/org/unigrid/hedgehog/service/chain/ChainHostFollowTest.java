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

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.Gap;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class ChainHostFollowTest {
	private static final String CLIENT = "client";
	private static final int STREAMS = 1000;
	private static final int TIMEOUT_SECONDS = 10;
	private static final long SECOND = 1000;

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

	private static ChainId chainId(final int number) {
		final byte[] id = new byte[ChainId.SIZE];

		id[0] = (byte) number;
		return new ChainId(id);
	}

	private static ChainEntry packet(final List<Entry> entries) {
		return ChainEntry.builder().entries(entries).build();
	}

	private static List<Object> written(final EmbeddedChannel stream) {
		return Stream.iterate(stream.<Object>readOutbound(), packet -> packet != null,
			packet -> stream.<Object>readOutbound()).toList();
	}

	/* Answers what the action returns on a thread of its own while another thread holds the monitor, so an action
	   that waits for the monitor fails the test instead of answering */
	@SneakyThrows
	private static <T> T whileHeld(final Object monitor, final Callable<T> action) {
		final ExecutorService threads = Executors.newFixedThreadPool(2);
		final CountDownLatch held = new CountDownLatch(1);
		final CountDownLatch released = new CountDownLatch(1);

		try {
			threads.submit(() -> {
				synchronized (monitor) {
					held.countDown();
					return released.await(3 * TIMEOUT_SECONDS, TimeUnit.SECONDS);
				}
			});

			assertThat(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
			return threads.submit(action).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} finally {
			released.countDown();
			threads.shutdownNow();
		}
	}

	/* Joins the first chain at a member through the member's end of its stream to the client, then follows it
	   through the client's end */
	private static boolean follow(final ChainFleet fleet, final String member, final int writer) {
		final JoinReply reply = fleet.host(member).join(fleet.stream(member, CLIENT), JoinChain.builder()
			.requestId(1).writer(ChainFleet.writer(writer)).chainId(fleet.chain()).build());

		assertThat(reply.getStatus(), is(JoinReply.Status.JOINED));
		return fleet.host(CLIENT).follow(fleet.chain(), fleet.stream(CLIENT, member), reply.getHeads());
	}

	private static List<Entry> offeredTo(final ChainHost client) {
		final List<Entry> seen = new ArrayList<>();

		client.addEntryListener((chainId, entry) -> seen.add(entry));
		return seen;
	}

	private static void floodFromAnotherClient(final ChainFleet fleet, final List<Entry> entries) {
		fleet.host("gn-1").receive(fleet.client("gn-1", 1), ChainEntry.builder().entries(entries).build());
		fleet.deliver();
	}

	@Example
	public void takesInWhatTheMembersFloodAndOffersItToItsListeners() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> seen = offeredTo(fleet.host(CLIENT));
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 3, ChainFleet.NOW);

		assertThat(follow(fleet, "gn-0", 7), is(true));
		floodFromAnotherClient(fleet, entries);

		assertThat(seen, equalTo(entries));
		assertThat(fleet.host(CLIENT).chain(fleet.chain()).orElseThrow().entries(), equalTo(entries));
	}

	@Example
	public void postsItsOwnEntriesThroughTheStreamsItFollows() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(7), fleet.chain(), 3, ChainFleet.NOW);

		assertThat(follow(fleet, "gn-0", 7), is(true));
		entries.forEach(entry -> assertThat(fleet.host(CLIENT).post(entry), is(Admission.ACCEPTED)));
		fleet.deliver();

		for (int member = 0; member < ChainFleet.MEMBERS; member++) {
			assertThat(fleet.host("gn-" + member).chain(fleet.chain()).orElseThrow().entries(), equalTo(entries));
		}
	}

	@Example
	public void startsTheFeedsOfTheJoinReplyAtTheirHeads() {
		final ChainFleet fleet = ChainFleet.group();
		final FeedWriter feed = new FeedWriter(ChainFleet.key(9), fleet.chain());
		final EmbeddedChannel writer = fleet.client("gn-1", 9);
		final List<Entry> earlier = ChainFleet.write(feed, 5, ChainFleet.NOW);
		final Entry next = ChainFleet.write(feed, 1, ChainFleet.NOW).get(0);

		fleet.host("gn-1").receive(writer, ChainEntry.builder().entries(earlier).build());
		fleet.deliver();
		assertThat(follow(fleet, "gn-0", 7), is(true));

		fleet.host("gn-1").receive(writer, ChainEntry.builder().entries(List.of(next)).build());
		fleet.deliver();

		assertThat(fleet.host(CLIENT).chain(fleet.chain()).orElseThrow().entries(), contains(next));
	}

	@Example
	public void takesNoMoreAnchorsThanItTakesWriters() {
		final ChainFleet fleet = ChainFleet.group();
		final Map<WriterId, Head> anchors = IntStream.rangeClosed(0, HostedChain.MAX_WRITERS).boxed()
			.collect(Collectors.toMap(ChainFleet::writer, number -> new Head(3, number)));

		assertThat(fleet.host(CLIENT).follow(fleet.chain(), fleet.stream(CLIENT, "gn-0"), anchors), is(true));
		assertThat(fleet.host(CLIENT).chain(fleet.chain()).orElseThrow().heads().size(), is(HostedChain.MAX_WRITERS));
	}

	@Example
	public void anchorsNoFeedAtAHeadNothingFollows() {
		final ChainFleet fleet = ChainFleet.group();
		final Head last = new Head(Long.MAX_VALUE - 1, 2);
		final Map<WriterId, Head> anchors = Map.of(ChainFleet.writer(1), new Head(Long.MAX_VALUE, 1),
			ChainFleet.writer(2), last);

		assertThat(fleet.host(CLIENT).follow(fleet.chain(), fleet.stream(CLIENT, "gn-0"), anchors), is(true));
		assertThat(fleet.host(CLIENT).chain(fleet.chain()).orElseThrow().heads(),
			equalTo(Map.of(ChainFleet.writer(2), last)));
	}

	@Example
	public void countsNoWriterForAnAnchorItRefused() {
		final ChainFleet fleet = ChainFleet.group();
		final Map<WriterId, Head> refused = IntStream.range(0, HostedChain.MAX_WRITERS).boxed()
			.collect(Collectors.toMap(ChainFleet::writer, number -> new Head(Long.MAX_VALUE, number)));
		final Map<WriterId, Head> taken = Map.of(ChainFleet.writer(HostedChain.MAX_WRITERS), new Head(3, 1));

		assertThat(fleet.host(CLIENT).follow(fleet.chain(), fleet.stream(CLIENT, "gn-0"), refused), is(true));
		assertThat(fleet.host(CLIENT).follow(fleet.chain(), fleet.stream(CLIENT, "gn-1"), taken), is(true));
		assertThat(fleet.host(CLIENT).chain(fleet.chain()).orElseThrow().heads(), equalTo(taken));
	}

	@Example
	public void passesNothingOn() {
		final ChainFleet fleet = ChainFleet.group();

		assertThat(follow(fleet, "gn-0", 7), is(true));
		assertThat(follow(fleet, "gn-1", 7), is(true));
		floodFromAnotherClient(fleet, ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 3, ChainFleet.NOW));

		assertThat(fleet.host(CLIENT).chain(fleet.chain()).orElseThrow().entries().size(), is(3));
		assertThat(fleet.deliveries.stream().filter(delivery -> delivery.from().equals(CLIENT)).toList(),
			is(empty()));
	}

	@Example
	public void forgetsAChainWhenItsLastStreamCloses() {
		final ChainFleet fleet = ChainFleet.group();

		assertThat(follow(fleet, "gn-0", 7), is(true));
		assertThat(follow(fleet, "gn-1", 7), is(true));

		fleet.stream(CLIENT, "gn-0").close();
		assertThat(fleet.host(CLIENT).chain(fleet.chain()).isPresent(), is(true));

		fleet.stream(CLIENT, "gn-1").close();
		assertThat(fleet.host(CLIENT).chain(fleet.chain()), equalTo(Optional.empty()));
	}

	@Example
	public void followsAFewChainsAtMost() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel member = fleet.stream(CLIENT, "gn-0");
		final List<ChainId> chains = new ArrayList<>();

		for (int number = 0; number <= ChainHost.MAX_FOLLOWED; number++) {
			final byte[] id = new byte[ChainId.SIZE];

			id[0] = (byte) (number + 1);
			chains.add(new ChainId(id));
		}

		for (int number = 0; number < ChainHost.MAX_FOLLOWED; number++) {
			assertThat(fleet.host(CLIENT).follow(chains.get(number), member, Map.of()), is(true));
		}

		assertThat(fleet.host(CLIENT).follow(chains.get(ChainHost.MAX_FOLLOWED), member, Map.of()), is(false));
		assertThat(fleet.host(CLIENT).follow(chains.get(0), fleet.stream(CLIENT, "gn-1"), Map.of()), is(true));

		fleet.host(CLIENT).unfollow(chains.get(1));
		assertThat(fleet.host(CLIENT).follow(chains.get(ChainHost.MAX_FOLLOWED), member, Map.of()), is(true));
	}

	@Example
	public void followsNothingWhileTheProxyIsOff() {
		final ChainFleet fleet = ChainFleet.group();

		fleet.settings.set(Optional.empty());

		assertThat(fleet.host(CLIENT).follow(fleet.chain(), fleet.stream(CLIENT, "gn-0"), Map.of()), is(false));
	}

	@Example
	public void believesRefusalsOnlyFromTheStreamsItFollows() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainHost client = fleet.host(CLIENT);
		final Entry entry = ChainFleet.entries(ChainFleet.key(7), fleet.chain(), 1, ChainFleet.NOW).get(0);
		final EntryRefused refusal = EntryRefused.of(entry, Admission.FULL);

		assertThat(follow(fleet, "gn-0", 7), is(true));

		client.refused(new EmbeddedChannel(), refusal);
		assertThat(client.pauses().isPaused(fleet.chain(), ChainFleet.writer(7)), is(false));

		client.refused(fleet.stream(CLIENT, "gn-0"), refusal);
		assertThat(client.pauses().refusedFrom(fleet.chain(), ChainFleet.writer(7)), equalTo(OptionalLong.of(0)));
	}

	@Example
	public void reportsTheGapsOfAFollowedChain() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final List<Gap> gaps = new ArrayList<>();
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 4, ChainFleet.NOW);

		client.addGapListener((chainId, gap) -> gaps.add(gap));
		assertThat(follow(fleet, "gn-0", 7), is(true));
		client.receive(fleet.stream(CLIENT, "gn-0"), ChainEntry.builder().entries(entries.subList(1, 4)).build());

		fleet.clock.addAndGet(31_000);
		client.maintain();

		assertThat(gaps, contains(new Gap(ChainFleet.writer(1), 0, 3, Set.of(Fingerprint.NONE))));
	}

	@Example
	public void followsTheFourthChainAndRefusesTheFifth() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final EmbeddedChannel member = fleet.stream(CLIENT, "gn-0");
		final EmbeddedChannel second = fleet.stream(CLIENT, "gn-1");

		for (int number = 1; number <= 4; number++) {
			assertThat(client.follow(chainId(number), member, Map.of()), is(true));
		}

		assertThat(client.follow(chainId(5), member, Map.of()), is(false));
		assertThat(client.chain(chainId(5)), equalTo(Optional.empty()));

		assertThat(client.follow(chainId(4), second, Map.of()), is(true));
		assertThat(client.hosted(chainId(4)).orElseThrow().getMemberStreams(), contains(member, second));
	}

	@Example
	public void followsNothingWhileTheProxyIsOffAndLeavesNoTrace() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final CountedStream member = new CountedStream();
		final ChainId chainId = fleet.chain();

		fleet.settings.set(Optional.empty());

		assertThat(client.follow(chainId, member, Map.of(ChainFleet.writer(1), new Head(3, 1))), is(false));
		assertThat(client.chain(chainId), equalTo(Optional.empty()));
		assertThat(member.listeners(), is(0));
	}

	@Example
	public void followsNothingWhileTheClockReadsBeforeTheOrigin() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final ChainId chainId = fleet.chain();
		final EmbeddedChannel member = fleet.stream(CLIENT, "gn-0");

		fleet.clock.set(-1);
		assertThat(client.follow(chainId, member, Map.of()), is(false));
		assertThat(client.chain(chainId), equalTo(Optional.empty()));

		fleet.clock.set(0);
		assertThat(client.follow(chainId, member, Map.of()), is(true));
	}

	@Example
	public void followsNoChainItHostsItself() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost member = fleet.host("gn-0");
		final HostedChain hosted = member.hosted(fleet.chain()).orElseThrow();
		final List<Channel> streams = hosted.getMemberStreams();
		final CountedStream stream = new CountedStream();

		assertThat(member.follow(fleet.chain(), stream, Map.of(ChainFleet.writer(1), new Head(3, 1))), is(false));

		assertThat(member.hosted(fleet.chain()).orElseThrow(), is(sameInstance(hosted)));
		assertThat(hosted.getMemberStreams(), equalTo(streams));
		assertThat(hosted.getChain().heads(), equalTo(Map.of()));
		assertThat(stream.listeners(), is(0));
	}

	@Example
	public void followsNothingThroughAStreamThatIsClosed() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final CountedStream closed = new CountedStream();

		closed.close();

		assertThat(client.follow(fleet.chain(), closed, Map.of(ChainFleet.writer(1), new Head(3, 1))), is(false));
		assertThat(client.chain(fleet.chain()), equalTo(Optional.empty()));
		assertThat(closed.listeners(), is(0));
	}

	@Example
	public void answersFalseForNoChainOrNoStreamAndAnchorsNothingForNoAnchors() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final EmbeddedChannel member = fleet.stream(CLIENT, "gn-0");

		assertThat(client.follow(null, member, Map.of()), is(false));
		assertThat(client.follow(fleet.chain(), null, Map.of()), is(false));
		assertThat(client.chain(fleet.chain()), equalTo(Optional.empty()));

		client.unfollow(null);

		assertThat(client.follow(fleet.chain(), member, null), is(true));
		assertThat(client.chain(fleet.chain()).orElseThrow().heads(), equalTo(Map.of()));
	}

	@Example
	public void skipsTheAnchorsThatNameNoWriterOrNoHead() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final Map<WriterId, Head> anchors = new HashMap<>();

		anchors.put(null, new Head(3, 1));
		anchors.put(ChainFleet.writer(1), null);
		anchors.put(ChainFleet.writer(2), new Head(3, 2));

		assertThat(client.follow(fleet.chain(), fleet.stream(CLIENT, "gn-0"), anchors), is(true));
		assertThat(client.chain(fleet.chain()).orElseThrow().heads(),
			equalTo(Map.of(ChainFleet.writer(2), new Head(3, 2))));
	}

	@Example
	public void anchorsAFeedAtItsFirstEntry() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final EmbeddedChannel member = fleet.stream(CLIENT, "gn-0");
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 2, ChainFleet.NOW);
		final Head first = new Head(0, entries.get(0).hash());

		assertThat(client.follow(fleet.chain(), member, Map.of(ChainFleet.writer(1), first)), is(true));
		assertThat(client.chain(fleet.chain()).orElseThrow().heads(), equalTo(Map.of(ChainFleet.writer(1), first)));

		client.receive(member, packet(entries));
		assertThat(client.chain(fleet.chain()).orElseThrow().entries(), equalTo(entries.subList(1, 2)));
	}

	@Example
	public void takesTheEntriesOfTheWritersItAnchoredButNoneOfANewOneOnceTheyFillIt() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final EmbeddedChannel member = fleet.stream(CLIENT, "gn-0");
		final List<Entry> anchored = ChainFleet.entries(ChainFleet.key(0), fleet.chain(), 5, ChainFleet.NOW);
		final Entry stranger = ChainFleet.entries(ChainFleet.key(HostedChain.MAX_WRITERS), fleet.chain(), 1,
			ChainFleet.NOW).get(0);
		final Map<WriterId, Head> anchors = new HashMap<>(IntStream.range(1, HostedChain.MAX_WRITERS).boxed()
			.collect(Collectors.toMap(ChainFleet::writer, number -> new Head(3, number))));

		anchors.put(ChainFleet.writer(0), new Head(3, anchored.get(3).hash()));

		assertThat(client.follow(fleet.chain(), member, anchors), is(true));
		client.receive(member, packet(List.of(stranger, anchored.get(4))));

		assertThat(client.chain(fleet.chain()).orElseThrow().entries(), contains(anchored.get(4)));
		assertThat(client.chain(fleet.chain()).orElseThrow().heads().size(), is(HostedChain.MAX_WRITERS));
	}

	/* The second follow waits for the monitor of the chain while an entry takes the last writer slot, so an anchor
	   that counted the writers before it had the monitor would take one slot too many */
	@Example
	public void countsAnAnchorAgainstTheWritersThatEntriesTookWhileItWaitedForTheChain() throws InterruptedException {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final EmbeddedChannel second = fleet.stream(CLIENT, "gn-1");
		final Map<WriterId, Head> anchors = IntStream.range(0, HostedChain.MAX_WRITERS - 1).boxed()
			.collect(Collectors.toMap(ChainFleet::writer, number -> new Head(3, number)));
		final Entry last = ChainFleet.entries(ChainFleet.key(HostedChain.MAX_WRITERS - 1), fleet.chain(), 1,
			ChainFleet.NOW).get(0);
		final WriterId late = ChainFleet.writer(HostedChain.MAX_WRITERS);
		final AtomicBoolean followed = new AtomicBoolean();

		assertThat(client.follow(fleet.chain(), fleet.stream(CLIENT, "gn-0"), anchors), is(true));

		final HostedChain replica = client.hosted(fleet.chain()).orElseThrow();
		final Thread following = new Thread(() -> followed.set(client.follow(fleet.chain(), second,
			Map.of(late, new Head(3, 1)))));

		synchronized (replica.getChain()) {
			following.start();
			await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).pollInterval(1, TimeUnit.MILLISECONDS)
				.until(() -> following.getState() == Thread.State.BLOCKED);
			assertThat(replica.offer(last).orElseThrow().admission(), is(Admission.ACCEPTED));
		}

		following.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

		assertThat(followed.get(), is(true));
		assertThat(replica.getChain().heads().containsKey(late), is(false));
		assertThat(replica.getChain().heads().size(), is(HostedChain.MAX_WRITERS));
	}

	@Example
	public void keepsWhatItHoldsWhenItFollowsTheChainAgain() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 3, ChainFleet.NOW);
		final Head first = new Head(0, entries.get(0).hash());

		assertThat(client.follow(fleet.chain(), fleet.stream(CLIENT, "gn-0"), Map.of(ChainFleet.writer(1), first)),
			is(true));
		client.receive(fleet.stream(CLIENT, "gn-0"), packet(entries.subList(1, 2)));

		assertThat(client.follow(fleet.chain(), fleet.stream(CLIENT, "gn-1"),
			Map.of(ChainFleet.writer(1), new Head(7, 7), ChainFleet.writer(2), new Head(3, 2))), is(true));
		assertThat(client.chain(fleet.chain()).orElseThrow().entries(), equalTo(entries.subList(1, 2)));
		assertThat(client.chain(fleet.chain()).orElseThrow().heads(), equalTo(Map.of(ChainFleet.writer(1),
			new Head(1, entries.get(1).hash()), ChainFleet.writer(2), new Head(3, 2))));

		client.receive(fleet.stream(CLIENT, "gn-1"), packet(entries.subList(2, 3)));
		assertThat(client.chain(fleet.chain()).orElseThrow().entries(), equalTo(entries.subList(1, 3)));
	}

	@Example
	public void takesEntriesFromEveryStreamItFollowsAndNoneFromAnother() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 2, ChainFleet.NOW);

		assertThat(follow(fleet, "gn-0", 7), is(true));
		assertThat(follow(fleet, "gn-1", 7), is(true));

		assertThat(whileHeld(client.chain(fleet.chain()).orElseThrow(),
			() -> client.receive(fleet.stream(CLIENT, "gn-2"), packet(entries))), is(empty()));
		assertThat(client.chain(fleet.chain()).orElseThrow().entries(), is(empty()));

		client.receive(fleet.stream(CLIENT, "gn-1"), packet(entries.subList(0, 1)));
		client.receive(fleet.stream(CLIENT, "gn-0"), packet(entries.subList(1, 2)));
		assertThat(client.chain(fleet.chain()).orElseThrow().entries(), equalTo(entries));
		assertThat(written(fleet.stream(CLIENT, "gn-0")), is(empty()));
		assertThat(written(fleet.stream(CLIENT, "gn-1")), is(empty()));
	}

	@Example
	public void takesNoEntriesOfAFollowedStreamOverItsBudget() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final EmbeddedChannel member = fleet.stream(CLIENT, "gn-0");
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 2, ChainFleet.NOW);

		assertThat(follow(fleet, "gn-0", 7), is(true));
		client.receive(member, packet(Collections.nCopies(StreamBudgets.Kind.ENTRIES.getPerSecond(),
			entries.get(0))));

		assertThat(whileHeld(client.chain(fleet.chain()).orElseThrow(),
			() -> client.receive(member, packet(entries.subList(1, 2)))), is(empty()));
		assertThat(client.chain(fleet.chain()).orElseThrow().entries(), equalTo(entries.subList(0, 1)));

		fleet.clock.addAndGet(SECOND);
		client.receive(member, packet(entries.subList(1, 2)));
		assertThat(client.chain(fleet.chain()).orElseThrow().entries(), equalTo(entries));
	}

	@Example
	public void believesARefusalFromEveryStreamItFollowsAndFromNoOtherMember() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final Entry entry = ChainFleet.entries(ChainFleet.key(7), fleet.chain(), 1, ChainFleet.NOW).get(0);
		final EntryRefused refusal = EntryRefused.of(entry, Admission.FULL);

		assertThat(follow(fleet, "gn-0", 7), is(true));
		assertThat(follow(fleet, "gn-1", 7), is(true));

		client.refused(fleet.stream(CLIENT, "gn-2"), refusal);
		assertThat(client.pauses().isPaused(fleet.chain(), ChainFleet.writer(7)), is(false));

		client.refused(fleet.stream(CLIENT, "gn-1"), refusal);
		assertThat(client.pauses().refusedFrom(fleet.chain(), ChainFleet.writer(7)), equalTo(OptionalLong.of(0)));
	}

	@Example
	public void postsEachEntryOnceOnEveryStreamItFollowsThatTakesIt() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final List<Entry> seen = offeredTo(client);
		final Entry entry = ChainFleet.entries(ChainFleet.key(7), fleet.chain(), 1, ChainFleet.NOW).get(0);
		final EmbeddedChannel full = ChainFleet.unwritable(fleet.stream(CLIENT, "gn-2"));

		assertThat(follow(fleet, "gn-0", 7), is(true));
		assertThat(follow(fleet, "gn-1", 7), is(true));
		assertThat(client.follow(fleet.chain(), full, Map.of()), is(true));
		assertThat(client.post(entry), is(Admission.ACCEPTED));

		assertThat(written(fleet.stream(CLIENT, "gn-0")), contains(packet(List.of(entry))));
		assertThat(written(fleet.stream(CLIENT, "gn-1")), contains(packet(List.of(entry))));
		assertThat(written(full), is(empty()));
		assertThat(client.chain(fleet.chain()).orElseThrow().entries(), contains(entry));
		assertThat(seen, is(empty()));
	}

	@Example
	public void neitherHostsNorPlacesNorAnnouncesAChainItFollows() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final EmbeddedChannel member = fleet.stream(CLIENT, "gn-0");

		client.refresh();
		assertThat(follow(fleet, "gn-0", 7), is(true));
		client.receive(member, packet(ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 2, ChainFleet.NOW)));
		client.reconcileRound();

		assertThat(client.hostedChains(), is(empty()));
		assertThat(client.retiredChains(), is(empty()));
		assertThat(written(member), is(empty()));
		assertThat(client.join(new EmbeddedChannel(), JoinChain.builder().requestId(1).writer(ChainFleet.writer(2))
			.chainId(fleet.chain()).build()).getStatus(), is(JoinReply.Status.UNKNOWN_CHAIN));
		assertThat(client.join(new EmbeddedChannel(), JoinChain.builder().requestId(2).writer(ChainFleet.writer(2))
			.build()).getStatus(), is(JoinReply.Status.UNKNOWN_CHAIN));
	}

	@Example
	public void trimsAFollowedChain() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);

		assertThat(follow(fleet, "gn-0", 7), is(true));
		floodFromAnotherClient(fleet, ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 3, ChainFleet.NOW));
		assertThat(client.chain(fleet.chain()).orElseThrow().entries().size(), is(3));

		fleet.clock.addAndGet(31_000);
		client.maintain();

		assertThat(client.chain(fleet.chain()).orElseThrow().entries(), is(empty()));
	}

	@Example
	public void forgetsAChainItUnfollowsAndNothingElse() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final EmbeddedChannel member = fleet.stream(CLIENT, "gn-0");
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(7), fleet.chain(), 1, ChainFleet.NOW);

		assertThat(follow(fleet, "gn-0", 7), is(true));
		assertThat(client.follow(chainId(1), member, Map.of()), is(true));
		client.receive(member, packet(entries));
		assertThat(client.chain(fleet.chain()).orElseThrow().entries(), equalTo(entries));

		client.unfollow(fleet.chain());
		client.unfollow(fleet.chain());

		assertThat(client.chain(fleet.chain()), equalTo(Optional.empty()));
		assertThat(client.chain(chainId(1)).isPresent(), is(true));
		assertThat(client.post(entries.get(0)), is(Admission.WRONG_CHAIN));
		assertThat(fleet.host("gn-0").hosted(fleet.chain()).orElseThrow().isAttached(ChainFleet.writer(7)),
			is(true));

		assertThat(client.follow(fleet.chain(), member, Map.of()), is(true));
		assertThat(client.chain(fleet.chain()).orElseThrow().entries(), is(empty()));
	}

	@Example
	public void watchesAStreamOnceHoweverManyChainsItIsFollowedFor() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final CountedStream member = new CountedStream();

		for (int number = 1; number <= ChainHost.MAX_FOLLOWED; number++) {
			assertThat(client.follow(chainId(number), member, Map.of()), is(true));
			assertThat(client.follow(chainId(number), member, Map.of()), is(true));
		}

		assertThat(member.listeners(), is(1));
		assertThat(client.hosted(chainId(1)).orElseThrow().getMemberStreams(), contains(member));

		member.close();

		for (int number = 1; number <= ChainHost.MAX_FOLLOWED; number++) {
			assertThat(client.chain(chainId(number)), equalTo(Optional.empty()));
		}
	}

	@Example
	public void holdsNothingForStreamsThatClose() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final List<CountedStream> streams = IntStream.range(0, STREAMS).mapToObj(number -> new CountedStream())
			.toList();

		streams.forEach(stream -> assertThat(client.follow(fleet.chain(), stream, Map.of()), is(true)));
		assertThat(client.hosted(fleet.chain()).orElseThrow().getMemberStreams(), contains(streams.toArray()));

		streams.subList(1, STREAMS).forEach(EmbeddedChannel::close);
		assertThat(client.hosted(fleet.chain()).orElseThrow().getMemberStreams(), contains(streams.get(0)));

		streams.get(0).close();
		assertThat(client.chain(fleet.chain()), equalTo(Optional.empty()));
		assertThat(streams.stream().map(CountedStream::listeners).toList(), everyItem(is(1)));
	}

	@Example
	public void forgetsAChainWhenAStreamItAlsoJoinedThroughCloses() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost member = fleet.host("gn-0");
		final EmbeddedChannel stream = new EmbeddedChannel();

		assertThat(member.join(stream, JoinChain.builder().requestId(1).writer(ChainFleet.writer(1))
			.chainId(fleet.chain()).build()).getStatus(), is(JoinReply.Status.JOINED));
		assertThat(member.follow(chainId(9), stream, Map.of()), is(true));

		stream.close();

		assertThat(member.chain(chainId(9)), equalTo(Optional.empty()));
		assertThat(member.hosted(fleet.chain()).orElseThrow().isAttached(ChainFleet.writer(1)), is(false));
	}
}
