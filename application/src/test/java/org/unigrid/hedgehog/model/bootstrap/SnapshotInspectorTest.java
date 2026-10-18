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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import lombok.SneakyThrows;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

public class SnapshotInspectorTest {
	private static final int MAX_ADDRESSES = 64;
	private static final long MAX_BALANCE = 21_000_000L * 100_000_000L;

	@SneakyThrows
	@Property
	public void shouldRefuseAFileShorterThanAHeader(@ForAll @Size(max = SnapshotFormat.HEADER_SIZE - 1) byte[] contents) {
		final Path snapshot = SyntheticSnapshots.inMemory(contents);

		SyntheticSnapshots.assertRefused(() -> SnapshotInspector.summarise(snapshot), "too short to hold a snapshot header");
		SyntheticSnapshots.assertRefused(() -> SnapshotInspector.validate(snapshot), "too short to hold a snapshot header");
	}

	@SneakyThrows
	@Property
	public void shouldRefuseAnotherMagic(@ForAll @Size(8) byte[] magic) {
		Assume.that(!Arrays.equals(magic, SnapshotFormat.MAGIC));

		final Path snapshot = SyntheticSnapshots.inMemory(SyntheticSnapshots.builder().magic(magic).build());

		SyntheticSnapshots.assertRefused(() -> SnapshotInspector.summarise(snapshot), "is not a legacy chain snapshot");
		SyntheticSnapshots.assertRefused(() -> SnapshotInspector.validate(snapshot), "is not a legacy chain snapshot");
	}

	@SneakyThrows
	@Property
	public void shouldRefuseAnotherFormatVersion(@ForAll int version) {
		Assume.that(version != SnapshotFormat.VERSION);

		final Path snapshot = SyntheticSnapshots.inMemory(SyntheticSnapshots.builder().version(version).build());

		SyntheticSnapshots.assertRefused(() -> SnapshotInspector.summarise(snapshot), "format version " + version);
	}

	@SneakyThrows
	@Property
	public void shouldSummariseTheHeader(@ForAll @IntRange(min = 0) int tipHeight, @ForAll @LongRange(min = 0) long entries,
		@ForAll @LongRange(min = 0) long transactions, @ForAll @LongRange(min = 0, max = MAX_BALANCE) long unspent,
		@ForAll @LongRange(min = 0, max = MAX_BALANCE) long minted) {

		final SnapshotInfo info = SnapshotInspector.summarise(SyntheticSnapshots.inMemory(SyntheticSnapshots.builder()
			.tipHeight(tipHeight).entryCount(entries).transactionCount(transactions).totalUnspent(unspent)
			.zerocoinMinted(minted).build())
		);

		assertThat(info.getTipHeight(), equalTo(tipHeight));
		assertThat(info.getEntryCount(), equalTo(entries));
		assertThat(info.getTransactionCount(), equalTo(transactions));
		assertThat(info.getTotalUnspent(), equalTo(Coin.toDecimal(unspent)));
		assertThat(info.getZerocoinMinted(), equalTo(Coin.toDecimal(minted)));
	}

	@SneakyThrows
	@Property
	public void shouldReportAnUnsignedSnapshot(@ForAll @Size(max = MAX_ADDRESSES)
		List<@LongRange(min = 0, max = MAX_BALANCE) Long> balances) {

		final byte[] contents = SyntheticSnapshots.builder().balances(balances).build();
		final byte[] header = SyntheticSnapshots.builder().balances(balances).transactionTableOffset(contents.length)
			.build();

		assertThat(SnapshotInspector.validate(SyntheticSnapshots.inMemory(header)), equalTo(SignatureStatus.UNSIGNED));
	}

	@SneakyThrows
	@Property
	public void shouldSumTheAddressTable(@ForAll @Size(max = MAX_ADDRESSES)
		List<@LongRange(min = 0, max = MAX_BALANCE) Long> balances) {

		final Path snapshot = SyntheticSnapshots.inMemory(SyntheticSnapshots.builder().balances(balances).build());

		assertThat(SnapshotInspector.totalBalance(snapshot),
			equalTo(balances.stream().mapToLong(Long::longValue).sum())
		);
	}

	@SneakyThrows
	@Property
	public void shouldRefuseATruncatedAddressTable(@ForAll @Size(min = 1, max = MAX_ADDRESSES)
		List<@LongRange(min = 0, max = MAX_BALANCE) Long> balances, @ForAll @IntRange(min = 1) int cut) {

		final byte[] contents = SyntheticSnapshots.builder().balances(balances).build();
		final int table = contents.length - SnapshotFormat.HEADER_SIZE;
		final Path snapshot = SyntheticSnapshots.inMemory(Arrays.copyOf(contents,
			contents.length - 1 - (cut - 1) % table)
		);

		SyntheticSnapshots.assertRefused(() -> SnapshotInspector.totalBalance(snapshot), "ends inside its address table");
	}
}
