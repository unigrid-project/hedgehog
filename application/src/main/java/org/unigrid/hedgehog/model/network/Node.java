/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.Future;
import jakarta.ws.rs.core.UriBuilder;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.model.network.packet.Packet;

@Data
@Slf4j
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Node {

	private InetSocketAddress address;
	@JsonIgnore
	@Builder.Default
	@ToString.Exclude
	private Optional<Connection> connection = Optional.empty();
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

	@SneakyThrows
	public boolean isMe() {
		final AtomicBoolean found = new AtomicBoolean();

		try {
			NetworkInterface.getNetworkInterfaces().asIterator().forEachRemaining(ni -> {
				ni.inetAddresses().forEach(a -> {
					final InetSocketAddress socketAddress = new InetSocketAddress(a.getHostAddress(),
						NetOptions.getPort()
					);

					if (equals(Node.builder().address(socketAddress).build())) {
						found.set(true);
					}
				});
			});

			if (!found.get()) {
				final Node me = Node.fromAddress(NetOptions.getHost());
				final String address = String.format("%s:%s", NetOptions.getHost(), NetOptions.getPort());

				return equals(Node.fromAddress(address));
			}

		} catch (URISyntaxException ex) {
			log.atTrace().log("Invalid host/address format {}", ex.getMessage());
			return false;
		}

		return found.get();
	}

	public static void send(Packet packet, Node node, Optional<BiConsumer<Node, Future>> consumer) {
		log.atDebug().log("Send all packet " + packet.toString());
		if (node.getConnection().isPresent()) {
			final ChannelFuture out = node.getConnection().get().getChannel().writeAndFlush(packet);

			out.addListener(f -> {
				consumer.ifPresent(c -> {
					c.accept(node, f);
				});
			});
		}
	}

	public static Node fromURI(URI uri) throws URISyntaxException {
		int port = uri.getPort();

		if (uri.getPort() == -1) {
			port = NetOptions.DEFAULT_PORT;
		}

		return Node.builder().address(new InetSocketAddress(uri.getHost(), port)).build();
	}

	public static Node fromAddress(String address) throws URISyntaxException {
		return fromURI(new URI(null, address, null, null, null).parseServerAuthority());
	}

	public URI getURI() {
		return UriBuilder.fromPath("/{host}:{port}").build(address.getAddress().getHostAddress(), address.getPort());
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
