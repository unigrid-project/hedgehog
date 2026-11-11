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

import java.net.InetAddress;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class ConnectionLimiterTest {
	private static final InetAddress PEER = InetAddress.ofLiteral("192.0.2.7");
	private static final InetAddress OTHER = InetAddress.ofLiteral("2001:db8::7");

	/* A host with a /64, the allocation of a line or a server, takes any of its 2^64 addresses as its own. These are
	   few enough for their shares to stay below what all addresses may hold together. */
	private static final int ADDRESSES_OF_A_SITE = 100;

	/* Hosts that have nothing to do with each other, each of a /16 of its own */
	private static final int UNRELATED_HOSTS = 20_000;

	/* A node connects once to each node it knows and the node once back, and knows at most Topology.MAX_NODES */
	private static final int CONNECTIONS_OF_A_FULL_TOPOLOGY = 2 * Topology.MAX_NODES;

	/* As many hosts as hold all that the node admits when each holds its share */
	private static final int HOSTS_THAT_FILL_THE_NODE = ConnectionLimiter.MAX_IN_ALL
		/ ConnectionLimiter.MAX_PER_ADDRESS;

	private static void admitAll(ConnectionLimiter limiter, InetAddress address) {
		IntStream.range(0, ConnectionLimiter.MAX_PER_ADDRESS).forEach(n -> assertThat(limiter.admit(address), is(true)));
	}

	@Example
	public void shouldRefuseAnAddressOneConnectionAboveItsShare() {
		final ConnectionLimiter limiter = new ConnectionLimiter();

		admitAll(limiter, PEER);

		assertThat(limiter.admit(PEER), is(false));
		assertThat(limiter.held(PEER), equalTo(ConnectionLimiter.MAX_PER_ADDRESS));
		assertThat(limiter.admit(OTHER), is(true));
	}

	@Example
	public void shouldLetAnAddressInAgainOnceAConnectionHasClosed() {
		final ConnectionLimiter limiter = new ConnectionLimiter();

		admitAll(limiter, PEER);
		limiter.release(PEER);

		assertThat(limiter.admit(PEER), is(true));
	}

	@Example
	public void shouldForgetAnAddressWithNoConnectionLeft() {
		final ConnectionLimiter limiter = new ConnectionLimiter();

		limiter.admit(PEER);
		limiter.release(PEER);
		limiter.release(PEER);

		assertThat(limiter.held(PEER), equalTo(0));
	}

	/* A forwarder on this host hands every connection over from the loopback address */
	@Example
	public void shouldLeaveTheLoopbackAddressAlone() {
		final ConnectionLimiter limiter = new ConnectionLimiter();
		final InetAddress loopback = InetAddress.getLoopbackAddress();

		IntStream.rangeClosed(0, ConnectionLimiter.MAX_PER_ADDRESS * 4)
			.forEach(n -> assertThat(limiter.admit(loopback), is(true)));
		assertThat(limiter.held(loopback), equalTo(0));
	}

	/* Every address asks for one connection above its share, as a peer would that wants as many as it can get */
	private static int admittedFrom(ConnectionLimiter limiter, InetAddress address) {
		int admitted = 0;

		for (int asked = 0; asked <= ConnectionLimiter.MAX_PER_ADDRESS; asked++) {
			if (limiter.admit(address)) {
				admitted++;
			}
		}

		return admitted;
	}

	/* The addresses are the first of 2001:db8:0:1::/64 */
	private static InetAddress inTheSite(int n) {
		return InetAddress.ofLiteral("2001:db8:0:1::" + Integer.toHexString(n + 1));
	}

	/* The first octet runs from 1 to 79 and the second from 0 to 255, so that no two hosts share a /16 or a /24, and
	   neither is the loopback network */
	@SneakyThrows
	private static InetAddress unrelatedHost(int n) {
		return InetAddress.getByAddress(new byte[] { (byte) (1 + n / 256), (byte) (n % 256), 0, 1 });
	}

	/* An address of IPv6 has a share of its own, as one of IPv4 has, also when it is one of many in a /64 */
	@Example
	public void shouldGiveEachAddressOfAnIpv6SiteAShareOfItsOwn() {
		final ConnectionLimiter limiter = new ConnectionLimiter();
		final int admitted = IntStream.range(0, ADDRESSES_OF_A_SITE)
			.map(n -> admittedFrom(limiter, inTheSite(n))).sum();

		assertThat("connections admitted from " + ADDRESSES_OF_A_SITE + " addresses of one /64, where one address gets "
			+ ConnectionLimiter.MAX_PER_ADDRESS + " and each asked for " + (ConnectionLimiter.MAX_PER_ADDRESS + 1),
			admitted, equalTo(ADDRESSES_OF_A_SITE * ConnectionLimiter.MAX_PER_ADDRESS));
	}

	@Example
	public void shouldAdmitNoMoreConnectionsThanATopologyThatIsFullCanHold() {
		final ConnectionLimiter limiter = new ConnectionLimiter();
		final int admitted = IntStream.range(0, UNRELATED_HOSTS)
			.map(n -> admittedFrom(limiter, unrelatedHost(n))).sum();

		assertThat("connections admitted from " + UNRELATED_HOSTS + " unrelated hosts, each of a /16 of its own and each "
			+ "asking for " + (ConnectionLimiter.MAX_PER_ADDRESS + 1), admitted,
			is(lessThanOrEqualTo(CONNECTIONS_OF_A_FULL_TOPOLOGY)));
		assertThat(admitted, equalTo(ConnectionLimiter.MAX_IN_ALL));
	}

	private static void fill(ConnectionLimiter limiter) {
		IntStream.range(0, HOSTS_THAT_FILL_THE_NODE).forEach(n -> admitAll(limiter, unrelatedHost(n)));
	}

	/* An address that holds nothing yet is refused once the others hold all that the node admits, and a connection of
	   any address that closes makes room for one more, of whatever address */
	@Example
	public void shouldLetAnAddressInOnceAConnectionOfAnotherHasClosedWhenTheNodeHeldAllItAdmits() {
		final ConnectionLimiter limiter = new ConnectionLimiter();
		final InetAddress late = unrelatedHost(HOSTS_THAT_FILL_THE_NODE);

		fill(limiter);
		assertThat(limiter.admit(late), is(false));
		assertThat(limiter.held(late), equalTo(0));

		limiter.release(unrelatedHost(0));
		assertThat(limiter.admit(late), is(true));
		assertThat(limiter.admit(late), is(false));
		assertThat(limiter.admit(unrelatedHost(0)), is(false));
	}

	/* A release for an address that holds nothing, as the second of two for one connection, makes no room */
	@Example
	public void shouldMakeNoRoomForAnAddressThatHeldNothingWhenItIsReleased() {
		final ConnectionLimiter limiter = new ConnectionLimiter();
		final InetAddress late = unrelatedHost(HOSTS_THAT_FILL_THE_NODE);

		fill(limiter);
		limiter.release(late);
		limiter.release(InetAddress.getLoopbackAddress());

		assertThat(limiter.admit(late), is(false));
	}

	/* A forwarder on this host is let in when the node holds all it admits, and what it hands over takes no room from
	   the other addresses */
	@Example
	public void shouldLeaveTheLoopbackAddressOutOfWhatAllAddressesHoldTogether() {
		final ConnectionLimiter limiter = new ConnectionLimiter();
		final InetAddress loopback = InetAddress.getLoopbackAddress();

		IntStream.range(0, ConnectionLimiter.MAX_IN_ALL).forEach(n -> assertThat(limiter.admit(loopback), is(true)));
		fill(limiter);

		assertThat(limiter.admit(unrelatedHost(HOSTS_THAT_FILL_THE_NODE)), is(false));
		assertThat(limiter.admit(loopback), is(true));
	}

	/* The control for the two above: a limiter that admits the share of every address that it sees tells the sum of
	   those shares, so the sums above measure the limiter and not the way the addresses are made */
	@Example
	public void shouldAdmitTheShareOfEachOfTheAddressesOfTheExamplesAbove() {
		final ConnectionLimiter limiter = new ConnectionLimiter();

		assertThat(admittedFrom(limiter, inTheSite(0)), equalTo(ConnectionLimiter.MAX_PER_ADDRESS));
		assertThat(admittedFrom(limiter, unrelatedHost(0)), equalTo(ConnectionLimiter.MAX_PER_ADDRESS));
		assertThat(inTheSite(0).equals(inTheSite(1)), is(false));
		assertThat(unrelatedHost(0).equals(unrelatedHost(1)), is(false));
		assertThat(unrelatedHost(UNRELATED_HOSTS - 1).isLoopbackAddress(), is(false));
	}
}
