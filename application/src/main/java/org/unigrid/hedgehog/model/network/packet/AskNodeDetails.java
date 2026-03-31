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

import java.io.Serializable;

public class AskNodeDetails extends Packet implements Serializable {

    private boolean protocol = true;
    private boolean version = true;

    public AskNodeDetails() {
        super(Type.ASK_NODE_DETAILS);
    }

    public boolean isProtocol() {
        return protocol;
    }

    public void setProtocol(boolean protocol) {
        this.protocol = protocol;
    }

    public boolean isVersion() {
        return version;
    }

    public void setVersion(boolean version) {
        this.version = version;
    }

    public byte toFlags() {
        int flags = 0;
        if (protocol) flags |= Flags.PROTOCOL.mask;
        if (version) flags |= Flags.VERSION.mask;
        return (byte) flags;
    }

    public void fromFlags(byte flags) {
        this.protocol = Flags.PROTOCOL.isSet(flags);
        this.version = Flags.VERSION.isSet(flags);
    }

    public enum Flags {
        PROTOCOL(0x01),
        VERSION(0x02);

        private final int mask;

        Flags(int mask) {
            this.mask = mask;
        }

        public int getMask() {
            return mask;
        }

        public boolean isSet(int flags) {
            return (flags & mask) == mask;
        }
    }
}