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

import io.netty.incubator.codec.quic.QuicStreamChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeProperty;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.model.network.QuicLoopback.PREVIOUS_RELEASE;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.server.BaseDefaultNodeTest;
import org.unigrid.hedgehog.transport.initializer.ChainStreamInitializer;
import org.unigrid.hedgehog.transport.initializer.RegisterQuicChannelInitializer;

/* A peer run with --preview offers the proxy protocol, and one that runs a ledger offers the ledger protocol ahead of
   it, besides what a node without them offers. The node of this release without --preview knows neither name, so
   whatever such a peer offers, they settle on the protocol of the release, and the peer is taken for one that takes no
   chain traffic. The second stream it opens, which a node with the proxy would take for the chain stream, is a
   main stream like the first. */
public class PeerProtocolDefaultNodeTest extends BaseDefaultNodeTest {
	private static final String[] OF_THE_RELEASE = { Network.SPORK_PROTOCOL, PREVIOUS_RELEASE[0], PREVIOUS_RELEASE[1] };

	/* The streams that the node set up as a chain stream, and as a main stream */
	private static final List<Long> chainStreams = new CopyOnWriteArrayList<>();
	private static final List<Long> mainStreams = new CopyOnWriteArrayList<>();

	/* The mock-ups are set up once for all examples and note the streams in static fields */
	@BeforeContainer
	private static void noteTheStreamsOfBothKinds() {
		new MockUp<ChainStreamInitializer>() {
			@Mock void initChannel(Invocation invocation, QuicStreamChannel channel) {
				chainStreams.add(channel.streamId());
				invocation.proceed(channel);
			}
		};

		new MockUp<RegisterQuicChannelInitializer>() {
			@Mock void initChannel(Invocation invocation, QuicStreamChannel channel) {
				mainStreams.add(channel.streamId());
				invocation.proceed(channel);
			}
		};
	}

	@BeforeProperty
	private void forgetEarlierStreams() {
		chainStreams.clear();
		mainStreams.clear();
	}

	private static String[] withTheProxyAndTheLedger() {
		return new String[] { Network.LEDGER_PROTOCOL, Network.PROXY_PROTOCOL, Network.SPORK_PROTOCOL,
			PREVIOUS_RELEASE[0], PREVIOUS_RELEASE[1] };
	}

	private static QuicPeer dial(final String... protocols) {
		return QuicPeer.dial(NetOptions.getHost(), NetOptions.getPort(), protocols);
	}

	/* The ledger is offered first, as a node that runs it does, and the proxy right after */
	@Example
	public void shouldSettleOnTheProtocolOfTheReleaseWhateverThePeerOffersBesides() throws InterruptedException {
		final List<String[]> offers = List.of(withTheProxyAndTheLedger(),
			new String[] { Network.PROXY_PROTOCOL, Network.SPORK_PROTOCOL, PREVIOUS_RELEASE[0], PREVIOUS_RELEASE[1] },
			new String[] { Network.LEDGER_PROTOCOL, Network.SPORK_PROTOCOL, PREVIOUS_RELEASE[0], PREVIOUS_RELEASE[1] },
			new String[] { PREVIOUS_RELEASE[1], PREVIOUS_RELEASE[0], Network.SPORK_PROTOCOL, Network.PROXY_PROTOCOL,
				Network.LEDGER_PROTOCOL });

		for (String[] offer : offers) {
			try (QuicPeer peer = dial(offer)) {
				assertThat(peer.protocol(), is(Network.SPORK_PROTOCOL));
				assertThat(PeerProtocol.speaksProxy(peer.connection()), is(false));
			}
		}

		assertThat(createdOfThePreview(), is(empty()));
	}

	@Example
	public void shouldSettleOnNothingWithAPeerThatOffersOnlyTheProxyAndTheLedger() throws InterruptedException {
		assertThrows(Exception.class, () -> dial(Network.LEDGER_PROTOCOL, Network.PROXY_PROTOCOL).close());
		assertThat(Network.getProtocols(), is(OF_THE_RELEASE));
	}

	/* A chain pipeline would refuse the frame of a part, as the stream is a chain stream's, and so close it */
	@Example
	public void shouldGiveTheSecondStreamOfSuchAPeerTheMainPipeline() throws InterruptedException {
		try (QuicPeer peer = dial(withTheProxyAndTheLedger())) {
			final QuicPeer.Stream main = peer.open();

			main.awaitAnswer(main.ping());

			final QuicPeer.Stream second = peer.open();

			assertThat(second.streamId(), is(Network.CHAIN_STREAM_ID));
			PreviewFrames.ofTheParts().forEach(second::send);
			second.awaitAnswer(second.ping());

			assertThat(second.isOpen(), is(true));
			assertThat(mainStreams, hasItem(Network.CHAIN_STREAM_ID));
			assertThat(chainStreams, is(empty()));
			assertThat(createdOfThePreview(), is(empty()));
			assertThat(threadsOfThePreview(), is(empty()));
		}
	}

	/* The frame of a chain would be handed to the chain host on a chain stream, which the node does not run */
	@Example
	public void shouldRefuseAChainFrameOnTheSecondStreamOfSuchAPeer() throws InterruptedException {
		try (QuicPeer peer = dial(withTheProxyAndTheLedger())) {
			final QuicPeer.Stream main = peer.open();

			main.awaitAnswer(main.ping());

			final QuicPeer.Stream second = peer.open();

			LedgerAndChainFrames.ofTheChain().forEach(second::send);
			second.awaitAnswerOrClose(second.ping());

			assertThat(second.isOpen(), is(false));
			assertThat(chainStreams, is(empty()));
			assertThat(createdOfThePreview(), is(empty()));
			assertThat(threadsOfThePreview(), is(empty()));
		}
	}
}
