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

package org.unigrid.hedgehog.model.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import java.util.List;
import lombok.SneakyThrows;
import net.jqwik.api.Example;

public class LedgerReplayTest {
	private static final byte[] ADDRESS_A = addressHash((byte) 0x01);
	private static final byte[] ADDRESS_B = addressHash((byte) 0x02);
	private static final long MINED_FIRST = 5_000_000_000L;
	private static final long MINED_SECOND = 3_000_000_000L;

	@Example
	@SneakyThrows
	public void shouldDebitTheSpentOutputAndCreditTheRecipient() {
		final byte[] firstCoinbase = SyntheticBlocks.coinbaseTransaction(ADDRESS_A, MINED_FIRST);
		final byte[] secondCoinbase = SyntheticBlocks.coinbaseTransaction(ADDRESS_A, MINED_SECOND);
		final byte[] spend = SyntheticBlocks.spendTransaction(
			SyntheticBlocks.transactionId(firstCoinbase), 0, ADDRESS_B, MINED_FIRST);

		final List<List<byte[]>> perHeight = List.of(List.of(), List.of(firstCoinbase),
			List.of(secondCoinbase), List.of(spend));
		final BlockFileStore store = SyntheticBlocks.store(builder -> builder.mainChain(3, perHeight::get));
		final Ledger ledger = LedgerReplay.replay(store, ChainLinker.link(store));

		final int addressA = ledger.getAddresses().idOf(ADDRESS_A);
		final int addressB = ledger.getAddresses().idOf(ADDRESS_B);

		assertThat(ledger.getBalances()[addressA], equalTo(MINED_SECOND));
		assertThat(ledger.getBalances()[addressB], equalTo(MINED_FIRST));
		assertThat(sentEntry(ledger, addressA), equalTo(-MINED_FIRST));
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAnInputThatSpendsAnOutpointThatWasNeverCreated() {
		final byte[] neverCreated = new byte[Hashing.HASH_SIZE];

		neverCreated[0] = 0x7f;

		final byte[] spend = SyntheticBlocks.spendTransaction(neverCreated, 0, ADDRESS_B, MINED_FIRST);
		final List<List<byte[]>> perHeight = List.of(List.of(), List.of(spend));
		final BlockFileStore store = SyntheticBlocks.store(builder -> builder.mainChain(1, perHeight::get));
		final Chain chain = ChainLinker.link(store);

		try {
			LedgerReplay.replay(store, chain);
			throw new AssertionError("A spend of an unknown output was accepted");

		} catch (IllegalStateException expected) {
			assertThat(expected.getMessage(), equalTo("Input at height 1 spends an unknown output "
				+ BlockParser.toDisplayString(neverCreated) + ":0"));
		}
	}

	private static long sentEntry(Ledger ledger, int address) {
		final LedgerEntries entries = ledger.getEntries();

		for (int entry = 0; entry < entries.getSize(); entry++) {
			if (entries.addressAt(entry) == address && EntryKind.of(entries.kindAt(entry)) == EntryKind.SENT) {
				return entries.amountAt(entry);
			}
		}

		throw new AssertionError("No SENT entry found for address " + address);
	}

	private static byte[] addressHash(byte marker) {
		final byte[] hash = new byte[Hashing.ADDRESS_HASH_SIZE];

		hash[0] = marker;
		return hash;
	}
}
