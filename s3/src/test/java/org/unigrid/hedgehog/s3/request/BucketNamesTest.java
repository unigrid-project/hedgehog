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

package org.unigrid.hedgehog.s3.request;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class BucketNamesTest {
	@Example
	public void acceptsNamesWithinTheRules() {
		for (final String name : List.of("abc", "a-b", "a.b", "my.bucket-name", "123", "a1b2c3",
			"amzn-s3-demox", "a".repeat(63))) {

			assertThat(BucketNames.valid(name), equalTo(true));
		}
	}

	@Example
	public void rejectsNamesThatBreakTheRules() {
		for (final String name : List.of("ab", "a".repeat(64), "Abc", "a_b", "a b", ".abc", "abc.", "-abc",
			"abc-", "a..b", "192.168.5.4", "xn--bucket", "sthree-bucket", "amzn-s3-demo-bucket",
			"bucket-s3alias", "bucket--ol-s3", "bucket.mrap", "bucket--x-s3", "bucket--table-s3")) {

			assertThat(BucketNames.valid(name), equalTo(false));
		}

		assertThat(BucketNames.valid(null), equalTo(false));
	}

	@Provide
	Arbitrary<String> validNames() {
		final Arbitrary<String> segment = Arbitraries.strings().withCharRange('a', 'z').numeric()
			.ofMinLength(1).ofMaxLength(20);

		return segment.list().ofMinSize(1).ofMaxSize(4)
			.map(parts -> String.join(".", parts))
			.filter(name -> name.length() >= 3 && name.length() <= 63)
			.filter(name -> !name.matches("(\\d{1,3}\\.){3}\\d{1,3}"))
			.filter(name -> !name.endsWith(".mrap"));
	}

	@Property
	public void acceptsValidGeneratedNames(@ForAll("validNames") final String name) {
		assertThat(BucketNames.valid(name), equalTo(true));
	}
}
