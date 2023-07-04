/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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
import java.time.Instant;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.model.util.ExceptionUtil;

@Data
@NoArgsConstructor
public class SporkDatabaseInfo implements Serializable {
	public static final String LASTCHANGED_NEVER = "never";

	private Overview<Integer, String> mintStorageEntries = new Overview(0, LASTCHANGED_NEVER);
	private Overview<BigDecimal, String> mintSupply = new Overview(BigDecimal.ZERO, LASTCHANGED_NEVER);
	private Overview<Integer, String> vestingStoragEntries = new Overview(0, LASTCHANGED_NEVER);
	private Overview<Integer, String> cosmosEntries = new Overview(0, LASTCHANGED_NEVER);

	public SporkDatabaseInfo(SporkDatabase sporkDatabase) {
		ExceptionUtil.swallow(() -> {
			if (Objects.nonNull(sporkDatabase.getMintStorage())) {
				final MintStorage.SporkData data = sporkDatabase.getMintStorage().getData();
				final int amount = data.getMints().size();
				final Instant lastChanged = sporkDatabase.getMintStorage().getTimeStamp();

				mintStorageEntries.amount = amount;
				mintStorageEntries.lastChanged = lastChanged.toString();
			}
		}, NullPointerException.class);

		ExceptionUtil.swallow(() -> {
			if (Objects.nonNull(sporkDatabase.getMintSupply())) {
				final MintSupply.SporkData data = sporkDatabase.getMintSupply().getData();
				final Instant lastChanged = sporkDatabase.getMintSupply().getTimeStamp();

				Objects.requireNonNull(data.getMaxSupply());
				mintSupply.amount = data.getMaxSupply();
				mintSupply.lastChanged = lastChanged.toString();
			}
		}, NullPointerException.class);

		ExceptionUtil.swallow(() -> {
			if (Objects.nonNull(sporkDatabase.getVestingStorage())) {
				final VestingStorage.SporkData data = sporkDatabase.getVestingStorage().getData();
				final int amount = data.getVestingAddresses().size();
				final Instant lastChanged = sporkDatabase.getVestingStorage().getTimeStamp();

				vestingStoragEntries.amount = amount;
				vestingStoragEntries.lastChanged = lastChanged.toString();
			}
		}, NullPointerException.class);

		ExceptionUtil.swallow(() -> {
			if (Objects.nonNull(sporkDatabase.getCosmos())) {
				final Cosmos.SporkData data = sporkDatabase.getCosmos().getData();
				final Instant lastChanged = sporkDatabase.getCosmos().getTimeStamp();
				final int amount = data.getParameters().size();

				cosmosEntries.amount = amount;
				cosmosEntries.lastChanged = lastChanged.toString();
			}
		}, NullPointerException.class);
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Overview<T, D> {
		private T amount;
		private D lastChanged;
	}
}
