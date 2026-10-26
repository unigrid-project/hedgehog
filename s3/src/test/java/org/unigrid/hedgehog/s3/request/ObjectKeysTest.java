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

import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class ObjectKeysTest {
	private static final int MAX_BYTES = 1024;
	private static final String ASCII = "a";

	@Property
	public void acceptsAsciiKeysUpToTheByteLimit(@ForAll @IntRange(min = 1, max = MAX_BYTES) final int bytes) {
		assertThat(ObjectKeys.valid(ASCII.repeat(bytes)), equalTo(true));
	}

	@Property
	public void rejectsAsciiKeysBeyondTheByteLimit(
		@ForAll @IntRange(min = MAX_BYTES + 1, max = 2 * MAX_BYTES) final int bytes) {

		assertThat(ObjectKeys.valid(ASCII.repeat(bytes)), equalTo(false));
	}

	@Property
	public void countsUtf8BytesNotCharacters(@ForAll @IntRange(min = 1, max = 600) final int characters) {
		assertThat(ObjectKeys.valid("é".repeat(characters)), equalTo(characters <= MAX_BYTES / 2));
	}

	@Example
	public void acceptsAnyNonEmptyKeyAtTheBoundary() {
		assertThat(ObjectKeys.valid(ASCII), equalTo(true));
		assertThat(ObjectKeys.valid(ASCII.repeat(MAX_BYTES) + "é"), equalTo(false));
		assertThat(ObjectKeys.valid(ASCII + "€".repeat(341)), equalTo(true));
		assertThat(ObjectKeys.valid(ASCII + "€".repeat(341) + ASCII), equalTo(false));
	}

	@Example
	public void rejectsMissingKeys() {
		assertThat(ObjectKeys.valid(null), equalTo(false));
		assertThat(ObjectKeys.valid(""), equalTo(false));
	}
}
