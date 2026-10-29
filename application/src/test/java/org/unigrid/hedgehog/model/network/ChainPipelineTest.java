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
import jakarta.enterprise.inject.spi.CDI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.model.network.codec.ChainArbitraries;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.initializer.ChainPipeline;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.ChainStem;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.server.BaseServerTest;
import org.unigrid.hedgehog.server.TestServer;
import org.unigrid.hedgehog.service.chain.ChainHost;

public class ChainPipelineTest extends BaseServerTest {
	private static final int WAIT_SECONDS = 5;
	private final BlockingQueue<EntryRefused> refusals = new LinkedBlockingQueue<>();
	private final BlockingQueue<ChainEntry> received = new LinkedBlockingQueue<>();
	private final BlockingQueue<ChainStem> stemmed = new LinkedBlockingQueue<>();
	private final BlockingQueue<ChainHeads> reconciled = new LinkedBlockingQueue<>();
	private final BlockingQueue<FetchEntries> fetches = new LinkedBlockingQueue<>();
	private final BlockingQueue<LeaveChain> leaves = new LinkedBlockingQueue<>();
	private volatile Optional<FetchEntries> reconciliation = Optional.empty();
	private volatile List<Entry> retained = List.of();

	@Provide
	public Arbitrary<WriterId> writers() {
		return ChainArbitraries.writers();
	}

	@Provide
	public Arbitrary<ChainId> chainIds() {
		return ChainArbitraries.chainIds();
	}

	@Provide
	public Arbitrary<Map<WriterId, Head>> heads() {
		return ChainArbitraries.heads();
	}

	@Provide
	public Arbitrary<List<Entry>> entryLists() {
		return ChainArbitraries.entries().list().ofMinSize(1).ofMaxSize(4);
	}

	@Provide
	public Arbitrary<List<List<Entry>>> twoDifferentEntryLists() {
		return entryLists().list().ofSize(2).uniqueElements();
	}

	@Provide
	public Arbitrary<FetchEntries> fetchRequests() {
		final Arbitrary<FetchEntries.Range> ranges = Combinators.combine(ChainArbitraries.writers(),
			Arbitraries.longs().between(0, 1000), Arbitraries.longs().between(0, 1000))
			.as((writer, from, length) -> new FetchEntries.Range(writer, from, from + length));

		return Combinators.combine(ChainArbitraries.chainIds(), ranges.list().ofMinSize(1).ofMaxSize(4))
			.as((chainId, list) -> FetchEntries.builder().chainId(chainId).ranges(list).build());
	}

	@BeforeTry
	private void forgetEarlierTraffic() {
		refusals.clear();
		received.clear();
		stemmed.clear();
		reconciled.clear();
		fetches.clear();
		leaves.clear();
		forgetStaleAnswers();
	}

	private void forgetStaleAnswers() {
		reconciliation = Optional.empty();
		retained = List.of();
	}

	/* Joins stay with the real host, which refuses them while the proxy is off */
	@BeforeProperty
	private void fakeChainHost() {
		new MockUp<ChainHost>() {
			@Mock public List<EntryRefused> receive(Channel peer, ChainEntry entries) {
				received.add(entries);
				return entries.getEntries().stream().map(entry -> EntryRefused.of(entry, Admission.FULL))
					.collect(Collectors.toList());
			}

			@Mock public List<EntryRefused> stem(Channel peer, ChainStem stem) {
				stemmed.add(stem);
				return stem.getEntries().stream().map(entry -> EntryRefused.of(entry, Admission.FULL))
					.collect(Collectors.toList());
			}

			@Mock public void refused(Channel peer, EntryRefused refusal) {
				refusals.add(refusal);
			}

			@Mock public Optional<FetchEntries> reconcile(Channel peer, ChainHeads heads) {
				reconciled.add(heads);
				return reconciliation;
			}

			@Mock public List<Entry> fetch(Channel peer, FetchEntries request) {
				final List<Entry> answer = retained;

				fetches.add(request);
				return answer;
			}

			@Mock public void leave(Channel peer, LeaveChain leave) {
				leaves.add(leave);
			}
		};
	}

	private <T> T awaited(final String what, final BlockingQueue<T> queue) throws InterruptedException {
		final T item = queue.poll(WAIT_SECONDS, TimeUnit.SECONDS);

		assertThat("Expected " + what + " within " + WAIT_SECONDS + " seconds", item, notNullValue());
		return item;
	}

	private void assertNothingLeftOver() {
		assertThat(Stream.<Collection<?>>of(refusals, received, reconciled, fetches, leaves)
			.flatMap(Collection::stream).collect(Collectors.toList()), empty());
	}

