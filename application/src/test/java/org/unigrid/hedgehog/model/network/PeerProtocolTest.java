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

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.model.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.model.Network;
import static org.unigrid.hedgehog.model.network.QuicLoopback.PREVIOUS_RELEASE;

/* Over QUIC connections on loopback. The server takes the first of its protocols that the client offers too. */
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class PeerProtocolTest {
	private static final List<String> DAEMON = List.of("daemon");

	private static String protocolOf(QuicChannel connection) {
		return connection.sslEngine().getApplicationProtocol();
	}

	/* What a node of this release offers when it is run with the preview, which turns the proxy on */
	private static String[] withThePreview() {
		OptionsCli.parse(DAEMON, "--preview");
		return Network.getProtocols();
	}

	private static String[] withoutThePreview() {
		OptionsCli.parse(DAEMON);
		return Network.getProtocols();
	}

	@Example
	public void speaksProxyWithANodeOfThisReleaseRunWithThePreviewAtEitherEndAndOnItsStreams()
		throws InterruptedException {

		try (QuicLoopback loopback = QuicLoopback.between(withThePreview(), withThePreview())) {
			assertThat(protocolOf(loopback.clientEnd()), is(Network.PROXY_PROTOCOL));
			assertThat(PeerProtocol.speaksProxy(loopback.clientEnd()), is(true));
			assertThat(PeerProtocol.speaksProxy(loopback.serverEnd()), is(true));
			assertThat(PeerProtocol.speaksProxy(loopback.stream()), is(true));
		}
	}

	/* The proxy is one of the parts of the node that this release leaves off */
	@Example
	public void speaksNoProxyBetweenNodesOfThisReleaseRunWithoutThePreview() throws InterruptedException {
		try (QuicLoopback loopback = QuicLoopback.between(withoutThePreview(), withoutThePreview())) {
			assertThat(protocolOf(loopback.clientEnd()), is(Network.SPORK_PROTOCOL));
			assertThat(PeerProtocol.speaksProxy(loopback.clientEnd()), is(false));
			assertThat(PeerProtocol.speaksProxy(loopback.serverEnd()), is(false));
			assertThat(PeerProtocol.speaksProxy(loopback.stream()), is(false));
		}
	}

	/* Either way round: the end with the preview offers the proxy protocol, which the other end does not take */
	@Example
	public void speaksNoProxyBetweenANodeRunWithThePreviewAndOneRunWithoutIt() throws InterruptedException {
		try (QuicLoopback loopback = QuicLoopback.between(withThePreview(), withoutThePreview())) {
			assertThat(protocolOf(loopback.clientEnd()), is(Network.SPORK_PROTOCOL));
			assertThat(PeerProtocol.speaksProxy(loopback.clientEnd()), is(false));
			assertThat(PeerProtocol.speaksProxy(loopback.serverEnd()), is(false));
		}

		try (QuicLoopback loopback = QuicLoopback.between(withoutThePreview(), withThePreview())) {
			assertThat(protocolOf(loopback.serverEnd()), is(Network.SPORK_PROTOCOL));
			assertThat(PeerProtocol.speaksProxy(loopback.clientEnd()), is(false));
			assertThat(PeerProtocol.speaksProxy(loopback.serverEnd()), is(false));
		}
	}

	/* A node that runs the ledger offers its protocol first, which carries the proxy as well */
	@Example
	public void speaksProxyOnTheLedgerProtocolAndWithANodeWithoutTheLedger() throws InterruptedException {
		final String[] withLedger = { Network.LEDGER_PROTOCOL, Network.PROXY_PROTOCOL, Network.SPORK_PROTOCOL,
			PREVIOUS_RELEASE[0], PREVIOUS_RELEASE[1] };
		final String[] withoutLedger = { Network.PROXY_PROTOCOL, Network.SPORK_PROTOCOL, PREVIOUS_RELEASE[0],
			PREVIOUS_RELEASE[1] };

		try (QuicLoopback loopback = QuicLoopback.between(withLedger, withLedger)) {
			assertThat(protocolOf(loopback.clientEnd()), is(Network.LEDGER_PROTOCOL));
			assertThat(PeerProtocol.speaksProxy(loopback.clientEnd()), is(true));
			assertThat(PeerProtocol.speaksProxy(loopback.serverEnd()), is(true));
		}

		try (QuicLoopback loopback = QuicLoopback.between(withLedger, withoutLedger)) {
			assertThat(protocolOf(loopback.clientEnd()), is(Network.PROXY_PROTOCOL));
			assertThat(PeerProtocol.speaksProxy(loopback.clientEnd()), is(true));
		}

		try (QuicLoopback loopback = QuicLoopback.between(withoutLedger, withLedger)) {
			assertThat(protocolOf(loopback.serverEnd()), is(Network.PROXY_PROTOCOL));
			assertThat(PeerProtocol.speaksProxy(loopback.serverEnd()), is(true));
		}
	}

	/* A node of this release that was built before the proxy offers the protocol of the release and nothing newer */
	@Example
	public void speaksNoProxyWithANodeOfThisReleaseThatHasNone() throws InterruptedException {
		final String[] withoutProxy = { Network.SPORK_PROTOCOL, PREVIOUS_RELEASE[0], PREVIOUS_RELEASE[1] };

		try (QuicLoopback loopback = QuicLoopback.between(withoutProxy, withThePreview())) {
			assertThat(protocolOf(loopback.clientEnd()), is(Network.SPORK_PROTOCOL));
			assertThat(PeerProtocol.speaksProxy(loopback.clientEnd()), is(false));
			assertThat(PeerProtocol.speaksProxy(loopback.stream()), is(false));
		}
	}

	@Example
	public void speaksNoProxyWithAServerOfThePreviousRelease() throws InterruptedException {
		try (QuicLoopback loopback = QuicLoopback.between(PREVIOUS_RELEASE, withThePreview())) {
			assertThat(protocolOf(loopback.clientEnd()), is(PREVIOUS_RELEASE[0]));
			assertThat(PeerProtocol.speaksProxy(loopback.clientEnd()), is(false));
			assertThat(PeerProtocol.speaksProxy(loopback.stream()), is(false));
		}
	}

	@Example
	public void speaksNoProxyWithAClientOfThePreviousRelease() throws InterruptedException {
		try (QuicLoopback loopback = QuicLoopback.between(withThePreview(), PREVIOUS_RELEASE)) {
			assertThat(protocolOf(loopback.serverEnd()), is(PREVIOUS_RELEASE[0]));
			assertThat(PeerProtocol.speaksProxy(loopback.serverEnd()), is(false));
			assertThat(PeerProtocol.speaksProxy(loopback.stream()), is(false));
		}
	}

	@Example
	public void speaksNoProxyBeforeTheHandshakeHasAgreedOnAProtocol() throws InterruptedException {
		try (QuicLoopback loopback = QuicLoopback.unanswered(withThePreview())) {
			assertThat(protocolOf(loopback.pendingClientEnd()), is(nullValue()));
			assertThat(PeerProtocol.speaksProxy(loopback.pendingClientEnd()), is(false));
		}
	}

	@Example
	public void speaksNoProxyOnAChannelOutsideAQuicConnection() {
		assertThat(PeerProtocol.speaksProxy(new EmbeddedChannel()), is(false));
		assertThat(PeerProtocol.speaksProxy(null), is(false));
	}

	/* Two nodes of this release would never agree on the newest protocol if it did not lead */
	@Example
	public void agreesOnTheFirstProtocolOfTheServerThatTheClientOffers() throws InterruptedException {
		final String[] newestLast = { PREVIOUS_RELEASE[0], PREVIOUS_RELEASE[1], Network.PROXY_PROTOCOL };

		try (QuicLoopback loopback = QuicLoopback.between(newestLast, withThePreview())) {
			assertThat(protocolOf(loopback.clientEnd()), is(PREVIOUS_RELEASE[0]));
		}

		try (QuicLoopback loopback = QuicLoopback.between(withThePreview(), newestLast)) {
			assertThat(protocolOf(loopback.clientEnd()), is(Network.PROXY_PROTOCOL));
		}
	}
}
