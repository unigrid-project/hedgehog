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

import jakarta.ws.rs.core.Response;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * An answer that refuses a request and says why, as {"error": "..."}: the shape the ledger already answers with, so
 * that a client reads the reason of every refusal the same way.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RestError {
	/** The one field of the body, which a client reads the reason from. */
	public static final String FIELD = "error";

	/**
	 * A refusal with the given status and reason.
	 *
	 * @param status the HTTP status
	 * @param reason why the request was refused, written for the person who made it
	 * @return the answer
	 */
	public static Response of(Response.Status status, String reason) {
		return Response.status(status).entity(Map.of(FIELD, reason)).build();
	}
}
