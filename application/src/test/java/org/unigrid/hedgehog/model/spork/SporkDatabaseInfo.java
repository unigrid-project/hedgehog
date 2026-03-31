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

import java.util.Objects;

public class SporkDatabaseInfo {

    public static final String LASTCHANGED_NEVER = "Never";

    private Overview mintStorageEntries = new Overview();
    private Overview mintSupply = new Overview();
    private Overview vestingStoragEntries = new Overview();

    public SporkDatabaseInfo() {
        mintStorageEntries.setLastChanged(LASTCHANGED_NEVER);
        mintSupply.setLastChanged(LASTCHANGED_NEVER);
        vestingStoragEntries.setLastChanged(LASTCHANGED_NEVER);
    }

    public SporkDatabaseInfo(SporkDatabase database) {

        this();

        if (Objects.nonNull(database)) {

            if (database.getMintStorage() != null) {

                GridSpork spork = database.getMintStorage();

                mintStorageEntries.setLastChanged(
                        spork.getTimeStamp() != null
                                ? spork.getTimeStamp().toString()
                                : LASTCHANGED_NEVER
                );

                if (spork.getData() instanceof MintStorage.SporkData data) {
                    mintStorageEntries.setAmount(data.getMints().size());
                }
            }

            if (database.getMintSupply() != null) {

                GridSpork spork = database.getMintSupply();

                mintSupply.setLastChanged(
                        spork.getTimeStamp() != null
                                ? spork.getTimeStamp().toString()
                                : LASTCHANGED_NEVER
                );

                if (spork.getData() instanceof MintSupply.SporkData data) {
                    mintSupply.setAmount(data.getMaxSupply());
                }
            }

            if (database.getVestingStorage() != null) {

                GridSpork spork = database.getVestingStorage();

                vestingStoragEntries.setLastChanged(
                        spork.getTimeStamp() != null
                                ? spork.getTimeStamp().toString()
                                : LASTCHANGED_NEVER
                );

                if (spork.getData() instanceof VestingStorage.SporkData data) {
                    vestingStoragEntries.setAmount(data.getVestingAddresses().size());
                }
            }
        }
    }

    public Overview getMintStorageEntries() {
        return mintStorageEntries;
    }

    public Overview getMintSupply() {
        return mintSupply;
    }

    public Overview getVestingStoragEntries() {
        return vestingStoragEntries;
    }

    public static class Overview {

        private String lastChanged = LASTCHANGED_NEVER;
        private Object amount;

        public String getLastChanged() {
            return lastChanged;
        }

        public void setLastChanged(String lastChanged) {
            this.lastChanged = lastChanged;
        }

        public Object getAmount() {
            return amount;
        }

        public void setAmount(Object amount) {
            this.amount = amount;
        }
    }
}