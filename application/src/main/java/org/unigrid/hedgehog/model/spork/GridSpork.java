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
import java.time.Instant;

public abstract class GridSpork implements Serializable {
    private Type type;
    private short flags;
    private Instant timeStamp;
    private Instant previousTimeStamp;
    private Object data;
    private Object previousData;
    private byte[] signature;

    public enum Type {
        MINT_STORAGE((short)1),
        MINT_SUPPLY((short)2),
        VESTING_STORAGE((short)3),
        STATISTICS_PUBKEY((short)4);

        private final short value;
        Type(short value) { this.value = value; }
        public short getValue() { return value; }

        // Konvertera short till Type
        public static Type get(short value) {
            for (Type t : values()) {
                if (t.value == value) return t;
            }
            throw new IllegalArgumentException("Unknown GridSpork.Type: " + value);
        }
    }

    // =======================
    // Getters / Setters
    // =======================
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public short getFlags() { return flags; }
    public void setFlags(short flags) { this.flags = flags; }
    public Instant getTimeStamp() { return timeStamp; }
    public void setTimeStamp(Instant timeStamp) { this.timeStamp = timeStamp; }
    public Instant getPreviousTimeStamp() { return previousTimeStamp; }
    public void setPreviousTimeStamp(Instant previousTimeStamp) { this.previousTimeStamp = previousTimeStamp; }
    public Object getData() { return data; }
    public void setData(Object data) { this.data = data; }
    public Object getPreviousData() { return previousData; }
    public void setPreviousData(Object previousData) { this.previousData = previousData; }
    public byte[] getSignature() { return signature; }
    public void setSignature(byte[] signature) { this.signature = signature; }

    // =======================
    // Hjälpmetoder
    // =======================
    public boolean isNewerThan(GridSpork other) {
        return other == null || (this.timeStamp != null && this.timeStamp.isAfter(other.timeStamp));
    }

    public boolean isValidSignature() {
        return signature != null && signature.length > 0;
    }

    // =======================
    // Factory-metod för GridSpork
    // =======================
    public static GridSpork create(Type type) {
        switch(type) {
            case MINT_STORAGE: return new MintStorage();
            case MINT_SUPPLY: return new MintSupply();
            case VESTING_STORAGE: return new VestingStorage();
            case STATISTICS_PUBKEY: return new StatisticsPubKey();
            default: throw new IllegalArgumentException("Unknown GridSpork type: " + type);
        }
    }
}