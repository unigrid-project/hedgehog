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

package org.unigrid.hedgehog.s3.http;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.s3.error.S3Error;

/*
   Support requests quote both ids, and clients correct their clock from the date of a refusal, so the date comes
   from the clock signatures are checked against.
*/
@RequiredArgsConstructor
public class RequestIdFilter implements ContainerResponseFilter {
	public static final String REQUEST_ID = "x-amz-request-id";
	public static final String HOST_ID = "x-amz-id-2";

	private static final String PROPERTY = RequestIdFilter.class.getName();
	private static final int REQUEST_ID_BYTES = 8;
	private static final int HOST_ID_BYTES = 48;

	private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
		.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

	private final Clock clock;

	private static byte[] random(final int length) {
		final byte[] bytes = new byte[length];

		ThreadLocalRandom.current().nextBytes(bytes);
		return bytes;
	}

	public static String of(final ContainerRequestContext request) {
		if (request.getProperty(PROPERTY) == null) {
			request.setProperty(PROPERTY, HexFormat.of().withUpperCase().formatHex(random(REQUEST_ID_BYTES)));
		}

		return (String) request.getProperty(PROPERTY);
	}

	@Override
	public void filter(final ContainerRequestContext request, final ContainerResponseContext response) {
		final String id = of(request);

		response.getHeaders().putSingle(REQUEST_ID, id);
		response.getHeaders().putSingle(HOST_ID, Base64.getEncoder().encodeToString(random(HOST_ID_BYTES)));
		response.getHeaders().putSingle(HttpHeaders.DATE, HTTP_DATE.format(clock.instant()));

		if (response.getEntity() instanceof S3Error error) {
			error.setRequestId(id);
		}
	}
}
