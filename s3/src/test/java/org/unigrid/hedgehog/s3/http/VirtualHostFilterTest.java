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

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.jqwik.Expect;

public class VirtualHostFilterTest {
	private static final String DOMAIN = "s3.example.com";

	@Provide
	public Arbitrary<String> provideBucket() {
		return Arbitraries.strings().withCharRange('a', 'z').numeric().withChars(".-").ofMinLength(2).ofMaxLength(63)
			.map(name -> "b" + name);
	}

	@Property
	public void takesTheBucketFromTheSubdomain(@ForAll("provideBucket") String bucket,
		@ForAll @IntRange(min = 1, max = 65535) int port, @ForAll boolean upperCase) {

		final String host = bucket + "." + DOMAIN + ":" + port;

		assertThat(VirtualHostFilter.bucket(upperCase ? host.toUpperCase(Locale.ROOT) : host, DOMAIN),
			equalTo(Optional.of(bucket))
		);
	}

	@Example
	public void leavesTheDomainItselfAndOtherHostsToPathStyle() {
		assertThat(VirtualHostFilter.bucket(DOMAIN, DOMAIN), equalTo(Optional.empty()));
		assertThat(VirtualHostFilter.bucket(DOMAIN + ":443", DOMAIN), equalTo(Optional.empty()));
		assertThat(VirtualHostFilter.bucket("localhost:52885", DOMAIN), equalTo(Optional.empty()));
		assertThat(VirtualHostFilter.bucket("[::1]:52885", DOMAIN), equalTo(Optional.empty()));
		assertThat(VirtualHostFilter.bucket("bucket.other" + DOMAIN, DOMAIN), equalTo(Optional.empty()));
	}

	@Example
	public void refusesASubdomainThatCannotNameABucket() {
		for (String host : new String[] { "a_b." + DOMAIN, "a/b." + DOMAIN, "-ab." + DOMAIN }) {
			assertThat(Expect.assertThrows(S3Exception.class, () -> VirtualHostFilter.bucket(host, DOMAIN)).getCode(),
				equalTo(S3ErrorCode.InvalidBucketName)
			);
		}
	}

	@Example
	public void movesTheBucketIntoThePath() {
		assertThat(VirtualHostFilter.rewrite(URI.create("http://b.s3:1/a%20b/c%2Fd?tagging&x=a%26b"), "b"),
			equalTo(URI.create("http://b.s3:1/b/a%20b/c%2Fd?tagging&x=a%26b"))
		);
		assertThat(VirtualHostFilter.rewrite(URI.create("http://b.s3:1/?location"), "b"),
			equalTo(URI.create("http://b.s3:1/b?location"))
		);
		assertThat(VirtualHostFilter.rewrite(URI.create("http://b.s3:1/"), "b"), equalTo(URI.create("http://b.s3:1/b")));
	}
}
