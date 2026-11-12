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

package org.unigrid.hedgehog.model.network;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Random;
import org.unigrid.hedgehog.model.network.codec.ChainArbitraries;
import org.unigrid.hedgehog.model.network.codec.ChainEntryEncoder;
import org.unigrid.hedgehog.model.network.codec.ChainHeadsEncoder;
import org.unigrid.hedgehog.model.network.codec.ChainStemEncoder;
import org.unigrid.hedgehog.model.network.codec.EntryRefusedEncoder;
import org.unigrid.hedgehog.model.network.codec.FetchEntriesEncoder;
import org.unigrid.hedgehog.model.network.codec.JoinChainEncoder;
import org.unigrid.hedgehog.model.network.codec.JoinReplyEncoder;
import org.unigrid.hedgehog.model.network.codec.LeaveChainEncoder;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.ChainStem;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockBatch;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockRequest;
import org.unigrid.hedgehog.model.network.packet.LedgerConsensus;
import org.unigrid.hedgehog.model.network.packet.LedgerHeartbeat;
import org.unigrid.hedgehog.model.network.packet.LedgerStatus;
import org.unigrid.hedgehog.model.network.packet.LedgerTransaction;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.service.ledger.LedgerWireFixtures;
import static org.unigrid.hedgehog.model.network.PreviewFrames.frameOf;

/* The frames that a node run with --preview sends for the ledger and for chain traffic, one of each type, to go with
   those of PreviewFrames for the gridnode and the storage */
public final class LedgerAndChainFrames {
	private static final long SEED = 7;
	private static final long REQUEST_ID = 42;
	private static final long SEQ = 3;
	private static final int ENTRIES = 2;

	private LedgerAndChainFrames() {
		/* Static helpers only */
	}

	public static List<ByteBuf> ofTheLedger() {
		final Random random = new Random(SEED);

		return List.of(
			LedgerWireFixtures.encoded(LedgerTransaction.builder()
				.transaction(LedgerWireFixtures.transaction(random)).build()),
			LedgerWireFixtures.encoded(LedgerConsensus.builder().message(LedgerWireFixtures.message(random)).build()),
			LedgerWireFixtures.encoded(LedgerHeartbeat.builder()
				.heartbeat(LedgerWireFixtures.heartbeat(random)).build()),
			LedgerWireFixtures.encoded(LedgerStatus.builder().status(LedgerWireFixtures.status(random)).build()),
			LedgerWireFixtures.encoded(LedgerBlockRequest.builder().requestId(REQUEST_ID)
				.request(LedgerWireFixtures.request(random)).build()),
			LedgerWireFixtures.encoded(LedgerBlockBatch.builder().requestId(REQUEST_ID)
				.batch(LedgerWireFixtures.emptyBatch()).build()));
	}

	public static List<ByteBuf> ofTheChain() {
		final ChainId chainId = ChainArbitraries.chainIds().sample();
		final WriterId writer = ChainArbitraries.writers().sample();
		final List<Entry> entries = ChainArbitraries.entries().list().ofSize(ENTRIES).sample();

		return List.of(
			frameOf(new JoinChainEncoder(), JoinChain.builder().requestId(REQUEST_ID).writer(writer)
				.chainId(chainId).build()),
			frameOf(new JoinReplyEncoder(), JoinReply.builder().requestId(REQUEST_ID)
				.status(JoinReply.Status.JOINED).chainId(chainId).heads(ChainArbitraries.mostHeads()).build()),
			frameOf(new ChainEntryEncoder(), ChainEntry.builder().entries(entries).build()),
			frameOf(new ChainStemEncoder(), ChainStem.builder().entries(entries).build()),
			frameOf(new ChainHeadsEncoder(), ChainHeads.builder().chainId(chainId)
				.heads(ChainArbitraries.mostHeads()).build()),
			frameOf(new FetchEntriesEncoder(), FetchEntries.builder().chainId(chainId)
				.ranges(List.of(new FetchEntries.Range(writer, 0, SEQ))).build()),
			frameOf(new EntryRefusedEncoder(), EntryRefused.builder().chainId(chainId).writer(writer).seq(SEQ)
				.admission(Admission.FULL).build()),
			frameOf(new LeaveChainEncoder(), LeaveChain.builder().chainId(chainId).writer(writer).build()));
	}
}
