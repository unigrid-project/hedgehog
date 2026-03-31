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

public class SporkDatabaseInfo implements Serializable {

    public static final String LASTCHANGED_NEVER = "never";

    private Overview<Integer, String> mintStorageEntries =
            new Overview<>(0, LASTCHANGED_NEVER);

    private Overview<BigDecimal, String> mintSupply =
            new Overview<>(BigDecimal.ZERO, LASTCHANGED_NEVER);

    private Overview<Integer, String> vestingStorageEntries =
            new Overview<>(0, LASTCHANGED_NEVER);

    public SporkDatabaseInfo() {}

    public SporkDatabaseInfo(SporkDatabase sporkDatabase) {

        if (sporkDatabase.getMintStorage() != null) {

            MintStorage.SporkData data =
                    (MintStorage.SporkData)
                            sporkDatabase.getMintStorage().getData();

            int amount = data.getMints().size();
            Instant lastChanged =
                    sporkDatabase.getMintStorage().getTimeStamp();

            mintStorageEntries =
                    new Overview<>(amount, lastChanged.toString());
        }

        if (sporkDatabase.getMintSupply() != null) {

            MintSupply.SporkData data =
                    (MintSupply.SporkData)
                            sporkDatabase.getMintSupply().getData();

            BigDecimal supply = data.getMaxSupply();
            Instant lastChanged =
                    sporkDatabase.getMintSupply().getTimeStamp();

            mintSupply =
                    new Overview<>(supply, lastChanged.toString());
        }

        if (sporkDatabase.getVestingStorage() != null) {

            VestingStorage.SporkData data =
                    (VestingStorage.SporkData)
                            sporkDatabase.getVestingStorage().getData();

            int amount =
                    data.getVestingAddresses().size();

            Instant lastChanged =
                    sporkDatabase.getVestingStorage().getTimeStamp();

            vestingStorageEntries =
                    new Overview<>(amount, lastChanged.toString());
        }
    }

    public static class Overview<T, D> implements Serializable {

        private T amount;
        private D lastChanged;

        public Overview() {}

        public Overview(T amount, D lastChanged) {
            this.amount = amount;
            this.lastChanged = lastChanged;
        }

        public T getAmount() { return amount; }
        public D getLastChanged() { return lastChanged; }
    }
}