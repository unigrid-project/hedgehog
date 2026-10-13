/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model;

import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.command.option.NetOptions;

@Slf4j
public class Network {
	/* Not 0.0.3 or 0.0.4, which name the spork formats with signature logs and cosignatures that this one lacks */
	public static final String PROXY_PROTOCOL = "hedgehog/0.0.5";

	/* The server takes the first of its protocols that the client offers too, so the newest one leads: two nodes of
	   this release agree on it, and a node of the previous release, which offers only the ones after it, still
	   connects, see PeerProtocol */
	private static final String[] PROTOCOLS = {
		PROXY_PROTOCOL,
		"hedgehog/0.0.2",
		"gridspork/0.0.2"
	};

	private static final String[] SEEDS = {
		"seed1.unigrid.org", "seed2.unigrid.org",
		"seed3.unigrid.org", "seed4.unigrid.org",
		"seed5.unigrid.org", "seed6.unigrid.org"
	};

	public static final int COMMUNICATION_THREADS = 4;
	public static final int MAX_DATA_SIZE = 1024 * 1024 * 256; /* 256 MB */

	/* The second stream a client opens, after the main one: client-initiated bidirectional streams are 0, 4, 8, ... */
	public static final long CHAIN_STREAM_ID = 4;

	public static final int MAX_STREAMS = 512;
	public static final int IDLE_TIME_MINUTES = 15;
	public static final int CONNECTION_TIMEOUT_MS = 2000;

	public static String[] getProtocols() {
		return PROTOCOLS;
	}

	public static String[] getSeeds() {
		try {
			if (NetOptions.isSeeds()) {
				return SEEDS;
			}
		} catch (ClassCastException ex) {
			// TODO: Some weird exception that only seems to happen during testing?
			log.atError().log("Failed to get seeds {}", ex.getMessage());
		}

		return new String[0];
	}
}
