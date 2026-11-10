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

package org.unigrid.hedgehog.model.network;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import mockit.Verifications;
import net.jqwik.api.Example;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.model.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.ProtectedInterceptor;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.gridnode.GridnodeSignature;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.server.TestServer;
import org.unigrid.hedgehog.service.ledger.LedgerStreams;
import static org.unigrid.hedgehog.model.gridnode.GridnodeFixtures.copyOf;
import static org.unigrid.hedgehog.model.gridnode.GridnodeFixtures.signed;

@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class }, scan = false)
public class TopologyTest extends BaseMockedWeldTest {
	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;

	@Inject private Topology topology;

	@BeforeTry
	public void before() {
		TestServer.mockProperties();
	}

	@Example
	public void shouldFindNodeByItsChangedAddress() {
		final InetSocketAddress original = new InetSocketAddress("127.0.100.1", 1000);
		final Node changed = Node.builder().address(new InetSocketAddress("127.0.100.2", 1000)).build();

		topology.addNode(Node.builder().address(original).build());
		topology.changeAddress(Node.builder().address(original).build(), changed.getAddress());

		assertThat(topology.containsNode(Node.builder().address(original).build()), is(false));
		assertThat(topology.containsNode(changed), is(true));
		assertThat(topology.removeNode(changed), is(true));
	}

	/* A peer that moves onto the address of a node that is known already cannot be filed there twice. The node must not
	   vanish with its connection left behind and its entry in the channel map, with no node to answer for them: it is
	   either kept where it was or let go of with both. */
	@Example
	public void shouldLeaveNoConnectionWithoutAnOwnerWhenANodeMovesOntoAKnownAddress() {
		final EmbeddedChannel movingStream = new EmbeddedChannel();
		final EmbeddedChannel knownStream = new EmbeddedChannel();
		final Node moving = NodeConnections.connected("127.0.100.21:1000", movingStream);
		final Node known = NodeConnections.connected("127.0.100.22:1000", knownStream);
		final Node movingAsFiled = NodeConnections.unconnected("127.0.100.21:1000");

		try {
			assertThat(topology.addNode(moving), is(true));
			assertThat(topology.addNode(known), is(true));
			topology.getChannels().set(movingStream, moving);
			topology.getChannels().set(knownStream, known);

			topology.changeAddress(movingAsFiled, known.getAddress());

			final boolean kept = topology.containsNode(movingAsFiled);
			final boolean letGo = !movingStream.isOpen() && topology.getChannels().get(movingStream).isEmpty();

			assertThat("the node is gone but its connection is open or its channel entry is left", kept || letGo,
				is(true));
			assertThat(topology.containsNode(known), is(true));
			assertThat(knownStream.isOpen(), is(true));
			assertThat(topology.getChannels().get(knownStream), is(Optional.of(known)));
		} finally {
			topology.removeNode(movingAsFiled);
			topology.removeNode(known);
			topology.getChannels().remove(movingStream);
			topology.getChannels().remove(knownStream);
		}
	}

	/* A name that resolves slowly, or not at all, takes as long as the lookup does. Every event loop that reads the
	   topology would wait that long if the lookup ran under its lock, so a read and an add must go through while it
	   blocks. The lookup is held at the first seed, and the fake takes it on only for as long as the example runs. */
	private boolean servesWhileASeedLookupBlocks(Consumer<Set<Node>> take, Node added) throws Exception {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final ExecutorService threads = Executors.newFixedThreadPool(2);

		new MockUp<Node>() {
			@Mock
			@SneakyThrows
			public /* static */ Node fromAddress(String address) {
				entered.countDown();
				release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
				return Node.fromURI(new URI(null, address, null, null, null).parseServerAuthority());
			}
		};

		try {
			final Future<?> lookingUp = threads.submit(() -> take.accept(Topology.resolveSeeds()));
			assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));

			final Future<Boolean> serving = threads.submit(() -> {
				topology.cloneNodes();
				return topology.addNode(added);
			});

