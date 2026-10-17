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

package org.unigrid.hedgehog.model.spork;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/* An amount of coins in a spork as the wire carries it: text that ends with a zero byte, which the encoders write
   with toPlainString(). Anyone can send a frame, and `new BigDecimal("1E+999999999")` is a number of a few bytes that
   writes out as a billion digits, so signing it, which writes it out, takes gigabytes. An amount is therefore taken
   only with at most MAX_DECIMALS decimals (not counting trailing zeros), at most MAX_INTEGER_DIGITS digits before the
   point and a text of at most MAX_TEXT characters, which leaves room for every real amount (the ledger counts in
   units of 1e-8 in a long, a bit under 12 digits of coins). A frame that holds more is corrupt. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SporkAmount {
	public static final int MAX_DECIMALS = 8;
	public static final int MAX_INTEGER_DIGITS = 12;
	public static final int MAX_TEXT = 48;
	private static final int SHOWN = 24;

	/* The value is judged without its trailing zeros: 1.500000000 and 1E+9 are real amounts, that a node of an
	   earlier release took and may have written. The scale as it is stored is bounded all the same, as it is what
	   toPlainString() writes out: 0E-999999999 is zero with a billion zeros, and a scale near the end of the int
	   range overflows the sum of the digits. */
	public static boolean isWithinBounds(final BigDecimal amount) {
		if (amount.scale() > MAX_TEXT || amount.scale() < -MAX_INTEGER_DIGITS) {
			return false;
		}

		final BigDecimal stripped = amount.stripTrailingZeros();

		return stripped.scale() <= MAX_DECIMALS && stripped.precision() - stripped.scale() <= MAX_INTEGER_DIGITS;
	}

	public static BigDecimal read(final ByteBuf in) {
		final int length = in.bytesBefore((byte) 0);

		if (length < 0 || length > MAX_TEXT) {
			throw new CorruptedFrameException("An amount is no text of up to " + MAX_TEXT + " characters");
		}

		final byte[] text = new byte[length];

		in.readBytes(text);
		in.skipBytes(1 /* The zero that ends the text */);
		return parsed(new String(text, StandardCharsets.UTF_8));
	}

	/* An empty text is no amount */
	public static Optional<BigDecimal> readOptional(final ByteBuf in) {
		final int length = in.bytesBefore((byte) 0);

		if (length == 0) {
			in.skipBytes(1);
			return Optional.empty();
		}

		return Optional.of(read(in));
	}

	private static BigDecimal parsed(final String text) {
		final BigDecimal amount;

		try {
			amount = new BigDecimal(text);
		} catch (NumberFormatException ex) {
			throw new CorruptedFrameException("An amount is no number: " + shown(text));
		}

		if (!isWithinBounds(amount)) {
			throw new CorruptedFrameException("An amount is out of bounds, at most " + MAX_DECIMALS
				+ " decimals and " + MAX_INTEGER_DIGITS + " digits before the point: " + shown(text));
		}

		return amount;
	}

	private static String shown(final String text) {
		return text.length() <= SHOWN ? text : text.substring(0, SHOWN) + "...";
	}
}
