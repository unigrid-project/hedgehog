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

import jakarta.annotation.Priority;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.command.option.PreviewOptions;

/* The endpoints of a part that this release leaves off stay, so that a client is told why the node does not serve
   them, rather than that there are none. The filter runs once the token is checked and the resource is matched, and
   before its method reaches anything the part stands on; the part is named by the path of the resource. */
@PreviewPart
@Priority(Priorities.USER)
public class PreviewPartFilter implements ContainerRequestFilter {
	private static final String LEFT_OFF = "This release does not include the %s part";

	@Override
	public void filter(final ContainerRequestContext context) {
		if (!PreviewOptions.isPreview()) {
			final Class<?> resource = context.getUriInfo().getMatchedResources().getFirst().getClass();
			final String part = StringUtils.removeStart(resource.getAnnotation(Path.class).value(), "/");

			context.abortWith(Response.status(Response.Status.SERVICE_UNAVAILABLE)
				.entity(String.format(LEFT_OFF, part)).type(MediaType.TEXT_PLAIN_TYPE).build());
		}
	}
}
