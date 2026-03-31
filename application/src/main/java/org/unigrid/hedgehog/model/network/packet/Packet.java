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
 package org.unigrid.hedgehog.model.network.packet;

import io.netty.util.AttributeKey;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Packet implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final AttributeKey<Packet> KEY =
            AttributeKey.valueOf("hedgehog.packet");

    private Type type = Type.UNDEFINED;

    public Packet() {
    }

    public Packet(Type type) {
        this.type = Objects.requireNonNull(type, "Packet type cannot be null");
    }

    public Type getType() {
        return type;
    }

    protected void setType(Type type) {
        this.type = Objects.requireNonNull(type, "Packet type cannot be null");
    }

    public boolean is(Type type) {
        return this.type == type;
    }

    @Override
    public String toString() {
        return "Packet{type=" + type + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(type);
    }

    @Override
    public boolean equals(Object obj) {

        if (this == obj)
            return true;

        if (obj == null || getClass() != obj.getClass())
            return false;

        Packet other = (Packet) obj;

        return type == other.type;
    }

    public enum Type {

        UNDEFINED((short) 0),
        HELLO((short) 250),
        PING((short) 500),
        ASK_PEERS((short) 1000),
        PUBLISH_PEERS((short) 1010),
        ASK_NODE_DETAILS((short) 1100),
        PUBLISH_NODE_DETAILS((short) 1110),
        ASK_SPORKS((short) 2000),
        GROW_SPORK((short) 2010),
        PUBLISH_SPORK((short) 2020);

        private final short value;

        private static final Map<Short, Type> LOOKUP = new HashMap<>();

        static {
            for (Type t : values()) {
                LOOKUP.put(t.value, t);
            }
        }

        Type(short value) {
            this.value = value;
        }

        public short getValue() {
            return value;
        }

        public static Type fromValue(short value) {
            Type type = LOOKUP.get(value);
            return type != null ? type : UNDEFINED;
        }
    }
}