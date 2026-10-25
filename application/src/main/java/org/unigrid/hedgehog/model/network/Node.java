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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.Future;
import jakarta.ws.rs.core.UriBuilder;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.command.option.NetOptions;

@Data
@Slf4j
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Node {

	private InetSocketAddress address;

	/* The connection that this node dialed to the node */
	@JsonIgnore
	@Builder.Default
	@ToString.Exclude
	private Optional<Connection> connection = Optional.empty();

	/* A stream that the node opened to this one and said hello on. It is sent on until this node dials the node, and
	   holds no place among the nodes it dials: anyone can connect, as often as they like. */
	@JsonIgnore
	@Builder.Default
	@ToString.Exclude
	private Optional<Connection> inbound = Optional.empty();

	@Builder.Default
	private Details details = new Details();
	private long nsPing;

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Details {

		private String[] protocols;
		private int version;
	}

	/* Whether the node is this one: on the port it listens on, at an address of one of its interfaces or the one it
	   is configured to listen at */
	public boolean isMe() {
		return !address.isUnresolved() && address.getPort() == NetOptions.getPort()
			&& LocalAddresses.current().contains(address.getAddress());
	}

	/* Every node a peer tells of is asked whether it is this one, under the lock of the topology, so the addresses of
	   the host are listed once a minute and not for each node: they seldom change, and listing the interfaces, or
	   resolving the configured host, takes long enough for a list of peers to hold the lock for seconds */
	private record LocalAddresses(Set<InetAddress> addresses, long listedAt) {
		private static final long LIFETIME_MILLIS = 60_000;
		private static volatile LocalAddresses held;

		static Set<InetAddress> current() {
			final LocalAddresses known = held;
			final long now = System.currentTimeMillis();

			if (known != null && now - known.listedAt() < LIFETIME_MILLIS) {
				return known.addresses();
			}

			held = new LocalAddresses(listed(), now);
			return held.addresses();
		}

		private static Set<InetAddress> listed() {
			final Set<InetAddress> found = new HashSet<>();

			try {
				NetworkInterface.getNetworkInterfaces().asIterator()
					.forEachRemaining(network -> network.inetAddresses().forEach(found::add));
			} catch (SocketException ex) {
				log.atWarn().log("Cannot list the network interfaces: {}", ex.getMessage());
			}

			try {
				found.addAll(Arrays.asList(InetAddress.getAllByName(NetOptions.getHost())));
			} catch (UnknownHostException ex) {
				log.atTrace().log("The host {} does not resolve: {}", NetOptions.getHost(), ex.getMessage());
			}

			return Set.copyOf(found);
		}
	}

	/* What this node sends to the node on: the connection it dialed, or else the stream the node opened to it, and one
	   that is open before one that closed, which the next round of dialing drops */
	@JsonIgnore
	public Optional<Connection> link() {
		return open(connection).or(() -> open(inbound)).or(() -> connection.or(() -> inbound));
	}

	/* What this node relays to the node: the stream that the node opened to it while that is open, as the node pays for
	   what comes on a connection that it dialed from the share of the peers it chose, and for what comes on one that
	   this node dialed from the share of anyone (PublishSporkChannelHandler) */
	@JsonIgnore
	public Optional<Connection> relay() {
		return open(inbound).or(this::link);
	}

	private static Optional<Connection> open(Optional<Connection> link) {
		return link.filter(held -> held.getChannel().isActive());
	}

	public static void send(Packet packet, Node node, Optional<BiConsumer<Node, Future>> consumer) {
		log.atDebug().log("Send all packet " + packet.toString());
		if (node.relay().isPresent()) {
			final ChannelFuture out = node.relay().get().getChannel().writeAndFlush(packet);

			out.addListener(f -> {
				consumer.ifPresent(c -> {
					c.accept(node, f);
				});
			});
		}
	}

	/* A node made by hand is refused when its host does not resolve, rather than stored under a name that may never
	   match the address a peer connects from */
	public static Node fromURI(URI uri) throws UnknownHostException {
		int port = uri.getPort();

		if (uri.getPort() == -1) {
			port = NetOptions.DEFAULT_PORT;
		}

		final InetSocketAddress address = new InetSocketAddress(uri.getHost(), port);

		if (address.isUnresolved()) {
			throw new UnknownHostException(uri.getHost());
		}

		return Node.builder().address(address).build();
	}

	public static Node fromAddress(String address) throws URISyntaxException, UnknownHostException {
		return fromURI(new URI(null, address, null, null, null).parseServerAuthority());
	}

	/* JSON leaves the address unresolved, and a node is identified by its resolved address */
	@JsonSetter("address")
	public void setResolvedAddress(InetSocketAddress address) {
		this.address = address.isUnresolved()
			? new InetSocketAddress(address.getHostString(), address.getPort())
			: address;
	}

	/* The address it resolves to, or its host name while it cannot be resolved, so that a node read from JSON during a
	   failed lookup can still be hashed, compared and listed */
	public URI getURI() {
		final String host = address.isUnresolved() ? address.getHostString() : address.getAddress().getHostAddress();

		return UriBuilder.fromPath("/{host}:{port}").build(host, address.getPort());
	}

	@Override
	public boolean equals(Object o) {
		final URI me = getURI();
		final URI other = ((Node) o).getURI();
		final boolean isEqual = me.equals(other);

		log.atTrace().log("Comparing {} with {} = {}", me, other, isEqual);
		return isEqual;
	}

	@Override
	public int hashCode() {
		return getURI().hashCode();
	}
}
