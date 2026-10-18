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

import java.util.function.Function;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import software.amazon.awssdk.services.s3.S3Client;

public class RoutingTest {
	private static final String BUCKET = "my.photos";
	private static final String KEY = "2024/summer/a b+c~d%e.jpg";

	private static <T> T serve(final Function<Integer, S3Client> clients, final Function<S3Client, T> request)
		throws Exception {

		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.start(port, EchoResource.class); S3Client client = clients.apply(port)) {
			return request.apply(client);
		}
	}

	private static String object(final S3Client client) {
		return client.getObjectAsBytes(request -> request.bucket(BUCKET).key(KEY)).asUtf8String();
	}

	private static String location(final S3Client client) {
		return client.getBucketLocation(request -> request.bucket(BUCKET)).locationConstraintAsString();
	}

	@Example
	public void findsTheObjectOfAPathStyleRequest() throws Exception {
		assertThat(serve(S3Fixture::client, RoutingTest::object), equalTo(BUCKET + "/" + KEY));
	}

	@Example
	public void findsTheObjectOfAVirtualHostStyleRequest() throws Exception {
		assertThat(serve(S3Fixture::virtualHostClient, RoutingTest::object), equalTo(BUCKET + "/" + KEY));
	}

	@Example
	public void findsTheBucketOfAVirtualHostStyleRequest() throws Exception {
		assertThat(serve(S3Fixture::virtualHostClient, RoutingTest::location), equalTo(BUCKET + " LOCATION"));
	}

	@Example
	public void findsTheBucketOfAPathStyleRequest() throws Exception {
		assertThat(serve(S3Fixture::client, RoutingTest::location), equalTo(BUCKET + " LOCATION"));
	}
}
