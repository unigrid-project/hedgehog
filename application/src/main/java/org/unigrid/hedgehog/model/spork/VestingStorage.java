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

import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;

public class VestingStorage extends GridSpork implements Serializable {

    public VestingStorage() {
        setType(Type.VESTING_STORAGE);

        SporkData data = new SporkData();
        data.setVestingAddresses(new HashMap<>());
        setData(data);
    }

    public static class SporkData implements ChunkData {

        private Map<Address, Vesting> vestingAddresses = new HashMap<>();

        public Map<Address, Vesting> getVestingAddresses() {
            return vestingAddresses;
        }

        public void setVestingAddresses(Map<Address, Vesting> vestingAddresses) {
            this.vestingAddresses = vestingAddresses;
        }

        public SporkData empty() {
            SporkData data = new SporkData();
            data.setVestingAddresses(new HashMap<>());
            return data;
        }

        public static class Vesting implements Serializable {

            private BigDecimal amount;

            @JsonFormat(shape = JsonFormat.Shape.STRING)
            private Instant start;

            @JsonFormat(shape = JsonFormat.Shape.STRING)
            private Duration duration;

            private int parts;

            public Vesting() {}

            public Vesting(BigDecimal amount, Instant start,
                           Duration duration, int parts) {
                this.amount = amount;
                this.start = start;
                this.duration = duration;
                this.parts = parts;
            }

            public BigDecimal getAmount() { return amount; }
            public void setAmount(BigDecimal amount) { this.amount = amount; }

            public Instant getStart() { return start; }
            public void setStart(Instant start) { this.start = start; }

            public Duration getDuration() { return duration; }
            public void setDuration(Duration duration) { this.duration = duration; }

            public int getParts() { return parts; }
            public void setParts(int parts) { this.parts = parts; }
        }
    }
}
