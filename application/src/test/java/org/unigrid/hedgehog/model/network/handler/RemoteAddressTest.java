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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class RemoteAddressTest {
	private static final int PORT = 52883;

	@SneakyThrows
	private static Optional<InetAddress> address(final String text) {
		return Optional.of(InetAddress.getByName(text));
	}

	private static Channel streamOf(final Channel connection) {
		return new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false);
	}

	@Example
	public void readsTheAddressTheServerLearnedOfTheConnection() {
		final EmbeddedChannel connection = new EmbeddedChannel();

		connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY).set(new InetSocketAddress("10.0.0.1", PORT));
		assertThat(RemoteAddress.of(streamOf(connection)), equalTo(address("10.0.0.1")));
	}

	@Example
	public void readsTheAddressTheClientConnectedTo() {
		final EmbeddedChannel connection = new EmbeddedChannel();

		connection.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).set(new InetSocketAddress("10.0.0.2", PORT));
		assertThat(RemoteAddress.of(streamOf(connection)), equalTo(address("10.0.0.2")));
	}

	@Example
	public void prefersTheAddressTheServerLearned() {
		final EmbeddedChannel connection = new EmbeddedChannel();

		connection.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).set(new InetSocketAddress("10.0.0.2", PORT));
		connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY).set(new InetSocketAddress("10.0.0.1", PORT));
		assertThat(RemoteAddress.of(streamOf(connection)), equalTo(address("10.0.0.1")));
	}

	@Example
	public void readsNoAddressFromAnUnresolvedOne() {
		final EmbeddedChannel connection = new EmbeddedChannel();

		connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY).set(InetSocketAddress.createUnresolved("peer.invalid", PORT));
		assertThat(RemoteAddress.of(streamOf(connection)), equalTo(Optional.empty()));
	}

	@Example
	public void readsNoAddressWhenTheConnectionHasNone() {
		assertThat(RemoteAddress.of(streamOf(new EmbeddedChannel())), equalTo(Optional.empty()));
	}

	@Example
	public void readsNoAddressWhenThereIsNoConnection() {
		assertThat(RemoteAddress.of(new EmbeddedChannel()), equalTo(Optional.empty()));
	}
}
