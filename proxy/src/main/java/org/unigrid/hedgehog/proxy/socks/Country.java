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
import java.util.regex.Pattern;

/* The country an exit is to be in, named by its ISO 3166-1 alpha-2 code */
public final class Country {
	private static final Set<String> CODES = Set.of(Locale.getISOCountries());

	/* Two ASCII letters: upper-casing other characters could turn them into a code they do not spell */
	private static final Pattern LETTERS = Pattern.compile("[A-Za-z]{2}");

	private Country() {
		/* Static helpers only */
	}

	/* The code in upper case, or nothing for an empty string, which names no country. A code that no country has
	   is refused, whatever its case. */
	public static Optional<String> of(String code) {
		if (code.isEmpty()) {
			return Optional.empty();
		}

		final String upper = code.toUpperCase(Locale.ROOT);

		if (!LETTERS.matcher(code).matches() || !CODES.contains(upper)) {
			throw new IllegalArgumentException("Not an ISO 3166-1 alpha-2 country code");
		}

		return Optional.of(upper);
	}
}
