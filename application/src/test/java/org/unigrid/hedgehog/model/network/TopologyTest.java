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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.UriBuilder;
import java.net.InetSocketAddress;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Expectations;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import mockit.Verifications;
import net.jqwik.api.Example;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.model.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.Lock;
import org.unigrid.hedgehog.model.cdi.Protected;
import org.unigrid.hedgehog.model.cdi.ProtectedInterceptor;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.gridnode.GridnodeSignature;
import org.unigrid.hedgehog.model.network.codec.PublishPeersDecoder;
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

	private static final int ROUNDS = 50;
	private static final String ROTATING_NETWORK = "10.77.";

	/* A seed name whose address is a new one in each round, as a name of a round robin or of a host that moves is, is
	   one seed. An address it gave earlier is dialed as a seed no longer, and leaves the topology, so neither the nodes
	   that are dialed in every round and pass the limit of the topology, nor the nodes, grow with the rounds. The fake
	   gives a new address for the first seed only while the example runs. */
	@Example
	public void shouldNotConfigureANodeForEveryAddressThatASeedNameResolvedToInTurn() {
		final String name = Network.getSeeds()[0];
		final AtomicBoolean rotating = new AtomicBoolean(true);
		final AtomicInteger lookups = new AtomicInteger();

		new MockUp<Node>() {
			@Mock
			@SneakyThrows
			public /* static */ Node fromAddress(String address) {
				final String resolved = rotating.get() && name.equals(address) ? ROTATING_NETWORK
					+ lookups.get() / 256 + "." + lookups.getAndIncrement() % 256 + ":1234" : address;

				return Node.fromURI(new URI(null, resolved, null, null, null).parseServerAuthority());
			}
		};

		try {
			for (int round = 0; round < ROUNDS; round++) {
				topology.addSeeds(Topology.resolveSeeds());
			}

			assertThat("the configured nodes after " + ROUNDS + " rounds of one seed that resolves anew each time",
				topology.cloneConfigured().size(), lessThanOrEqualTo(Network.getSeeds().length));
			assertThat("the nodes at an address that the seed resolved to in one of " + ROUNDS + " rounds",
				topology.cloneNodes().stream().map(node -> node.getAddress().getHostString())
					.filter(host -> host.startsWith(ROTATING_NETWORK)).count(), is(1L));
		} finally {
			rotating.set(false);
			topology.cloneConfigured().forEach(topology::removeConfigured);
			topology.repopulate(Topology.resolveSeeds());
		}
	}

	/* A seed that no name resolves to any more is dialed as a seed no longer, but a node that a connection or an open
	   stream still ties to this node is not dropped with it: it stays as a node that a peer told of does, and goes as
	   one once its stream closed */
	@Example
	public void shouldKeepAnEarlierSeedAsAnOrdinaryNodeWhileAConnectionOrAStreamTiesItToThisNode() {
		final EmbeddedChannel stream = new EmbeddedChannel();
		final Node dialed = NodeConnections.connected("127.0.100.51:52883", new EmbeddedChannel());
		final Node heard = NodeConnections.unconnected("127.0.100.52:52883");
		final Node idle = NodeConnections.unconnected("127.0.100.53:52883");
		final List<Node> earlier = List.of(dialed, heard, idle);

		heard.setInbound(Optional.of(LedgerStreams.connection(stream)));

		try {
			assertThat(topology.addSeeds(Set.copyOf(earlier)), hasSize(earlier.size()));
			assertThat(topology.cloneConfigured(), hasItems(dialed, heard, idle));
			topology.addSeeds(Topology.resolveSeeds());

			assertThat(topology.cloneNodes().stream().filter(earlier::contains).toList(),
				containsInAnyOrder(dialed, heard));
			assertThat(topology.cloneConfigured().stream().filter(earlier::contains).toList(), empty());

			stream.close();
			topology.letGoOfClosedStreams();
			assertThat(topology.cloneNodes().stream().filter(earlier::contains).toList(), contains(dialed));
		} finally {
			earlier.forEach(topology::removeNode);
			topology.addSeeds(Topology.resolveSeeds());
		}
	}

	/* A seed that the operator adds over REST as well was configured before, and is the operator's from then on: it
	   stays when no seed name resolves to it any more, until the operator removes it */
	@Example
	public void shouldKeepAnEarlierSeedThatTheOperatorAddedUntilTheOperatorRemovesIt() {
		final Node seed = NodeConnections.unconnected("127.0.100.54:52883");

		try {
			topology.addSeeds(Set.of(seed));
			assertThat(topology.addConfigured(NodeConnections.unconnected("127.0.100.54:52883")),
				is(Topology.Configured.BEFORE));
			topology.addSeeds(Topology.resolveSeeds());

			assertThat(topology.cloneNodes(), hasItem(seed));
			assertThat(topology.cloneConfigured(), hasItem(seed));
			assertThat(topology.removeConfigured(seed), is(Optional.of(seed)));
			assertThat(topology.cloneNodes(), not(hasItem(seed)));
			assertThat(topology.cloneConfigured(), not(hasItem(seed)));
		} finally {
			topology.removeConfigured(seed);
			topology.addSeeds(Topology.resolveSeeds());
		}
	}

	/* A seed that the operator removes is gone, as a node and as a seed, until the next round: a seed name that still
	   resolves to it brings it back */
	@Example
	public void shouldBringBackASeedThatTheOperatorRemovedOnceASeedNameResolvesToItAgain() {
		final Node seed = Topology.resolveSeeds().iterator().next();

		try {
			topology.addSeeds(Topology.resolveSeeds());
			assertThat(topology.removeConfigured(seed), is(Optional.of(seed)));
			assertThat(topology.cloneNodes(), not(hasItem(seed)));
			assertThat(topology.cloneConfigured(), not(hasItem(seed)));

			topology.addSeeds(Topology.resolveSeeds());
			assertThat(topology.cloneNodes(), hasItem(seed));
			assertThat(topology.cloneConfigured(), hasItem(seed));
		} finally {
			topology.addSeeds(Topology.resolveSeeds());
		}
	}

	/* A seed is what a name resolved to, which a node that moved to another address no longer is. It is the same node
	   among the seeds, so it has to leave them before its address changes: a hash set that holds it under the hash of
	   its old address would give it out at the new one. */
	@Example
	public void shouldTakeASeedThatMovedForNoSeedAtItsNewAddress() {
		final Node seed = NodeConnections.unconnected("127.0.100.55:52883");
		final Node moved = NodeConnections.unconnected("127.0.100.56:52883");

		try {
			topology.addSeeds(Set.of(NodeConnections.unconnected("127.0.100.55:52883")));
			topology.changeAddress(seed, moved.getAddress());

			assertThat(topology.cloneNodes(), hasItem(moved));
			assertThat(topology.cloneConfigured(), not(hasItem(moved)));
			assertThat(topology.cloneConfigured(), not(hasItem(seed)));
		} finally {
			topology.removeNode(moved);
			topology.addSeeds(Topology.resolveSeeds());
		}
	}

	private static final String MOVED_SEED = "127.0.100.57:52883";
	private static final String NEVER_RESOLVED_SEED = "127.0.200.9";

	/* The lookups of the seed names, which an answer replaces while one is held for a name: another address that the
	   name resolves to, or none for a lookup that fails, as that of a host whose resolver is down does. The fake stays
	   on for the rest of the class, so an example empties the answers when it is done. */
	private static void answerSeedLookups(Map<String, Optional<String>> answers) {
		new MockUp<Node>() {
			@Mock
			@SneakyThrows
			public /* static */ Node fromAddress(String address) {
				final String resolved = answers.getOrDefault(address, Optional.of(address))
					.orElseThrow(() -> new UnknownHostException(address));

				return Node.fromURI(new URI(null, resolved, null, null, null).parseServerAuthority());
			}
		};
	}

	/* A lookup that fails tells nothing of where a seed is: the resolver of the host is down for a while, or answers too
	   late. A round in which no seed name resolves must not let go of the seeds, which lead a node back to the network
	   when peers filled its topology: each name keeps the address that it last resolved to, which is dialed as a seed
	   and passes the limit of the topology as before. */
	@Example
	public void shouldKeepTheSeedsThroughARoundInWhichNoSeedNameResolves() {
		final Map<String, Optional<String>> answers = new ConcurrentHashMap<>();

		answerSeedLookups(answers);

		try {
			final Set<Node> seeds = topology.addSeeds(Topology.resolveSeeds());

			assertThat(seeds, hasSize(Network.getSeeds().length));
			Arrays.stream(Network.getSeeds()).forEach(name -> answers.put(name, Optional.empty()));

			assertThat("the seeds to dial after a round in which no seed name resolved",
				topology.addSeeds(Topology.resolveSeeds()), is(seeds));
			assertThat("the configured nodes after a round in which no seed name resolved",
				topology.cloneConfigured(), hasItems(seeds.toArray(Node[]::new)));
			assertThat("the nodes after a round in which no seed name resolved", topology.cloneNodes(),
				hasItems(seeds.toArray(Node[]::new)));
		} finally {
			answers.clear();
			topology.addSeeds(Topology.resolveSeeds());
		}
	}

	/* Only a name whose lookup failed keeps its address. A name that resolves to another address in the same round has
	   moved, so the address that it resolved to before is a seed no longer and leaves the topology, and it is the
	   address that the name moved to that it keeps when a later lookup of it fails. */
	@Example
	public void shouldKeepTheSeedOfANameThatDoesNotResolveAndLetGoOfTheOneOfANameThatMoved() throws Exception {
		final String[] names = Network.getSeeds();
		final Map<String, Optional<String>> answers = new ConcurrentHashMap<>();
		final Node moved = NodeConnections.unconnected(MOVED_SEED);

		answerSeedLookups(answers);

		try {
			final Node kept = Node.fromAddress(names[0]);
			final Node left = Node.fromAddress(names[1]);

			topology.addSeeds(Topology.resolveSeeds());
			answers.put(names[0], Optional.empty());
			answers.put(names[1], Optional.of(MOVED_SEED));
			topology.addSeeds(Topology.resolveSeeds());

			assertThat(topology.cloneConfigured(), allOf(hasItems(kept, moved), not(hasItem(left))));
			assertThat(topology.cloneNodes(), allOf(hasItems(kept, moved), not(hasItem(left))));

			answers.put(names[1], Optional.empty());
			topology.addSeeds(Topology.resolveSeeds());

			assertThat(topology.cloneConfigured(), allOf(hasItems(kept, moved), not(hasItem(left))));
			assertThat(topology.cloneNodes(), allOf(hasItems(kept, moved), not(hasItem(left))));
		} finally {
			answers.clear();
			topology.addSeeds(Topology.resolveSeeds());
		}
	}

	/* A name that has not resolved since the node started has no address to keep, so it gives no seed while its lookup
	   fails */
	@Example
	public void shouldGiveNoSeedForANameThatNeverResolved() {
		final Map<String, Optional<String>> answers = new ConcurrentHashMap<>();

		answers.put(NEVER_RESOLVED_SEED, Optional.empty());
		answerSeedLookups(answers);

		new Expectations() {{
			Network.getSeeds(); result = new String[] { NEVER_RESOLVED_SEED };
		}};

		try {
			assertThat(Topology.resolveSeeds(), empty());
		} finally {
			answers.clear();
		}
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

	/* A seed, and a node that the operator adds, is filed as the very node that the topology holds, so a node that moves
	   to another address has to move in the configured set as well: a hash set that holds an element under the hash of
	   its old address no longer finds it, and takes the same node a second time. */
	@Example
	public void shouldKnowAConfiguredNodeAtItsNewAddressWhenItMoved() {
		final Node moved = NodeConnections.unconnected("127.0.100.41:52883");

		try {
			assertThat(topology.addConfigured(NodeConnections.unconnected("127.0.100.40:52883")),
				is(Topology.Configured.ADDED));
			topology.changeAddress(NodeConnections.unconnected("127.0.100.40:52883"), moved.getAddress());

			assertThat(topology.addConfigured(NodeConnections.unconnected("127.0.100.41:52883")),
				is(Topology.Configured.BEFORE));
		} finally {
			topology.removeConfigured(moved);
		}
	}

	@Example
	public void shouldNotConfigureAMovedNodeAnymoreOnceTheOperatorRemovedItAtTheNewAddress() {
		final Node moved = NodeConnections.unconnected("127.0.100.43:52883");

		try {
			assertThat(topology.addConfigured(NodeConnections.unconnected("127.0.100.42:52883")),
				is(Topology.Configured.ADDED));
			topology.changeAddress(NodeConnections.unconnected("127.0.100.42:52883"), moved.getAddress());

			assertThat(topology.removeConfigured(moved), is(Optional.of(moved)));
			assertThat("the node is still configured at the address it moved to", topology.cloneConfigured(),
				not(hasItem(moved)));
		} finally {
			topology.removeConfigured(moved);
		}
	}

	/* A node that no operator configured is let go of once its stream closed and it holds no connection, a configured
	   one is kept, also after it moved */
	@Example
	public void shouldKeepAMovedConfiguredNodeWhoseStreamClosed() {
		final EmbeddedChannel closedStream = new EmbeddedChannel();
		final Node configured = NodeConnections.unconnected("127.0.100.44:52883");
		final Node moved = NodeConnections.unconnected("127.0.100.45:52883");

		closedStream.close();
		configured.setInbound(Optional.of(LedgerStreams.connection(closedStream)));

		try {
			assertThat(topology.addConfigured(configured), is(Topology.Configured.ADDED));
			topology.changeAddress(NodeConnections.unconnected("127.0.100.44:52883"), moved.getAddress());
			topology.letGoOfClosedStreams();

			assertThat("a configured node was let go of with its closed stream", topology.containsNode(moved), is(true));
		} finally {
			topology.removeConfigured(moved);
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

	/* A purge that ends in an exception must not leave the lock of the gridnodes held: the next offer, or the next copy
	   for the REST answer, on another thread would then wait for ever */
	@Example
	public void shouldReleaseTheLockOfTheGridnodesWhenAPurgeThrows() throws Exception {
		final Gridnode stale = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.2:1", NOW);
		final ExecutorService threads = Executors.newSingleThreadExecutor();

		topology.offerGridnode(stale, NOW);

		boolean threw = false;

		try {
			topology.purgeGridnodes(NOW + MAX_AGE, null);
		} catch (NullPointerException ex) {
			threw = true;
		}

		assertThat("the purge was expected to throw for a missing entry to keep", threw, is(true));

		try {
			final Future<Set<Gridnode>> copied = threads.submit(topology::cloneGridnode);

			assertThat(copied.get(10, TimeUnit.SECONDS).stream().map(Gridnode::getId).toList(),
				hasItem(stale.getId()));
		} catch (TimeoutException ex) {
			assertThat("the lock of the gridnodes is held after the purge threw", false);
		} finally {
			threads.shutdownNow();
		}
	}

	private static final int RELAYED_TO = 40;
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

	/* The container intercepts the methods of a bean that it is called on, never a static one, so an annotation that asks
	   for a lock on a static method promises a lock that nothing takes: a send is locked by the iteration of the
	   topology that it runs in */
	@Example
	public void shouldAskForNoLockOnAStaticMethod() {
		final List<String> promised = Stream.of(Topology.class, ChannelMap.class)
			.flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
			.filter(method -> Modifier.isStatic(method.getModifiers()))
			.filter(method -> method.isAnnotationPresent(Protected.class) || method.isAnnotationPresent(Lock.class))
			.map(Method::toGenericString).toList();

		assertThat("static methods that ask for a lock that the container never takes", promised, empty());
	}

	/* A send goes through the nodes under the read lock of the topology, so it waits for a writer that holds the lock */
	@Example
	public void shouldWaitForAWriterBeforeSendingToTheNodes() throws Exception {
		final EmbeddedChannel stream = new EmbeddedChannel();
		final Node node = NodeConnections.connected("127.0.100.46:52883", stream);
		final Packet packet = PublishGridnode.builder().build();
		final CountDownLatch writing = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final ExecutorService threads = Executors.newFixedThreadPool(2);

		try {
			assertThat(topology.addNode(node), is(true));
			threads.submit(() -> topology.modifyNode(node, n -> {
				writing.countDown();
				awaitQuietly(release);
			}));
			assertThat(writing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));

			final Future<?> sending = threads.submit(() -> Topology.sendAll(packet, topology, Optional.empty()));
			boolean sentWhileWriting = true;

			try {
				sending.get(300, TimeUnit.MILLISECONDS);
			} catch (TimeoutException ex) {
				sentWhileWriting = false;
			}

			release.countDown();
			assertThat("the packet was sent while another thread wrote to the topology", sentWhileWriting, is(false));
			sending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertThat(stream.<Packet>readOutbound(), is(packet));
		} finally {
			release.countDown();
			threads.shutdownNow();
			topology.removeNode(node);
		}
	}

	/* Counts the times it is rendered as text, as a spork is whole: all its data, its signatures and its log */
	private static final class CountedPacket extends Packet {
		private final AtomicInteger rendered = new AtomicInteger();

		@Override
		public String toString() {
			rendered.incrementAndGet();
			return "counted";
		}
	}

	private static void withoutDebugLogging(Runnable action) {
		final Logger[] loggers = { (Logger) LoggerFactory.getLogger(Node.class),
			(Logger) LoggerFactory.getLogger(Topology.class) };
		final Level[] levels = { loggers[0].getLevel(), loggers[1].getLevel() };

		Arrays.stream(loggers).forEach(logger -> logger.setLevel(Level.INFO));

		try {
			action.run();
		} finally {
			IntStream.range(0, loggers.length).forEach(i -> loggers[i].setLevel(levels[i]));
		}
	}

	/* A packet is relayed to every node on an event loop, under the read lock of the topology. Rendering it as text for
	   a debug line that is not written costs a string of all that a spork holds for each of the nodes. */
	@Example
	public void shouldNotRenderAPacketAsTextWhenRelayingItWithDebugLoggingOff() {
		final CountedPacket packet = new CountedPacket();
		final List<EmbeddedChannel> streams = IntStream.rangeClosed(1, RELAYED_TO)
			.mapToObj(i -> new EmbeddedChannel()).toList();
		final List<Node> nodes = IntStream.range(0, RELAYED_TO)
			.mapToObj(i -> NodeConnections.connected("10.1.0." + (i + 1) + ":52883", streams.get(i))).toList();

		try {
			nodes.forEach(node -> assertThat(topology.addNode(node), is(true)));
			withoutDebugLogging(() -> Topology.sendAll(packet, topology, Optional.empty()));

			assertThat("the packet was rendered as text with the debug level off", packet.rendered.get(), is(0));
			streams.forEach(stream -> assertThat(stream.<Packet>readOutbound(), sameInstance(packet)));
		} finally {
			nodes.forEach(topology::removeNode);
		}
	}

	private static Node peer(int index) {
		return Node.builder().address(new InetSocketAddress("10.2." + (index / 250) + "." + (index % 250 + 1), 52883))
			.build();
	}

	private static final AtomicInteger URIS_BUILT = new AtomicInteger();

	/* The fake of a try stays on for the rest of the class, so one fake counts for every try: an Invocation that
	   proceeds from a fake that a later try applied again reaches that fake once more, without end */
	@BeforeContainer
	private static void countTheUrisBuilt() {
		new MockUp<UriBuilder>() {
			@Mock
			public /* static */ UriBuilder fromPath(Invocation invocation, String path) {
				URIS_BUILT.incrementAndGet();
				return invocation.proceed();
			}
		};
	}

	/* The URIs that are made while the action runs: a node is hashed and compared by its address, which a URI has been
	   made of each time */
	private static int urisBuiltWhile(Runnable action) {
		final int before = URIS_BUILT.get();

		action.run();
		return URIS_BUILT.get() - before;
	}

	/* A peer sends up to a thousand nodes at a time, and each is added to the topology by the event loop of the stream
	   it came on, under the write lock of the topology. The list is read off the wire, so its nodes are not the ones that
	   the topology holds: all but a few of them are known already, and the topology is a node short of full. */
	@Example
	public void shouldNotBuildAUriToAddAFullListOfPeersToAFullTopology() {
		final List<Node> held = IntStream.range(0, Topology.MAX_NODES - 1 - topology.cloneNodes().size())
			.mapToObj(i -> peer(i)).toList();
		final List<Node> listed = IntStream.range(0, PublishPeersDecoder.MAX_PEERS).mapToObj(i -> peer(i)).toList();

		try {
			held.forEach(topology::addNode);
			assertThat(topology.cloneNodes().size(), is(Topology.MAX_NODES - 1));

			final int built = urisBuiltWhile(() -> listed.forEach(topology::addNode));

			assertThat("URIs were built to add " + listed.size() + " peers to " + Topology.MAX_NODES + " nodes", built,
				is(0));
			assertThat(topology.cloneNodes().size(), is(Topology.MAX_NODES));
		} finally {
			listed.forEach(topology::removeNode);
		}
	}

	/* Finding a node to change, as the dialing does for each node of the topology, compares it with every node held */
	@Example
	public void shouldNotBuildAUriToFindANodeInAFullTopology() {
		final List<Node> peers = IntStream.range(0, Topology.MAX_NODES - topology.cloneNodes().size())
			.mapToObj(i -> peer(i)).toList();
		final AtomicInteger found = new AtomicInteger();

		try {
			peers.forEach(topology::addNode);
			assertThat(topology.cloneNodes().size(), is(Topology.MAX_NODES));

			final int built = urisBuiltWhile(() -> topology.modifyNode(peer(peers.size() - 1),
				node -> found.incrementAndGet()));

			assertThat("URIs were built to find a node among " + Topology.MAX_NODES, built, is(0));
			assertThat(found.get(), is(1));
		} finally {
			peers.forEach(topology::removeNode);
		}
	}
}
