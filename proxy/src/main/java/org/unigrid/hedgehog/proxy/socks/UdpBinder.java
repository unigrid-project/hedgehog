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

package org.unigrid.hedgehog.proxy.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetSocketAddress;

/* Binds the datagram socket of one association, which tests replace with an embedded channel */
@FunctionalInterface
interface UdpBinder {
	/* On the event loop of the control connection and on the local address the client reached the proxy at, so the
	   socket is on the listener's host. It reads into buffers that hold the largest datagram: Netty's default would
	   cut a longer one without a word. */
	UdpBinder NIO = (control, handler) -> new Bootstrap().group(control.eventLoop()).channel(NioDatagramChannel.class)
		.option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(UdpAssociation.MAX_PACKET_BYTES))
		.handler(handler).bind(new InetSocketAddress(((InetSocketAddress) control.localAddress()).getAddress(), 0));

	/* Binds a socket whose datagrams go to the handler */
	ChannelFuture bind(Channel control, ChannelHandler handler);
}
