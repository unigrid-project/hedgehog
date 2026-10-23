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

package org.unigrid.hedgehog.service.ledger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.command.option.SnapshotOptions;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.LedgerGenesis;
import org.unigrid.hedgehog.ledger.ValidatorSet;
import org.unigrid.hedgehog.model.bootstrap.AddressSnapshots;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDigest;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;

/* What every node of a fleet agrees on. The wallets hold the balances of the bootstrap snapshot, which is written once
   by the writer of the application and put where the nodes look for it: the data directory of the mock-up, the same
   file for all of them. The genesis commits to that file and to the validators. The tip of the snapshot is `tipAge`
   before now, and its heights are the old chain's: a mint at a height above `tipHeight` is due a minute after the
   tip for each height. */
record FleetChain(List<LegacyWallet> wallets, Path snapshot, long tipTimeMillis, LedgerGenesis genesis) {
	private static final long GENESIS_AGE_MILLIS = 60_000;
	private static final long ROUND_MILLIS = 3_600_000;
	private static final int FIRST_SECRET = 1_000;

	/* Alternate keys of both encodings, as wallets of the old chain have both */
	@SneakyThrows
	static FleetChain of(final LedgerFleet.Settings settings, final FleetClock clock, final List<byte[]> seeds) {
		final List<LegacyWallet> wallets = IntStream.range(0, settings.getBalances().size())
			.mapToObj(n -> new LegacyWallet(FIRST_SECRET + n, n % 2 == 0)).toList();
		final Map<String, Long> holdings = new HashMap<>();
		final long tipSeconds = (clock.now() - settings.getTipAge().toMillis()) / 1000;

		IntStream.range(0, wallets.size()).forEach(n -> holdings.put(wallets.get(n).hash160Hex(),
			settings.getBalances().get(n)));

		final Path snapshot = installed(AddressSnapshots.write(holdings, settings.getTipHeight(), (int) tipSeconds));
		final ValidatorSet validators = new ValidatorSet(seeds.stream().map(Ed25519::publicKey).toList());
		final LedgerGenesis genesis = new LedgerGenesis(clock.now() - GENESIS_AGE_MILLIS, ROUND_MILLIS, validators,
			SnapshotDigest.of(snapshot), SnapshotReader.open(snapshot).totalBalance());

		return new FleetChain(wallets, snapshot, tipSeconds * 1000, genesis);
	}

	@SneakyThrows
	private static Path installed(final Path written) {
		final Path file = ApplicationDirectory.create().getUserDataDir().resolve(SnapshotOptions.SNAPSHOT_FILE);

		return Files.move(written, file, StandardCopyOption.REPLACE_EXISTING);
	}
}
