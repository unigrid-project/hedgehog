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

public final class Ping extends Packet implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final AttributeKey<Long> PING_TIME_KEY =
            AttributeKey.valueOf("PING_TIME");

    private boolean response;
    private long nanoTime;

    public Ping() {
        this(false, System.nanoTime());
        setType(Type.PING);
    }

    public Ping(boolean response, long nanoTime) {
        this.response = response;
        this.nanoTime = nanoTime;
        setType(Type.PING);
    }

    public boolean isResponse() {
        return response;
    }

    public void setResponse(boolean response) {
        this.response = response;
    }

    public long getNanoTime() {
        return nanoTime;
    }

    public void setNanoTime(long nanoTime) {
        this.nanoTime = nanoTime;
    }
}