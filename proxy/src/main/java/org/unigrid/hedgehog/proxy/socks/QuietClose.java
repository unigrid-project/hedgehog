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

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/* The last handler of a client connection. A client that resets its connection, as browsers do routinely and
   anyone who reaches the listener can at will, fails a read, which is no fault of the proxy: Netty would log it as
   a warning with its stack trace once it reached the tail. The connection is closed instead and the failure logged
   by its class alone, as its message may name the client. */
@Sharable
final class QuietClose extends ChannelInboundHandlerAdapter {
	static final QuietClose DEFAULT = new QuietClose(LoggerFactory.getLogger(QuietClose.class));

	private final Logger log;

	QuietClose(Logger log) {
		this.log = log;
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		log.debug("Closing a SOCKS connection after {}", cause.getClass().getName());
		ctx.close();
	}
}
