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

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.Response.StatusType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

public class ResponseOddityException extends Exception {
	private static final int MAX_BODY = 8 * 1024;

	/* What the node sent along with the status; a ledger node explains there why it refused a request */
	@Getter private final String body;

	/* Whether the node sent the body as plain text: a sentence in its own words, which a page of whatever answers in
	   its place is not, nor JSON that names its fields */
	@Getter private final boolean plainText;

	@Getter private final int statusCode;

	public ResponseOddityException(StatusType status) {
		this(status, "");
	}

	public ResponseOddityException(StatusType status, String body) {
		this(status, body, false);
	}

	private ResponseOddityException(StatusType status, String body, boolean plainText) {
		super(describe(status));
		this.body = body;
		this.plainText = plainText;
		this.statusCode = status.getStatusCode();
	}

	/* A status that jakarta has no name for, such as one of a proxy in between, is shown by its number. The phrase that
	   a node words a known status in is shown only where it is not the name of the status already. */
	private static String describe(StatusType status) {
		final int code = status.getStatusCode();
		final Status known = Status.fromStatusCode(code);
		final String phrase = status.getReasonPhrase();

		if (known != null) {
			return StringUtils.isBlank(phrase) || known.getReasonPhrase().equals(phrase)
				? String.format("%d %s", code, known.getReasonPhrase())
				: String.format("%d %s (%s)", code, known.getReasonPhrase(), phrase);
		}

		return StringUtils.isBlank(phrase) ? String.valueOf(code) : String.format("%d (%s)", code, phrase);
	}

	public static ResponseOddityException of(Response response) {
		return new ResponseOddityException(response.getStatusInfo(), response.hasEntity() ? bodyOf(response) : "",
			isSentAsPlainText(response));
	}

	/* By the header as it was sent, whatever follows the type in it: a header that names no type is no plain text,
	   and does not fail the request as one that is parsed would */
	private static boolean isSentAsPlainText(Response response) {
		final String type = StringUtils.substringBefore(response.getHeaderString(HttpHeaders.CONTENT_TYPE), ";");

		return MediaType.TEXT_PLAIN.equalsIgnoreCase(StringUtils.strip(type));
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
