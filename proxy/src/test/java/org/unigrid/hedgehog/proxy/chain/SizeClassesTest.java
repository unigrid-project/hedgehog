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

package org.unigrid.hedgehog.proxy.chain;

import java.util.List;
import java.util.OptionalInt;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

public class SizeClassesTest {
	private static final int DEFAULT_MAX_ENTRY_BYTES = 16 * 1024;

	@Example
	public void defaultSporkHasThreeClasses() {
		assertThat(SizeClasses.of(DEFAULT_MAX_ENTRY_BYTES), equalTo(List.of(1024, 4096, 16384)));
	}

	@Example
	public void tinyChainHasOneClass() {
		assertThat(SizeClasses.of(1024), equalTo(List.of(1024)));
	}

	@Example
	public void theLargestEntryIsAlwaysAClass() {
		assertThat(SizeClasses.of(2000), equalTo(List.of(1024, 2000)));
		assertThat(SizeClasses.of(4096), equalTo(List.of(1024, 4096)));
		assertThat(SizeClasses.of(5000), equalTo(List.of(1024, 4096, 5000)));
		assertThat(SizeClasses.of(500), equalTo(List.of(500)));
	}

	@Example
	public void fitsTheSmallestClassThatHoldsTheEntry() {
		assertThat(SizeClasses.fit(0, DEFAULT_MAX_ENTRY_BYTES), is(OptionalInt.of(1024)));
		assertThat(SizeClasses.fit(1024, DEFAULT_MAX_ENTRY_BYTES), is(OptionalInt.of(1024)));
		assertThat(SizeClasses.fit(1025, DEFAULT_MAX_ENTRY_BYTES), is(OptionalInt.of(4096)));
		assertThat(SizeClasses.fit(4097, DEFAULT_MAX_ENTRY_BYTES), is(OptionalInt.of(16384)));
		assertThat(SizeClasses.fit(16384, DEFAULT_MAX_ENTRY_BYTES), is(OptionalInt.of(16384)));
		assertThat(SizeClasses.fit(16385, DEFAULT_MAX_ENTRY_BYTES), is(OptionalInt.empty()));
	}

	@Example
	public void knowsItsClasses() {
		assertThat(SizeClasses.isClass(1024, DEFAULT_MAX_ENTRY_BYTES), is(true));
		assertThat(SizeClasses.isClass(4096, DEFAULT_MAX_ENTRY_BYTES), is(true));
		assertThat(SizeClasses.isClass(16384, DEFAULT_MAX_ENTRY_BYTES), is(true));
		assertThat(SizeClasses.isClass(2048, DEFAULT_MAX_ENTRY_BYTES), is(false));
		assertThat(SizeClasses.isClass(4096, 2048), is(false));
	}

	@Property(tries = 200)
	public void theSmallestIsTheFirstClass(@ForAll @IntRange(min = Entry.OVERHEAD, max = 1024 * 1024) int max) {
		assertThat(SizeClasses.smallest(max), is(SizeClasses.of(max).get(0)));
	}

	@Example
	public void theSmallestClassIsAKibibyteOrTheLargestEntry() {
		assertThat(SizeClasses.smallest(DEFAULT_MAX_ENTRY_BYTES), is(1024));
		assertThat(SizeClasses.smallest(1024), is(1024));
		assertThat(SizeClasses.smallest(500), is(500));
	}

	@Property(tries = 200)
	public void theFitIsAClassAndNoSmallerClassHoldsTheEntry(@ForAll @IntRange(min = 1024, max = 1024 * 1024) int max,
		@ForAll @IntRange(min = 0, max = 1024 * 1024) int entry) {
		final OptionalInt fit = SizeClasses.fit(entry, max);

		assertThat(fit.isPresent(), is(entry <= max));

		if (fit.isPresent()) {
			assertThat(SizeClasses.isClass(fit.getAsInt(), max), is(true));
			assertThat(fit.getAsInt(), greaterThanOrEqualTo(entry));
			SizeClasses.of(max).stream().filter(size -> size < fit.getAsInt())
				.forEach(size -> assertThat(size, lessThan(entry)));
		}
	}
}
