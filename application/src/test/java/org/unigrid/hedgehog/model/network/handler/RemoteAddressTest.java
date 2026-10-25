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
import java.util.List;
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
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.model.network.Dialed;
import org.unigrid.hedgehog.service.ledger.NettyLedgerPeer;
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

	/* Only a connection that this node opened is marked as dialed, and what a peer says of itself marks none */
	@Example
	public void takesAPeerForOneThisNodeDialedByTheConnectionThatItOpenedOnly() {
		final EmbeddedChannel dialed = new EmbeddedChannel();
		final EmbeddedChannel inbound = new EmbeddedChannel();
		final Channel listening = streamOf(inbound);

		dialed.attr(Dialed.KEY).set(Dialed.CHOSEN);
		inbound.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).set(new InetSocketAddress("10.0.0.1", PORT));
		RemoteAddress.listensOn(listening, PORT);

		assertThat(Dialed.of(streamOf(dialed)), equalTo(Optional.of(Dialed.CHOSEN)));
		assertThat(Dialed.of(listening), equalTo(Optional.empty()));
		assertThat(Dialed.of(new EmbeddedChannel()), equalTo(Optional.empty()));
	}

	/* A node that an operator configures after this node dialed it as one it chose is one it dialed as configured,
	   while a peer that connected to this node is none that it dialed whatever is configured */
	@Example
	public void marksAConnectionThatThisNodeDialedAsOneToAConfiguredNodeOnly() {
		final EmbeddedChannel dialed = new EmbeddedChannel();
		final Channel inbound = streamOf(new EmbeddedChannel());
		final Channel stream = streamOf(dialed);

		dialed.attr(Dialed.KEY).set(Dialed.CHOSEN);
		Dialed.configure(stream);
		Dialed.configure(inbound);

		assertThat(Dialed.of(stream), equalTo(Optional.of(Dialed.CONFIGURED)));
		assertThat(Dialed.of(inbound), equalTo(Optional.empty()));
	}

	private static String groupOf(final String address) {
		return RemoteAddress.groupOf(new InetSocketAddress(address, PORT));
	}

	/* One who holds a network holds every address in it, so a peer is counted with its /16 or its IPv6 /32, while an
	   address of this host or of a private network stands for itself, as many nodes of one operator share them */
	@Example
	public void groupsAPeerByItsNetwork() {
		assertThat(groupOf("45.10.200.7"), equalTo(groupOf("45.10.3.99")));
		assertThat(groupOf("45.10.200.7"), not(equalTo(groupOf("45.11.200.7"))));
		assertThat(groupOf("::ffff:45.10.0.1"), equalTo(groupOf("45.10.200.7")));
		assertThat(groupOf("2001:db8:1:2::1"), equalTo(groupOf("2001:db8:ffff::9")));
		assertThat(groupOf("2001:db8:1:2::1"), not(equalTo(groupOf("2001:db9:1:2::1"))));

		assertThat(groupOf("2002:2d0a:0101::1"), equalTo(groupOf("45.10.1.1")));
		assertThat(groupOf("2002:2d0a:0101::1"), equalTo(groupOf("2002:2d0a:ffff::9")));
		assertThat(groupOf("2002:2d0b::1"), not(equalTo(groupOf("45.10.1.1"))));

		for (String own : List.of("127.0.0.1", "10.1.2.3", "192.168.1.1", "172.16.0.1", "fd00::1", "fe80::1")) {
			assertThat(own, groupOf(own), equalTo(NettyLedgerPeer.idOf(new InetSocketAddress(own, PORT))));
		}
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
