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

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;

/* The container queues every chunk of a body for the stream that the resource reads, and gives the pooled buffers back
   only when that stream is closed. A request that is answered without a read, to a path that is not there, to one that
   takes another method or to a part this release leaves off, never closes it, so it is closed here once there is an
   answer. A body that a reader of the resource has read is closed by then, and the request refuses its stream with an
   IllegalStateException, which says there is nothing left to release. */
@Slf4j
public class UnreadBodyFilter implements ContainerResponseFilter {
	@Override
	public void filter(final ContainerRequestContext request, final ContainerResponseContext response)
		throws IOException {

		try {
			request.getEntityStream().close();
		} catch (IllegalStateException ex) {
			log.atTrace().log("The body of the request was closed by the reader that took it");
		}
	}
}
