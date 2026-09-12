/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.bootstrap;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/*
   Walks the active chain from the genesis block forward, crediting every output that carries an address
   and debiting the output each input consumes. What is left in the unspent map at the tip is the balance
   of the legacy chain, and every credit and debit is kept as a dated ledger entry.
*/
@Slf4j
public final class LedgerReplay {
	private static final int ESTIMATED_ENTRIES_PER_BLOCK = 6;
	private static final int ESTIMATED_UNSPENT_OUTPUTS = 4 << 20;
	private static final int PROGRESS_INTERVAL = 250_000;

	private final BlockFileStore store;
	private final Chain chain;
	private final AddressRegistry addresses = new AddressRegistry();
	private final TransactionIdTable transactionIds = new TransactionIdTable();
	private final Map<Outpoint, UnspentOutput> unspent = new HashMap<>(ESTIMATED_UNSPENT_OUTPUTS);
	private final LedgerEntries entries;
	private long zerocoinMinted;

	private LedgerReplay(BlockFileStore store, Chain chain) {
		this.store = store;
		this.chain = chain;
		this.entries = new LedgerEntries(chain.getBlockCount() * ESTIMATED_ENTRIES_PER_BLOCK);
	}

	public static Ledger replay(BlockFileStore store, Chain chain) {
		final LedgerReplay replay = new LedgerReplay(store, chain);

		replay.walk();
		return replay.toLedger();
	}

	private void walk() {
		for (int height = 0; height <= chain.getTipHeight(); height++) {
			final ByteBuffer block = store.read(chain.locationAt(height));

			BlockParser.header(block);

			for (final LegacyTransaction transaction : BlockParser.transactions(block)) {
				apply(transaction, height);
			}

			if (height % PROGRESS_INTERVAL == 0) {
				log.info("Replayed {} of {} blocks, {} unspent outputs held",
					height, chain.getBlockCount(), unspent.size());
			}
		}
	}

	private void apply(LegacyTransaction transaction, int height) {
		final int identifier = transactionIds.add(transaction.getId());

		spend(transaction, height, identifier);
		create(transaction, height, identifier);
	}

	private void spend(LegacyTransaction transaction, int height, int identifier) {
		for (final TransactionInput input : transaction.getInputs()) {
			if (input.getType() != InputType.STANDARD) {
				continue;
			}

			final Outpoint outpoint = new Outpoint(input.getPreviousTransaction(), input.getPreviousIndex());
			final UnspentOutput spent = unspent.remove(outpoint);

			if (spent == null) {
				throw new IllegalStateException("Input at height " + height + " spends an unknown output "
					+ BlockParser.toDisplayString(input.getPreviousTransaction())
					+ ":" + input.getPreviousIndex());
			}

			entries.add(spent.getAddress(), -spent.getValue(), height, identifier, EntryKind.SENT);
		}
	}

	private void create(LegacyTransaction transaction, int height, int identifier) {
		final List<TransactionOutput> outputs = transaction.getOutputs();
		final EntryKind kind = kindOf(transaction);

		for (int index = 0; index < outputs.size(); index++) {
			final TransactionOutput output = outputs.get(index);

			if (output.getType() == OutputType.ZEROCOIN_MINT) {
				zerocoinMinted += output.getValue();
			} else if (output.isSpendable()) {
				final int address = addresses.idOf(output.getAddressHash());

				unspent.put(new Outpoint(transaction.getId(), index),
					new UnspentOutput(address, output.getValue()));
				entries.add(address, output.getValue(), height, identifier, kind);
			}
		}
	}

	private static EntryKind kindOf(LegacyTransaction transaction) {
		if (transaction.isCoinBase()) {
			return EntryKind.MINED;
		}

		return transaction.isCoinStake() ? EntryKind.STAKED : EntryKind.RECEIVED;
	}

	private Ledger toLedger() {
		final long[] balances = new long[addresses.size()];
		long total = 0;

		for (final UnspentOutput output : unspent.values()) {
			balances[output.getAddress()] += output.getValue();
			total += output.getValue();
		}

		log.info("Replay complete: {} addresses, {} ledger entries, {} unspent outputs",
			addresses.size(), entries.getSize(), unspent.size());

		return Ledger.builder().addresses(addresses).entries(entries).transactionIds(transactionIds)
			.balances(balances).totalUnspent(total).zerocoinMinted(zerocoinMinted)
			.unspentOutputCount(unspent.size()).build();
	}
}
