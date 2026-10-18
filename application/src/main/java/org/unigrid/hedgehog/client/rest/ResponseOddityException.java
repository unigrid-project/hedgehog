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

package org.unigrid.hedgehog.client.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.Response.StatusType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import lombok.Getter;

public class ResponseOddityException extends Exception {
	private static final int MAX_BODY = 8 * 1024;

	/* What the node sent along with the status; a ledger node explains there why it refused a request */
	@Getter private final String body;

	public ResponseOddityException(StatusType status) {
		this(status, "");
	}

	public ResponseOddityException(StatusType status, String body) {
		super(String.format("%d %s (%s)",
			status.getStatusCode(), Status.fromStatusCode(status.getStatusCode()),
			status.getReasonPhrase())
		);

		this.body = body;
	}

	public static ResponseOddityException of(Response response) {
		return new ResponseOddityException(response.getStatusInfo(), response.hasEntity() ? bodyOf(response) : "");
	}

	/* A refusal is a line or two of text, so no more than that is read of whatever else a node or a proxy sends. A
	   body that cannot be read is kept as none, since the status line still says what happened. */
	private static String bodyOf(Response response) {
		try (InputStream body = response.readEntity(InputStream.class)) {
			return new String(body.readNBytes(MAX_BODY), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			return "";
		}
	}
}
