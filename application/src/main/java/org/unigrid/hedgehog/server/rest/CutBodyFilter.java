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

package org.unigrid.hedgehog.server.rest;

import io.netty.channel.Channel;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.glassfish.jersey.netty.httpserver.NettySecurityContext;

/* The container reads any end of a body as the end of a whole one, and a body that was cut short is given an end like
   any other. A resource must not act on a part of a body, so the stream that it reads from reports the cut as the
   failure it is. */
@PreMatching
public class CutBodyFilter implements ContainerRequestFilter {
	@Override
	public void filter(final ContainerRequestContext context) {
		if (context.getSecurityContext() instanceof NettySecurityContext netty) {
			context.setEntityStream(new CutAwareStream(context.getEntityStream(),
				netty.getNettyContext().channel()));
		}
	}

	private static final class CutAwareStream extends FilterInputStream {
		private final Channel channel;

		private CutAwareStream(final InputStream stream, final Channel channel) {
			super(stream);
			this.channel = channel;
		}

		private int ended(final int read) throws IOException {
			if (read == -1 && Boolean.TRUE.equals(channel.attr(RequestBodyLimit.CUT).get())) {
				throw new IOException("The body of the request was cut short");
			}

			return read;
		}

		@Override
		public int read() throws IOException {
			return ended(super.read());
		}

		@Override
		public int read(final byte[] buffer, final int offset, final int length) throws IOException {
			return ended(super.read(buffer, offset, length));
		}
	}
}