	@SneakyThrows
	private void assertJoinIsRefused(final P2PClient client, final WriterId writer) {
		/* Every test server runs in its own container, so an injected field could belong to another one than
		   the container the reply handler resolves from */
		final PendingRequests pendingRequests = CDI.current().select(PendingRequests.class).get();
		final long id = pendingRequests.nextRequestId();
		final Channel stream = client.getChainChannel().orElseThrow();
		final CompletableFuture<JoinReply> reply = pendingRequests.register(id, stream, JoinReply.class);

		stream.writeAndFlush(JoinChain.builder().requestId(id).writer(writer).build());

		assertThat(reply.get(WAIT_SECONDS, TimeUnit.SECONDS),
			equalTo(JoinReply.builder().requestId(id).status(JoinReply.Status.DISABLED).build()));
	}

	@SneakyThrows
	private void assertEntriesAreRefused(final P2PClient client, final List<Entry> entries) {
		client.getChainChannel().orElseThrow().writeAndFlush(ChainEntry.builder().entries(entries).build());

		for (Entry entry : entries) {
			assertThat(refusals.poll(WAIT_SECONDS, TimeUnit.SECONDS), equalTo(EntryRefused.of(entry, Admission.FULL)));
		}
	}

	@SneakyThrows
	private void assertStemIsTakenAndRefused(final P2PClient client, final List<Entry> entries) {
		final ChainStem stem = ChainStem.builder().entries(entries).build();

		client.getChainChannel().orElseThrow().writeAndFlush(stem);

		assertThat(awaited("the stem", stemmed), equalTo(stem));

		for (Entry entry : entries) {
			assertThat(awaited("a refusal", refusals), equalTo(EntryRefused.of(entry, Admission.FULL)));
		}
	}

	@SneakyThrows
	private void assertHeadsAreAnsweredWithAFetch(final P2PClient client, final ChainHeads heads,
		final FetchEntries wanted) {

		reconciliation = Optional.of(wanted);
		client.getChainChannel().orElseThrow().writeAndFlush(heads);

		assertThat(awaited("the heads", reconciled), equalTo(heads));
		assertThat(awaited("the fetch the heads asked for", fetches), equalTo(wanted));
	}

	@SneakyThrows
	private void assertFetchIsAnsweredWithEntries(final P2PClient client, final FetchEntries request,
		final List<Entry> entries) {

		retained = entries;
		client.getChainChannel().orElseThrow().writeAndFlush(request);

		assertThat(awaited("the fetch", fetches), equalTo(request));

		for (ChainEntry batch : ChainCodecs.batches(entries)) {
			assertThat(awaited("the entries of the fetch", received), equalTo(batch));
		}

		for (Entry entry : entries) {
			assertThat(awaited("a refusal", refusals), equalTo(EntryRefused.of(entry, Admission.FULL)));
		}
	}

	@SneakyThrows
	private void assertLeaveIsReported(final P2PClient client, final LeaveChain leave) {
		client.getChainChannel().orElseThrow().writeAndFlush(leave);

		assertThat(awaited("the leave", leaves), equalTo(leave));
	}

	@SneakyThrows
	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void carriesChainPacketsBothWays(@ForAll("provideTestServers") List<TestServer> servers,
		@ForAll("writers") WriterId writer, @ForAll("entryLists") List<Entry> entries) {

		for (TestServer server : servers) {
			final P2PClient client = new P2PClient(server.getP2p().getHostName(), server.getP2p().getPort());

			try {
				assertJoinIsRefused(client, writer);
				assertEntriesAreRefused(client, entries);
				assertStemIsTakenAndRefused(client, entries);
			} finally {
				client.closeDirty();
			}
		}
	}

	/* The first property reaches JOIN_CHAIN, JOIN_REPLY, CHAIN_ENTRY, CHAIN_STEM and ENTRY_REFUSED; these reach the
	   rest */
	@SneakyThrows
	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void carriesHeadsFetchesAndLeaves(@ForAll("provideTestServers") List<TestServer> servers,
		@ForAll("chainIds") ChainId chainId, @ForAll("writers") WriterId writer,
		@ForAll("heads") Map<WriterId, Head> heads, @ForAll("fetchRequests") FetchEntries wanted,
		@ForAll("fetchRequests") FetchEntries request,
		@ForAll("twoDifferentEntryLists") List<List<Entry>> rounds) {

		for (TestServer server : servers) {
			final P2PClient client = new P2PClient(server.getP2p().getHostName(), server.getP2p().getPort());

			try {
				/* Different entries in each round on one stream, so an answer left over from a round or a server
				   before turns up as a mismatch instead of passing for the answer of the current one */
				for (List<Entry> entries : rounds) {
					forgetStaleAnswers();
					assertHeadsAreAnsweredWithAFetch(client,
						ChainHeads.builder().chainId(chainId).heads(heads).build(), wanted);
					assertFetchIsAnsweredWithEntries(client, request, entries);
					assertLeaveIsReported(client, LeaveChain.builder().chainId(chainId).writer(writer).build());
					assertNothingLeftOver();
				}
			} finally {
				client.closeDirty();
			}
		}
	}
}
