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

package org.unigrid.hedgehog.proxy.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import java.util.Objects;

/* The pipeline of one SOCKS5 client connection: the greeting, then one command, then the relay. Each
   handler takes itself or its decoder out of the way once its part is over, and the handshake is given a deadline.
   Nothing is added later, so the handler added last stays last and closes whatever connection fails. */
public final class SocksInitializer extends ChannelInitializer<Channel> {
	static final long HANDSHAKE_TIMEOUT_MILLIS = 10_000;

	private final Tunnel tunnel;

	public SocksInitializer(Tunnel tunnel) {
		this.tunnel = Objects.requireNonNull(tunnel, "tunnel");
	}

	@Override
	protected void initChannel(Channel channel) {
		channel.pipeline().addLast(Socks5ServerEncoder.DEFAULT, new Socks5InitialRequestDecoder(),
			new SocksGreetingHandler(), new Socks5CommandRequestDecoder(),
			new HandshakeTimeout(HANDSHAKE_TIMEOUT_MILLIS), new SocksCommandHandler(tunnel), QuietClose.DEFAULT);
	}
}
