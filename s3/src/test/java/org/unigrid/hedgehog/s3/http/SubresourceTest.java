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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.jqwik.Expect;

public class SubresourceTest {
	private static final List<String> PARAMETERS = List.of("prefix", "delimiter", "max-keys", "marker", "versionId",
		"partNumber", "encoding-type", "response-content-type", "X-Amz-Signature", "x-id"
	);

	@Provide
	public Arbitrary<Map<String, List<String>>> provideParameters() {
		return Arbitraries.of(PARAMETERS).set().ofMaxSize(PARAMETERS.size()).map(names -> {
			final Map<String, List<String>> query = new HashMap<>();

			names.forEach(name -> query.put(name, List.of("")));
			return query;
		});
	}

	@Provide
	public Arbitrary<Subresource> provideSubresource() {
		return Arbitraries.of(Subresource.class).filter(subresource -> subresource != Subresource.NONE);
	}

	@Property
	public void findsTheSubresourceAmongOtherParameters(@ForAll("provideSubresource") Subresource subresource,
		@ForAll("provideParameters") Map<String, List<String>> query) {

		query.put(subresource.getParameter(), List.of(""));
		assertThat(Subresource.of(query), equalTo(subresource));
	}

	@Property
	public void findsNoSubresourceInPlainParameters(@ForAll("provideParameters") Map<String, List<String>> query) {
		assertThat(Subresource.of(query), equalTo(Subresource.NONE));
	}

	@Property
	public void refusesSubresourcesItDoesNotImplement(@ForAll("provideParameters") Map<String, List<String>> query,
		@ForAll("provideUnsupported") String unsupported) {

		query.put(unsupported, List.of(""));
		assertThat(Expect.assertThrows(S3Exception.class, () -> Subresource.of(query)).getCode(),
			equalTo(S3ErrorCode.NotImplemented)
		);
	}

	@Provide
	public Arbitrary<String> provideUnsupported() {
		return Arbitraries.of("acl", "policy", "website", "replication", "notification", "logging", "encryption",
			"accelerate", "analytics", "inventory", "metrics", "intelligent-tiering", "ownershipControls",
			"publicAccessBlock", "requestPayment", "restore", "select", "torrent", "attributes"
		);
	}
}
