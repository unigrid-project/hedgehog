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

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.commons.lang3.Validate;

@Data @ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class LedgerFee extends GridSpork implements Serializable {
	private static final long serialVersionUID = 4850672139516780234L;

	public LedgerFee() {
		setType(Type.LEDGER_FEE);
		setFlags((short) (getFlags() | Flag.GOVERNED.getValue()));
		setData(new SporkData());
	}

	@Data
	public static class SporkData implements ChunkData {
		private static final long serialVersionUID = -2610843305276195837L;

		public static final int DECIMALS = 8;

		/* One coin, so a mistyped fee cannot price every transfer out of the ledger */
		public static final long MAX_UNITS = 100_000_000L;

		/* A hundred coins, so a mistyped amount cannot keep everyone without an account out of the ledger */
		public static final long MAX_NEW_ACCOUNT_UNITS = 100 * MAX_UNITS;

		private BigDecimal minimumTransferFee = BigDecimal.ZERO;

		/* The least amount of a transfer to an address that holds nothing in the ledger yet */
		private BigDecimal minimumNewAccountAmount = BigDecimal.ZERO;

		@Override
		public SporkData empty() {
			return new SporkData();
		}

		public void validate() {
			validate("minimumTransferFee", minimumTransferFee, MAX_UNITS);
			validate("minimumNewAccountAmount", minimumNewAccountAmount, MAX_NEW_ACCOUNT_UNITS);
		}

		/* Cheapest checks first: an amount with an absurd exponent is refused before anything is rescaled */
		private static void validate(String name, BigDecimal amount, long maxUnits) {
			Validate.isTrue(Objects.nonNull(amount), "%s is required", name);
			Validate.isTrue(amount.signum() >= 0, "%s cannot be negative", name);
			final BigDecimal max = BigDecimal.valueOf(maxUnits, DECIMALS);

			Validate.isTrue(amount.compareTo(max) <= 0, "%s is at most %s coins", name,
				max.stripTrailingZeros().toPlainString());
			Validate.isTrue(amount.stripTrailingZeros().scale() <= DECIMALS, "%s has at most %d decimals", name,
				DECIMALS);
		}

		public long units() {
			validate();
			return unitsOf(minimumTransferFee);
		}

		public long newAccountUnits() {
			validate();
			return unitsOf(minimumNewAccountAmount);
		}

		private static long unitsOf(BigDecimal amount) {
			return amount.setScale(DECIMALS, RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
		}
	}
}
