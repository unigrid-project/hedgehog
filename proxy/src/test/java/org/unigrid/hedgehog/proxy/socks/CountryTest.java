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

package org.unigrid.hedgehog.proxy.socks;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class CountryTest {
	private static final Set<String> CODES = Set.of(Locale.getISOCountries());

	@Provide
	public Arbitrary<String> codes() {
		return Arbitraries.of(CODES);
	}

	@Provide
	public Arbitrary<String> unknown() {
		return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(5)
			.filter(code -> !CODES.contains(code.toUpperCase(Locale.ROOT)));
	}

	@Example
	public void shouldTakeAnEmptyStringForNoCountry() {
		assertThat(Country.of(""), is(Optional.empty()));
	}

	@Property(tries = 100)
	public void shouldKeepAKnownCodeUpperCase(@ForAll("codes") String code, @ForAll boolean lower) {
		final String given = lower ? code.toLowerCase(Locale.ROOT) : code;

		assertThat(Country.of(given), is(Optional.of(code)));
	}

	@Property(tries = 100)
	public void shouldRefuseWhatNoCountryHasAsItsCode(@ForAll("unknown") String code) {
		Expect.assertThrows(IllegalArgumentException.class, () -> Country.of(code));
	}

	/* Upper-casing alone would turn these into the codes IT, SE and SS */
	@Example
	public void shouldRefuseLettersThatOnlyUpperCaseToACode() {
		for (String code : new String[] { "ıt", "ſe", "ß" }) {
			Expect.assertThrows(IllegalArgumentException.class, () -> Country.of(code));
		}
	}

	@Example
	public void shouldRefuseCodeWithBlanks() {
		for (String code : new String[] { " ", " SE", "SE ", "S E", "\u0000\u0000" }) {
			Expect.assertThrows(IllegalArgumentException.class, () -> Country.of(code));
		}
	}
}
