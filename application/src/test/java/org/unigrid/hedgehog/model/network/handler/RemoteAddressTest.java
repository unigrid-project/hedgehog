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
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.ipv4Mapped;

public class RemoteAddressTest {
	private static final int PORT = 52883;
	private static final int CONNECTION_PORT = 40123;

	private static Optional<InetSocketAddress> address(final String text) {
		return Optional.of(new InetSocketAddress(text, PORT));
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

	@Property(tries = 20)
	public void readsAnIPv4AddressThatComesAsAnIPv6OneAsIPv4(@ForAll @IntRange(min = 1, max = 126) int a,
		@ForAll @IntRange(min = 0, max = 255) int b, @ForAll @IntRange(min = 0, max = 255) int c,
		@ForAll @IntRange(min = 0, max = 255) int d) {

		final EmbeddedChannel connection = new EmbeddedChannel();

		connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY).set(ipv4Mapped(a, b, c, d, PORT));

		final InetSocketAddress read = RemoteAddress.of(streamOf(connection)).get();

		assertThat(read.getAddress(), instanceOf(Inet4Address.class));
		assertThat(read.getAddress().getHostAddress(), equalTo(a + "." + b + "." + c + "." + d));
		assertThat(read.getPort(), equalTo(PORT));
	}

	@Property(tries = 10)
	public void takesALocalPeerThatComesAsAnIPv6AddressForALocalPeer(
		@ForAll @IntRange(min = 1, max = 65535) int listenPort) {

		final EmbeddedChannel connection = new EmbeddedChannel();
		final Channel stream = streamOf(connection);

		connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY).set(ipv4Mapped(127, 0, 0, 1, PORT));
		connection.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).set(ipv4Mapped(127, 0, 0, 1, CONNECTION_PORT));
		RemoteAddress.listensOn(stream, listenPort);

		assertThat(RemoteAddress.of(stream), equalTo(Optional.of(new InetSocketAddress("127.0.0.1", listenPort))));
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

	private static EmbeddedChannel connectionOf(final String learned, final String connected) {
		final EmbeddedChannel connection = new EmbeddedChannel();

		connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY).set(new InetSocketAddress(learned, PORT));
		connection.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).set(new InetSocketAddress(connected,
			CONNECTION_PORT));
		return connection;
	}

	@Provide
	Arbitrary<String> loopbacks() {
		return Arbitraries.of("127.0.0.1", "127.0.0.2", "127.255.255.254", "::1");
	}

	@Property(tries = 10)
	public void readsTheSocketOfTheConnectionForALocalPeer(@ForAll("loopbacks") String address) {
		assertThat(RemoteAddress.of(streamOf(connectionOf(address, address))),
			equalTo(Optional.of(new InetSocketAddress(address, CONNECTION_PORT))));
	}

	@Property(tries = 10)
	public void readsThePortALocalPeerSaysItListensOn(@ForAll("loopbacks") String address,
		@ForAll @IntRange(min = 1, max = 65535) int listenPort) {

		final EmbeddedChannel connection = connectionOf(address, address);
		final Channel stream = streamOf(connection);

		RemoteAddress.listensOn(stream, listenPort);
		assertThat(RemoteAddress.of(stream), equalTo(Optional.of(new InetSocketAddress(address, listenPort))));
		assertThat(RemoteAddress.of(streamOf(connection)),
			equalTo(Optional.of(new InetSocketAddress(address, listenPort))));
	}

	@Example
	public void keepsTheFirstPortASideAnnouncesOnAConnection() {
		final EmbeddedChannel connection = connectionOf("127.0.0.1", "127.0.0.1");

		RemoteAddress.listensOn(streamOf(connection), CONNECTION_PORT + 1);
		RemoteAddress.listensOn(streamOf(connection), CONNECTION_PORT + 2);

		assertThat(RemoteAddress.of(streamOf(connection)),
			equalTo(Optional.of(new InetSocketAddress("127.0.0.1", CONNECTION_PORT + 1))));
	}

	@Property(tries = 5)
	public void ignoresAPortThatIsNoPort(@ForAll @IntRange(min = -5, max = 0) int port) {
		final Channel stream = streamOf(connectionOf("127.0.0.1", "127.0.0.1"));

		RemoteAddress.listensOn(stream, port);
		assertThat(RemoteAddress.of(stream),
			equalTo(Optional.of(new InetSocketAddress("127.0.0.1", CONNECTION_PORT))));
	}

	@Example
	public void leavesARemotePeerNamedByItsAddressWhateverPortItSays() {
		final Channel stream = streamOf(connectionOf("10.0.0.1", "10.0.0.1"));

		RemoteAddress.listensOn(stream, CONNECTION_PORT + 1);
		assertThat(RemoteAddress.of(stream), equalTo(address("10.0.0.1")));
	}

	@Example
	public void saysNothingOfAPortWhenThereIsNoConnection() {
		final EmbeddedChannel stream = new EmbeddedChannel();

		RemoteAddress.listensOn(stream, CONNECTION_PORT);
		assertThat(RemoteAddress.of(stream), equalTo(Optional.empty()));
	}

	@Example
	public void readsTheAddressTheServerLearnedWhenALocalPeerHasNoConnectionSocket() {
		final EmbeddedChannel connection = new EmbeddedChannel();

		connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY).set(new InetSocketAddress("127.0.0.1", PORT));
		assertThat(RemoteAddress.of(streamOf(connection)), equalTo(address("127.0.0.1")));
	}

	@Example
	public void keepsTheAddressTheServerLearnedForARemotePeer() {
		assertThat(RemoteAddress.of(streamOf(connectionOf("10.0.0.1", "127.0.0.1"))), equalTo(address("10.0.0.1")));
	}

	@Example
	public void readsNoAddressFromAnUnresolvedOne() {
		final EmbeddedChannel connection = new EmbeddedChannel();

		connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY)
			.set(InetSocketAddress.createUnresolved("peer.invalid", PORT));
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

	@Example
	public void namesAPeerAsTheLedgerDoes() {
		assertThat(RemoteAddress.idOf(streamOf(connectionOf("10.0.0.1", "10.0.0.1"))), equalTo("10.0.0.1"));
		assertThat(RemoteAddress.idOf(streamOf(connectionOf("127.0.0.1", "127.0.0.1"))),
			equalTo("127.0.0.1:" + CONNECTION_PORT));
		assertThat(RemoteAddress.idOf(streamOf(connectionOf("2001:db8:1:2:3:4:5:6", "2001:db8:1:2:3:4:5:6"))),
			equalTo("20010db800010002/64"));
	}

	@Example
	public void namesAStreamWithoutAnAddressByItself() {
		final EmbeddedChannel stream = new EmbeddedChannel();

		assertThat(RemoteAddress.idOf(stream), equalTo(stream.id().asLongText()));
	}
}
