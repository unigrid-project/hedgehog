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

import java.io.ByteArrayInputStream;
import java.time.Clock;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

/* Signs requests the way every AWS SDK does, so the signatures the server computes can be checked against it */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SdkSigner {
	public static final String REGION = "us-east-1";

	public static SdkHttpRequest sign(final SdkHttpRequest request, final byte[] body, final String accessKey,
		final String secretKey, final Clock clock) {

		return AwsV4HttpSigner.create().sign(signing -> signing
			.identity(AwsCredentialsIdentity.create(accessKey, secretKey)).request(request)
			.payload(() -> new ByteArrayInputStream(body))
			.putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, SigV4.SERVICE)
			.putProperty(AwsV4HttpSigner.REGION_NAME, REGION)
			.putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, false)
			.putProperty(AwsV4HttpSigner.NORMALIZE_PATH, false)
			.putProperty(HttpSigner.SIGNING_CLOCK, clock)
		).request();
	}
}
