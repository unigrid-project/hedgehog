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

import java.util.Arrays;
import lombok.Getter;

/*
   Tens of millions of ledger entries are produced in chain order and only grouped by address when the
   snapshot is written, so they live in parallel primitive arrays rather than in objects.
*/
public final class LedgerEntries {
	private long[] amounts;
	private int[] heights;
	private int[] transactions;
	private int[] addresses;
	private byte[] kinds;
	@Getter private int size;

	public LedgerEntries(int initialCapacity) {
		this.amounts = new long[initialCapacity];
		this.heights = new int[initialCapacity];
		this.transactions = new int[initialCapacity];
		this.addresses = new int[initialCapacity];
		this.kinds = new byte[initialCapacity];
	}

	public void add(int address, long amount, int height, int transaction, EntryKind kind) {
		if (size == amounts.length) {
			grow();
		}

		addresses[size] = address;
		amounts[size] = amount;
		heights[size] = height;
		transactions[size] = transaction;
		kinds[size] = (byte) kind.ordinal();
		size++;
	}

	public int addressAt(int entry) {
		return addresses[entry];
	}

	public long amountAt(int entry) {
		return amounts[entry];
	}

	public int heightAt(int entry) {
		return heights[entry];
	}

	public int transactionAt(int entry) {
		return transactions[entry];
	}

	public byte kindAt(int entry) {
		return kinds[entry];
	}

	private void grow() {
		final int capacity = amounts.length + (amounts.length >> 1);

		amounts = Arrays.copyOf(amounts, capacity);
		heights = Arrays.copyOf(heights, capacity);
		transactions = Arrays.copyOf(transactions, capacity);
		addresses = Arrays.copyOf(addresses, capacity);
		kinds = Arrays.copyOf(kinds, capacity);
	}
}
