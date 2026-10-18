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

package org.unigrid.hedgehog.s3.auth;

import java.util.regex.Pattern;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;

/*
   The access key and the scope a request was signed for, written <key>/<date>/<region>/s3/aws4_request. Any region
   is accepted, as the gateway serves only one.
*/
public record Credential(String accessKeyId, String date, String region) {
	private static final int PARTS = 5;
	private static final Pattern DATE = Pattern.compile("\\d{8}");

	static Credential parse(final String value, final S3ErrorCode malformed) {
		final String[] parts = value.split("/", -1);

		if (parts.length != PARTS || !DATE.matcher(parts[1]).matches() || parts[2].isBlank()
			|| !SigV4.SERVICE.equals(parts[3]) || !SigV4.TERMINATOR.equals(parts[4])) {
			throw new S3Exception(malformed, "The credential '" + value + "' is not of the form "
				+ "<access key>/<date>/<region>/s3/aws4_request"
			);
		}

		return new Credential(parts[0], parts[1], parts[2]);
	}

	public String scope() {
		return String.join("/", date, region, SigV4.SERVICE, SigV4.TERMINATOR);
	}

	public byte[] signingKey(final String secretKey) {
		return SigV4.signingKey(secretKey, date, region, SigV4.SERVICE);
	}
}
