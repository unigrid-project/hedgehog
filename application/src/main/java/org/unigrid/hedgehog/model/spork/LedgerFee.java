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

		private BigDecimal minimumTransferFee = BigDecimal.ZERO;

		@Override
		public SporkData empty() {
			return new SporkData();
		}

		/* Cheapest checks first: a fee with an absurd exponent is refused before anything is rescaled */
		public void validate() {
			Validate.isTrue(Objects.nonNull(minimumTransferFee), "minimumTransferFee is required");
			Validate.isTrue(minimumTransferFee.signum() >= 0, "minimumTransferFee cannot be negative");
			Validate.isTrue(minimumTransferFee.compareTo(BigDecimal.valueOf(MAX_UNITS, DECIMALS)) <= 0,
				"minimumTransferFee is at most 1 coin");

			Validate.isTrue(minimumTransferFee.stripTrailingZeros().scale() <= DECIMALS,
				"minimumTransferFee has at most %d decimals", DECIMALS);
		}

		public long units() {
			validate();
			return minimumTransferFee.setScale(DECIMALS, RoundingMode.UNNECESSARY).unscaledValue()
				.longValueExact();
		}
	}
}
