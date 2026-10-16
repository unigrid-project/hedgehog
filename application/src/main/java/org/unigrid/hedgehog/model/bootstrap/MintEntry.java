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
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Address;
import org.unigrid.hedgehog.ledger.MintSchedule;
import org.unigrid.hedgehog.ledger.Reference;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;

/*
   An entry of the mint storage spork read as a mint of the ledger. The spork keeps its shape, an address string and
   a number of 32 bits that the model holds as an int and the wire as a u32. Here the number is read unsigned, and
   what it says depends on the kind of the address, since a height of the old chain is something only an address of
   the old chain can have.

   An old-style address, a legacy base58 address: the number is a height of the old chain. The entry is due at
   MintSchedule.dueTime(height), and an entry at or below the snapshot tip is already in the snapshot balances and
   never mints.

   A new-style address, an Ed25519 account of the ledger in 64 lower-case hex characters: it has no old height, so
   the number is a time in whole seconds since the Unix epoch, good until 2106-02-07. The entry is due at that time.
   The snapshot tip is no part of it, since no snapshot balance can exist for such an account. Upper-case hex is not
   an account, which leaves one spelling per account and so one reference per grant.

   An address that is neither is an entry the ledger cannot mint, which is for the caller to skip.
 */
public record MintEntry(Address recipient, long number, BigDecimal amount) {
	public enum Kind {
		OLD, NEW
	}

	private static final long MAXIMUM_NUMBER = Location.MAXIMUM_NUMBER;
	private static final Pattern ACCOUNT = Pattern.compile("[0-9a-f]{" + 2 * AccountKey.SIZE + "}");
	private static final long MILLIS_PER_SECOND = 1000;
	private static final int UNIT_DIGITS = Coin.DECIMALS;
	private static final int WHOLE_DIGITS_OF_A_LONG = 11;
	private static final BigInteger BEYOND_A_LONG = BigInteger.ONE.shiftLeft(Long.SIZE - 1);

	public MintEntry {
		Objects.requireNonNull(recipient);
		Objects.requireNonNull(amount);

		if (number < 0 || number > MAXIMUM_NUMBER) {
			throw new IllegalArgumentException("The number of a mint entry is of 32 bits, found " + number);
		}
	}

	public static Optional<MintEntry> of(final Location location, final BigDecimal amount) {
		return Optional.ofNullable(amount).flatMap(value -> Optional.ofNullable(location.getAddress())
			.map(address -> address.getWif()).flatMap(MintEntry::recipientOf)
			.map(recipient -> new MintEntry(recipient, Integer.toUnsignedLong(location.getHeight()), value)));
	}

	public static boolean isAccount(final String address) {
		return Objects.nonNull(address) && ACCOUNT.matcher(address).matches();
	}

	/* The int to keep for a number that was typed or sent. One rule for the command line and the node, so they agree
	   on what a number may be for each kind of address. */
	public static int heightOf(final String address, final long number) {
		return isAccount(address) ? secondsOf(number) : oldHeightOf(number);
	}

	private static int secondsOf(final long number) {
		if (number < 0 || number > MAXIMUM_NUMBER) {
			throw new IllegalArgumentException("A ledger account takes a time in whole seconds from 0 to "
				+ MAXIMUM_NUMBER + " (1970 to 2106), found " + number);
		}

		return (int) number;
	}

	private static int oldHeightOf(final long number) {
		try {
			return Math.toIntExact(number);
		} catch (ArithmeticException ex) {
			throw new IllegalArgumentException("An old-style address takes a height of the old chain, up to "
				+ Integer.MAX_VALUE + ", found " + number, ex);
		}
	}

	private static Optional<Address> recipientOf(final String address) {
		if (isAccount(address)) {
			return Optional.of(AccountKey.fromHex(address));
		}

		try {
			final byte[] hash = LegacyAddress.decode(address);

			return Optional.of(new org.unigrid.hedgehog.ledger.LegacyAddress(hash));
		} catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	public Kind kind() {
		return recipient instanceof AccountKey ? Kind.NEW : Kind.OLD;
	}

	public OptionalLong dueMillis(final MintSchedule schedule) {
		if (kind() == Kind.NEW) {
			return OptionalLong.of(Math.multiplyExact(number, MILLIS_PER_SECOND));
		}

		return schedule.mintsOnLedger(number) ? OptionalLong.of(schedule.dueTime(number)) : OptionalLong.empty();
	}

	public Reference reference() {
		return Reference.ofSpork(recipient, number);
	}

	public OptionalLong units() {
		final BigInteger units = unitsOf(amount);

		return units.signum() > 0 && units.bitLength() < Long.SIZE ? OptionalLong.of(units.longValueExact())
			: OptionalLong.empty();
	}

	/* The coins cut at eight decimals as whole units, never rounded up. Nothing, a negative amount and what is
	   below one unit count as 0; an amount that a long cannot hold is given as 2^63, which is enough for a caller
	   to tell. The digits before the point are counted first, as an absurd exponent would be expanded in full. */
	public static BigInteger unitsOf(final BigDecimal coins) {
		final long wholeDigits = (long) coins.precision() - coins.scale();

		if (coins.signum() <= 0 || wholeDigits < -UNIT_DIGITS) {
			return BigInteger.ZERO;
		}

		return wholeDigits > WHOLE_DIGITS_OF_A_LONG ? BEYOND_A_LONG
			: coins.setScale(UNIT_DIGITS, RoundingMode.DOWN).unscaledValue();
	}
}
