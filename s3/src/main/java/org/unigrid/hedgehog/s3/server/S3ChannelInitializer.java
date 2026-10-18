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

package org.unigrid.hedgehog.s3.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.ssl.SslContext;
import java.util.Optional;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;

/*
   Jersey's own initializer keeps Netty's 4 KiB limit on the request line, which a long key with a presigned query
   exceeds, and leaves connections open after a client asked to close them.
*/
@RequiredArgsConstructor
final class S3ChannelInitializer extends ChannelInitializer<SocketChannel> {
	private static final int MAX_REQUEST_LINE = 16 * 1024;
	private static final int MAX_HEADERS = 16 * 1024;
	private static final int MAX_CHUNK = 64 * 1024;

	private final Optional<SslContext> ssl;
	private final Callable<ChannelHandler> requests;

	@Override
	protected void initChannel(final SocketChannel channel) throws Exception {
		ssl.ifPresent(context -> channel.pipeline().addLast(context.newHandler(channel.alloc())));

		channel.pipeline().addLast(new HttpServerCodec(MAX_REQUEST_LINE, MAX_HEADERS, MAX_CHUNK),
			new HttpServerKeepAliveHandler(), requests.call()
		);
	}
}
