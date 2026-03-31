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
 // ======================================
// File: org/unigrid/hedgehog/model/Network.java
// ======================================

package org.unigrid.hedgehog.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.command.option.NetOptions;

/**
 * Central konfigurationsklass för nätverksrelaterade konstanter.
 */
public final class Network {

    private static final Logger log = LoggerFactory.getLogger(Network.class);

    private static final String[] PROTOCOLS = {
        "hedgehog/0.0.2",
        "gridspork/0.0.2"
    };

    private static final String[] SEEDS = {
        "seed1.unigrid.org",
        "seed2.unigrid.org",
        "seed3.unigrid.org",
        "seed4.unigrid.org",
        "seed5.unigrid.org",
        "seed6.unigrid.org"
    };

    public static final int COMMUNICATION_THREADS = 4;
    public static final int MAX_DATA_SIZE = 1024 * 1024 * 256; // 256 MB
    public static final int MAX_STREAMS = 512;
    public static final int IDLE_TIME_MINUTES = 15;
    public static final int CONNECTION_TIMEOUT_MS = 2000;

    private Network() { }

    public static String[] getProtocols() {
        return PROTOCOLS.clone();
    }

    /**
     * Returnerar DNS-seeds baserat på NetOptions.
     */
    public static String[] getSeeds() {
        try {
            if (NetOptions.isSeeds()) {
                return SEEDS.clone();
            }
        } catch (Exception ex) {
            log.error("Failed to resolve seeds. Continuing without seeds.", ex);
        }
        return new String[0];
    }
} 