			try {
				return serving.get(1, TimeUnit.SECONDS);
			} catch (TimeoutException ex) {
				return false;
			} finally {
				release.countDown();
				lookingUp.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
		} finally {
			release.countDown();
			threads.shutdownNow();
		}
	}

	@Example
	public void shouldReadAndAddWhileASeedLookupBlocksOnAddingTheSeeds() throws Exception {
		final Node added = Node.builder().address(new InetSocketAddress("127.0.100.5", 1000)).build();
		final boolean served = servesWhileASeedLookupBlocks(topology::addSeeds, added);

		assertThat("the topology took no read and add for a second while a seed was looked up", served, is(true));
		assertThat(topology.containsNode(added), is(true));
		assertThat(topology.removeNode(added), is(true));
	}

	@Example
	public void shouldReadAndAddWhileASeedLookupBlocksOnRepopulating() throws Exception {
		final Node added = Node.builder().address(new InetSocketAddress("127.0.100.6", 1000)).build();
		final boolean served = servesWhileASeedLookupBlocks(topology::repopulate, added);

		assertThat("the topology took no read and add for a second while a seed was looked up", served, is(true));
		assertThat(topology.cloneNodes().size(), is(Network.getSeeds().length));
	}

	/* The seeds are looked up outside the lock, but a seed that resolved is configured under it as before, so a writer
	   that holds the lock keeps it from the seeds until it lets go */
	@Example
	public void shouldAddTheResolvedSeedsOnlyOnceAWriterLetsGoOfTheLock() throws Exception {
		final Node seed = Node.fromAddress(Network.getSeeds()[0]);
		final CountDownLatch writing = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final ExecutorService threads = Executors.newFixedThreadPool(2);
		boolean addedWhileWriting = true;

		try {
			topology.addSeeds(Topology.resolveSeeds());
			threads.submit(() -> topology.modifyNode(seed, node -> {
				writing.countDown();
				awaitQuietly(release);
			}));
			assertThat(writing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));

			final Future<Set<Node>> adding = threads.submit(
				() -> topology.addSeeds(Topology.resolveSeeds()));

			try {
				adding.get(300, TimeUnit.MILLISECONDS);
			} catch (TimeoutException ex) {
				addedWhileWriting = false;
			}

			release.countDown();
			assertThat("the seeds were added while another thread wrote to the topology", addedWhileWriting, is(false));
			assertThat(adding.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size(), is(Network.getSeeds().length));
		} finally {
			release.countDown();
			threads.shutdownNow();
		}
	}

	@SneakyThrows
	private static void awaitQuietly(CountDownLatch latch) {
		latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static final long NOW = 1_800_000_000_000L;
	private static final long MAX_AGE = GridnodeSignature.MAX_AGE.toMillis();
	private static final long MAX_SKEW = GridnodeSignature.MAX_SKEW.toMillis();

	@Example
	public void shouldStoreAFreshSignedEntry() throws Exception {
		final Gridnode entry = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);

		assertThat(topology.offerGridnode(entry, NOW), is(true));
		assertThat(topology.findGridnode(entry.getId()).get().getSignature(), is(entry.getSignature()));
	}

	@Example
	public void shouldReplaceAnEntryWithANewerOne() throws Exception {
		final Signature key = new Signature();
		final Gridnode older = signed(key, Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);
		final Gridnode newer = signed(key, Gridnode.Status.INACTIVE, "10.0.0.1:1", NOW + 1);

		topology.offerGridnode(older, NOW);

		assertThat(topology.offerGridnode(newer, NOW + 1), is(true));
		assertThat(topology.findGridnode(older.getId()).get().getStatus(), is(Gridnode.Status.INACTIVE));
	}

	@Example
	public void shouldNotLetARecordedOlderEntryRevertANewerOne() throws Exception {
		final Signature key = new Signature();
		final Gridnode active = signed(key, Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);
		final Gridnode inactive = signed(key, Gridnode.Status.INACTIVE, "10.0.0.1:1", NOW + 1000);

		topology.offerGridnode(active, NOW);
		topology.offerGridnode(inactive, NOW + 1000);

		assertThat(topology.offerGridnode(active, NOW + 1000), is(false));
		assertThat(topology.findGridnode(active.getId()).get().getStatus(), is(Gridnode.Status.INACTIVE));
	}

	@Example
	public void shouldNotReplaceAnEntryByAnotherOneOfTheSameTime() throws Exception {
		final Signature key = new Signature();
		final Gridnode first = signed(key, Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);
		final Gridnode rival = signed(key, Gridnode.Status.ACTIVE, "10.9.9.9:1", NOW);

		topology.offerGridnode(first, NOW);

		assertThat(topology.offerGridnode(rival, NOW), is(false));
		assertThat(topology.findGridnode(first.getId()).get().getHostName(), is("10.0.0.1:1"));
	}

	@Example
	public void shouldRefuseExpiredFutureForgedAndUnsignedEntries() throws Exception {
		final Signature key = new Signature();
		final Gridnode good = signed(key, Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);

		assertThat(topology.offerGridnode(signed(key, Gridnode.Status.ACTIVE, "h:1", NOW - MAX_AGE), NOW), is(false));
		assertThat(topology.offerGridnode(signed(key, Gridnode.Status.ACTIVE, "h:1", NOW + MAX_SKEW + 1), NOW),
			is(false));
		assertThat(topology.offerGridnode(copyOf(good, g -> g.setHostName("10.6.6.6:1")), NOW), is(false));
		assertThat(topology.offerGridnode(copyOf(good, g -> g.setSignature(null)), NOW), is(false));
		assertThat(topology.findGridnode(good.getId()), is(Optional.empty()));
	}

	@Example
	public void shouldPurgeExpiredEntriesButKeepTheOwnOne() throws Exception {
		final Gridnode own = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);
		final Gridnode stale = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.2:1", NOW);
		final Gridnode fresh = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.3:1", NOW + MAX_AGE);

		topology.offerGridnode(own, NOW);
		topology.offerGridnode(stale, NOW);
		topology.offerGridnode(fresh, NOW + MAX_AGE - 1);

		topology.purgeGridnodes(NOW + MAX_AGE, Optional.of(own.getId()));

		assertThat(topology.findGridnode(own.getId()).isPresent(), is(true));
		assertThat(topology.findGridnode(stale.getId()).isPresent(), is(false));
		assertThat(topology.findGridnode(fresh.getId()).isPresent(), is(true));
	}

	private static final int OFFERING_THREADS = 4;
	private static final int OFFERED_BY_EACH = 75;
	private static final long TIMEOUT_SECONDS = 60;

	/* A key pair takes a dozen tries to find, so each thread signs its own entries before the start: the signing then
	   runs in parallel and stays out of the race */
	private Callable<List<Gridnode>> signThenOffer(CyclicBarrier start) {
		return () -> {
			final List<Gridnode> entries = new ArrayList<>();

			for (int i = 0; i < OFFERED_BY_EACH; i++) {
				entries.add(signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW));
			}

			start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return entries.stream().filter(gridnode -> topology.offerGridnode(gridnode, NOW)).toList();
		};
	}

	/* Peers offer entries on several event loops at once while the topology thread purges and copies the set, so no
	   offer may change the set under an iteration or lose an entry that another offer stored */
	@Example
	public void shouldHoldEveryEntryOfferedOnSeveralThreadsWhileTheSetIsPurgedAndCopied() throws Exception {
		final ExecutorService threads = Executors.newFixedThreadPool(OFFERING_THREADS + 1);
		final CyclicBarrier start = new CyclicBarrier(OFFERING_THREADS + 1);
		final AtomicBoolean offering = new AtomicBoolean(true);
		final List<Gridnode> taken = new ArrayList<>();

		try {
			final Future<Integer> purges = threads.submit(() -> {
				int rounds = 0;

				start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

				while (offering.get()) {
					topology.purgeGridnodes(NOW, Optional.empty());
					topology.cloneGridnode();
					rounds++;
				}

				return rounds;
			});

			final List<Future<List<Gridnode>>> offers = IntStream.range(0, OFFERING_THREADS)
				.mapToObj(i -> threads.submit(signThenOffer(start))).toList();

			try {
				for (Future<List<Gridnode>> offer : offers) {
					taken.addAll(offer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
				}
			} finally {
				offering.set(false);
			}

			assertThat(purges.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), greaterThan(0));
		} finally {
			threads.shutdownNow();
		}

		final Set<String> held = topology.cloneGridnode().stream().map(Gridnode::getId).collect(Collectors.toSet());

		assertThat(taken.size(), is(OFFERING_THREADS * OFFERED_BY_EACH));
		assertThat(taken.stream().filter(gridnode -> held.contains(gridnode.getId())).count(),
			is((long) OFFERING_THREADS * OFFERED_BY_EACH));
	}

	@Property(tries = 1)
	public void shouldSendToEveryConnectedNodeButTheExcludedOne(@Mocked Connection first, @Mocked Connection second,
		@Mocked Channel firstChannel, @Mocked Channel secondChannel) {

		final Node excluded = Node.builder().address(new InetSocketAddress("127.0.100.1", 1000))
			.connection(Optional.of(first)).build();
		final Node other = Node.builder().address(new InetSocketAddress("127.0.100.2", 1000))
			.connection(Optional.of(second)).build();
		final Packet packet = PublishGridnode.builder().build();

		new Expectations() {{
			first.getChannel(); result = firstChannel; minTimes = 0;
			second.getChannel(); result = secondChannel; minTimes = 0;
		}};

		topology.addNode(excluded);
		topology.addNode(other);
		Topology.sendAllExcept(packet, topology, Optional.of(excluded));

		new Verifications() {{
			firstChannel.writeAndFlush(packet); times = 0;
			secondChannel.writeAndFlush(packet); times = 1;
		}};
	}

	/* A packet that not every node could read goes on those of the streams of a node that take it */
	@Example
	public void shouldSendAPacketThatNotEveryNodeCouldReadOnTheStreamsThatTakeIt() {
		final EmbeddedChannel dialed = new EmbeddedChannel();
		final EmbeddedChannel opened = new EmbeddedChannel();
		final Node node = Node.builder().address(new InetSocketAddress("127.0.100.3", 1000))
			.connection(Optional.of(LedgerStreams.connection(dialed)))
			.inbound(Optional.of(LedgerStreams.connection(opened))).build();
		final Packet packet = PublishGridnode.builder().build();

		assertThat(topology.addNode(node), is(true));
		Topology.sendAll(packet, topology, channel -> channel == dialed, Optional.empty());
		assertThat(dialed.<Packet>readOutbound(), is(packet));
		assertThat(opened.<Packet>readOutbound(), nullValue());

		Topology.sendAll(packet, topology, channel -> channel == opened, Optional.empty());
		assertThat(dialed.<Packet>readOutbound(), nullValue());
		assertThat(opened.<Packet>readOutbound(), is(packet));
		topology.removeNode(node);
	}
}
