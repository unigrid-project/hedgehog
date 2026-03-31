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
import java.util.HashMap;
import java.util.Map;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;

/**
 * GridSpork för individuell lagring av mintade tokens
 */
public class MintStorage extends GridSpork implements Serializable {

    public MintStorage() {
        setType(Type.MINT_STORAGE);

        SporkData data = new SporkData();
        data.setMints(new HashMap<>());
        setData(data);
    }

    // =============================
    // SporkData
    // =============================
    public static class SporkData implements ChunkData, Serializable {
        private Map<Location, BigDecimal> mints;

        public Map<Location, BigDecimal> getMints() {
            return mints;
        }

        public void setMints(Map<Location, BigDecimal> mints) {
            this.mints = mints;
        }

        public SporkData empty() {
            SporkData data = new SporkData();
            data.setMints(new HashMap<>());
            return data;
        }

        // =============================
        // Location inuti SporkData
        // =============================
        public static class Location implements Serializable {
            private Address address;
            private int height;

            public Address getAddress() {
                return address;
            }

            public void setAddress(Address address) {
                this.address = address;
            }

            public int getHeight() {
                return height;
            }

            public void setHeight(int height) {
                this.height = height;
            }
        }
    }

    // =============================
    // Typ-säker getData / setData
    // =============================
    @Override
    public SporkData getData() {
        return (SporkData) super.getData();
    }

    public void setData(SporkData data) {
        super.setData(data);
    }

    // =============================
    // Enkel Address-klass
    // =============================
    public static class Address implements Serializable {
        private String wif;

        public Address() {}

        public Address(String wif) {
            this.wif = wif;
        }

        public String getWif() {
            return wif;
        }

        public void setWif(String wif) {
            this.wif = wif;
        }
    }
}