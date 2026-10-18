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

package org.unigrid.hedgehog.command.bootstrap;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Builders;
import org.unigrid.hedgehog.model.bootstrap.AddressTransaction;
import org.unigrid.hedgehog.model.bootstrap.BuildReport;
import org.unigrid.hedgehog.model.bootstrap.EntryKind;
import org.unigrid.hedgehog.model.bootstrap.SignatureStatus;
import org.unigrid.hedgehog.model.bootstrap.SnapshotInfo;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BootstrapArbitraries {
	private static final int COIN_SCALE = 8;
	private static final int MAX_TRANSACTIONS = 20;
	private static final long LATEST_TIME = Instant.parse("2100-01-01T00:00:00Z").getEpochSecond();

	public static Arbitrary<String> addresses() {
		return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(40);
	}

	public static Arbitrary<BigDecimal> coins() {
		return Arbitraries.longs().greaterOrEqual(0).map(amount -> BigDecimal.valueOf(amount, COIN_SCALE));
	}

	private static Arbitrary<String> hashes() {
		return Arbitraries.strings().numeric().withChars("abcdef").ofLength(64);
	}

	private static Arbitrary<Instant> times() {
		return Arbitraries.longs().between(0, LATEST_TIME).map(Instant::ofEpochSecond);
	}

	private static Arbitrary<Integer> counts() {
		return Arbitraries.integers().greaterOrEqual(0);
	}

	public static Arbitrary<SnapshotInfo> infos() {
		return Builders.withBuilder(SnapshotInfo::builder)
			.use(hashes()).in((b, v) -> b.tipHash(v))
			.use(counts()).in((b, v) -> b.tipHeight(v))
			.use(counts()).in((b, v) -> b.addressCount(v))
			.use(Arbitraries.longs().greaterOrEqual(0)).in((b, v) -> b.entryCount(v))
			.use(Arbitraries.longs().greaterOrEqual(0)).in((b, v) -> b.transactionCount(v))
			.use(coins()).in((b, v) -> b.totalUnspent(v))
			.use(coins()).in((b, v) -> b.zerocoinMinted(v))
			.use(times()).in((b, v) -> b.built(v))
			.use(Arbitraries.of(SignatureStatus.class)).in((b, v) -> b.signature(v))
			.build(SnapshotInfo.SnapshotInfoBuilder::build);
	}

	public static Arbitrary<List<AddressTransaction>> transactions() {
		return Builders.withBuilder(AddressTransaction::builder)
			.use(hashes()).in((b, v) -> b.transaction(v))
			.use(times()).in((b, v) -> b.time(v))
			.use(counts()).in((b, v) -> b.height(v))
			.use(coins()).in((b, v) -> b.amount(v))
			.use(Arbitraries.of(EntryKind.class)).in((b, v) -> b.kind(v))
			.build(AddressTransaction.AddressTransactionBuilder::build)
			.list().ofMinSize(1).ofMaxSize(MAX_TRANSACTIONS);
	}

	public static Arbitrary<BuildReport> reports() {
		return Builders.withBuilder(BuildReport::builder)
			.use(hashes()).in((b, v) -> b.tipHash(v))
			.use(counts()).in((b, v) -> b.tipHeight(v))
			.use(counts()).in((b, v) -> b.storedBlocks(v))
			.use(counts()).in((b, v) -> b.chainBlocks(v))
			.use(counts()).in((b, v) -> b.staleBlocks(v))
			.use(counts()).in((b, v) -> b.addressCount(v))
			.use(Arbitraries.longs().greaterOrEqual(0)).in((b, v) -> b.entryCount(v))
			.use(Arbitraries.longs().greaterOrEqual(0)).in((b, v) -> b.transactionCount(v))
			.use(counts()).in((b, v) -> b.unspentOutputCount(v))
			.use(coins()).in((b, v) -> b.totalUnspent(v))
			.use(coins()).in((b, v) -> b.zerocoinMinted(v))
			.build(BuildReport.BuildReportBuilder::build);
	}
}
