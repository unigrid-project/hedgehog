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

package org.unigrid.hedgehog.model.network;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.model.Network;
import static org.unigrid.hedgehog.model.network.QuicLoopback.PREVIOUS_RELEASE;

/* Over QUIC connections on loopback. The server takes the first of its protocols that the client offers too. */
public class PeerProtocolTest {
	private static String protocolOf(QuicChannel connection) {
		return connection.sslEngine().getApplicationProtocol();
	}

	@Example
	public void speaksProxyWithANodeOfThisReleaseAtEitherEndAndOnItsStreams() throws InterruptedException {
		try (QuicLoopback loopback = QuicLoopback.between(Network.getProtocols(), Network.getProtocols())) {
			assertThat(protocolOf(loopback.clientEnd()), is(Network.PROXY_PROTOCOL));
			assertThat(PeerProtocol.speaksProxy(loopback.clientEnd()), is(true));
			assertThat(PeerProtocol.speaksProxy(loopback.serverEnd()), is(true));
			assertThat(PeerProtocol.speaksProxy(loopback.stream()), is(true));
		}
	}

	@Example
	public void speaksNoProxyWithAServerOfThePreviousRelease() throws InterruptedException {
		try (QuicLoopback loopback = QuicLoopback.between(PREVIOUS_RELEASE, Network.getProtocols())) {
			assertThat(protocolOf(loopback.clientEnd()), is(PREVIOUS_RELEASE[0]));
			assertThat(PeerProtocol.speaksProxy(loopback.clientEnd()), is(false));
			assertThat(PeerProtocol.speaksProxy(loopback.stream()), is(false));
		}
	}

	@Example
	public void speaksNoProxyWithAClientOfThePreviousRelease() throws InterruptedException {
		try (QuicLoopback loopback = QuicLoopback.between(Network.getProtocols(), PREVIOUS_RELEASE)) {
			assertThat(protocolOf(loopback.serverEnd()), is(PREVIOUS_RELEASE[0]));
			assertThat(PeerProtocol.speaksProxy(loopback.serverEnd()), is(false));
			assertThat(PeerProtocol.speaksProxy(loopback.stream()), is(false));
		}
	}

	@Example
	public void speaksNoProxyBeforeTheHandshakeHasAgreedOnAProtocol() throws InterruptedException {
		try (QuicLoopback loopback = QuicLoopback.unanswered(Network.getProtocols())) {
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

		try (QuicLoopback loopback = QuicLoopback.between(newestLast, Network.getProtocols())) {
			assertThat(protocolOf(loopback.clientEnd()), is(PREVIOUS_RELEASE[0]));
		}

		try (QuicLoopback loopback = QuicLoopback.between(Network.getProtocols(), newestLast)) {
			assertThat(protocolOf(loopback.clientEnd()), is(Network.PROXY_PROTOCOL));
		}
	}
}
