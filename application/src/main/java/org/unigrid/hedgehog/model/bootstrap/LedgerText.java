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

package org.unigrid.hedgehog.model.bootstrap;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Address;
import org.unigrid.hedgehog.model.spork.SporkAmount;

/* How a person writes who gets coins and how many, shared by the REST interface, the command line and the mint
   spork so that all three read the same text the same way. A ledger account is 64 lower-case hex characters, one
   spelling per account; a legacy address is base58. Coins are plain decimal text and never rounded: what does not
   fit eight decimals in a long of units is refused. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LedgerText {
	private static final Pattern ACCOUNT = Pattern.compile("[0-9a-f]{" + 2 * AccountKey.SIZE + "}");
	private static final Pattern PLAIN_DECIMAL = Pattern.compile("[0-9]+(\\.[0-9]+)?");
	private static final String LARGEST = coins(Long.MAX_VALUE);

	public static boolean isAccount(final String text) {
		return Objects.nonNull(text) && ACCOUNT.matcher(text).matches();
	}

	public static Optional<Address> address(final String text) {
		if (isAccount(text)) {
			return Optional.of(AccountKey.fromHex(text));
		}

		try {
			return Optional.of(new org.unigrid.hedgehog.ledger.LegacyAddress(LegacyAddress.decode(text)));
		} catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	/* An address as a person types it: an account in any case, since case means nothing in hex, but a legacy address
	   exactly as written, as case is part of base58 */
	public static Optional<Address> typed(final String text) {
		final String lowerCase = text.toLowerCase(Locale.ROOT);

		return address(isAccount(lowerCase) ? lowerCase : text);
	}

	public static String text(final Address address) {
		return switch (address) {
			case AccountKey account -> account.toString();
			case org.unigrid.hedgehog.ledger.LegacyAddress legacy -> LegacyAddress.encode(legacy.bytes());
		};
	}

	public static long units(final String coins) {
		requirePlainDecimal(coins);

		final BigDecimal amount = new BigDecimal(coins).stripTrailingZeros();

		if (amount.scale() > Coin.DECIMALS) {
			throw refused("has at most " + Coin.DECIMALS + " decimals");
		}

		if (amount.signum() == 0) {
			throw refused("is above zero");
		}

		try {
			return amount.movePointRight(Coin.DECIMALS).longValueExact();
		} catch (ArithmeticException ex) {
			throw refused("is at most " + LARGEST);
		}
	}

	public static String coins(final long units) {
		return BigDecimal.valueOf(units, Coin.DECIMALS).toPlainString();
	}

	private static void requirePlainDecimal(final String coins) {
		if (coins.isEmpty()) {
			throw refused("is empty");
		}

		if (coins.length() > SporkAmount.MAX_TEXT || !PLAIN_DECIMAL.matcher(coins).matches()) {
			throw refused("is plain digits with an optional point, such as 12.5, of at most "
				+ SporkAmount.MAX_TEXT + " characters, without sign, exponent, comma or space");
		}
	}

	private static IllegalArgumentException refused(final String reason) {
		return new IllegalArgumentException("An amount of coins " + reason);
	}
}
