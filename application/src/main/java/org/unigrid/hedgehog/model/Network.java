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

import java.util.Arrays;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.command.option.LedgerOptions;
import org.unigrid.hedgehog.command.option.NetOptions;

@Slf4j
public class Network {
	/* The ledger protocol is offered first: a node that knows it settles on it with another that does, and a node that
	   does not know it settles on the next one, which is what a node of an earlier release offers first. Whether a
	   connection settled on it is what tells a node that the other end speaks the ledger. */
	public static final String LEDGER_PROTOCOL = "hedgehog-ledger/0.0.1";

	/* Offered by every node of this release ahead of what earlier releases offer, so that a connection that settled on
	   it, or on the ledger protocol, tells a node that the other end reads every spork type of this release */
	public static final String SPORK_PROTOCOL = "hedgehog/0.0.9";

	/* With the board keys built into the release, the root of the chain of changes to them (BoardKeyChain). A release
	   that counts it up starts from its own keys again, and every change made before it is void. */
	public static final long BOARD_KEYS_EPOCH = 1;

	/* A chain of board keys that the release vouches for: its length, and the digest in hex of what its last change
	   decides (BoardKeyChain.digestOf). A quorum of any key set the board ever had can sign a fork that a node which
	   starts fresh takes, so a release made after a change of the board keys sets this, or counts the epoch up, and a
	   node of it takes no chain that does not hold the checkpoint. */
	public record Checkpoint(int length, String digest) {
	}

	public static final Optional<Checkpoint> BOARD_KEYS_CHECKPOINT = Optional.empty();

	private static final String[] EARLIER_PROTOCOLS = {
		"hedgehog/0.0.4",
		"gridspork/0.0.4"
	};

	private static final String[] PROTOCOLS = {
		SPORK_PROTOCOL,
		EARLIER_PROTOCOLS[0],
		EARLIER_PROTOCOLS[1]
	};

	private static final String[] LEDGER_PROTOCOLS = {
		LEDGER_PROTOCOL,
		SPORK_PROTOCOL,
		EARLIER_PROTOCOLS[0],
		EARLIER_PROTOCOLS[1]
	};

	private static final String[] SEEDS = {
		"seed1.unigrid.org", "seed2.unigrid.org",
		"seed3.unigrid.org", "seed4.unigrid.org",
		"seed5.unigrid.org", "seed6.unigrid.org"
	};

	public static final int COMMUNICATION_THREADS = 4;
	public static final int MAX_DATA_SIZE = 1024 * 1024 * 256; /* 256 MB */

	/* What a peer may have in flight to this node before it waits for it to read, on a connection and on a stream.
	   A frame larger than this still arrives, as the node hands out more as it reads. */
	public static final int MAX_WINDOW_SIZE = 1024 * 1024 * 16; /* 16 MB */

	/* A node opens one stream on each connection, so a peer that opens more has no use for them */
	public static final int MAX_STREAMS = 16;
	public static final int IDLE_TIME_MINUTES = 15;
	public static final int CONNECTION_TIMEOUT_MS = 2000;

	/* Only a node that runs a ledger offers its protocol: one that did not opt in speaks no ledger */
	public static String[] getProtocols() {
		return hasLedger() ? LEDGER_PROTOCOLS : PROTOCOLS;
	}

	public static boolean hasLedger() {
		return LedgerOptions.isLedger();
	}

	/* A node carries the ledger pipeline exactly when it offers the ledger protocol, so it never sends a ledger packet
	   over a connection that did not settle on it */
	public static boolean hasLedger(final String[] protocols) {
		return Arrays.asList(protocols).contains(LEDGER_PROTOCOL);
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
