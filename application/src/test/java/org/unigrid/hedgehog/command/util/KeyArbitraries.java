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

package org.unigrid.hedgehog.command.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import org.unigrid.hedgehog.model.crypto.Signature;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class KeyArbitraries {
	private static final String HEX_DIGITS = "0123456789abcdefABCDEF";
	private static final String FOREIGN = "ghxyzGHXYZ!?.:";
	private static final int MAX_HEX = 64;

	public static Arbitrary<Signature> keys() {
		return Arbitraries.create(KeyArbitraries::generate);
	}

	public static Arbitrary<String> malformedHex() {
		final Arbitrary<String> hex = Arbitraries.strings().withChars(HEX_DIGITS).ofMaxLength(MAX_HEX);
		final Arbitrary<String> oddLength = hex.filter(text -> text.length() % 2 == 1);
		final Arbitrary<String> foreign = Combinators.combine(hex, Arbitraries.chars().with(FOREIGN), hex)
			.as((before, character, after) -> before + character + after);

		return Arbitraries.oneOf(oddLength, foreign);
	}

	@SneakyThrows
	private static Signature generate() {
		return new Signature();
	}
}
