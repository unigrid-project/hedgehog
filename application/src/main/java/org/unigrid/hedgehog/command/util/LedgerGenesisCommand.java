/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
    the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.command.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.LedgerGenesis;
import org.unigrid.hedgehog.ledger.ValidatorSet;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDigest;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;
import org.unigrid.hedgehog.service.ledger.GenesisFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "ledger-genesis",
	description = "Makes the genesis file of the ledger from a bootstrap snapshot and prints the numbers operators"
		+ " compare."
)
public class LedgerGenesisCommand implements Runnable {
	private static final Pattern KEY = Pattern.compile("\\p{XDigit}{" + AccountKey.SIZE * 2 + "}");
	private static final HexFormat HEX = HexFormat.of();

	@Parameters(index = "0", paramLabel = "<snapshot>",
		description = "The bootstrap snapshot the genesis commits to; the file must exist."
	)
	private Path snapshot;

	@Option(names = "--validator", paramLabel = "<64 hex>", required = true,
		description = "An Ed25519 public key of a validator, in proposer order; repeat for each."
	)
	private List<String> validators;

	@Option(names = "--time", paramLabel = "<Unix ms>", required = true,
		description = "The start time of the chain, in Unix milliseconds."
	)
	private long time;

	@Option(names = "--round-length", paramLabel = "<blocks>", required = true,
		description = "The length of a round in blocks, at least 1."
	)
	private int roundLength;

	@Option(names = "--output", paramLabel = "<file>", required = true,
		description = "Where to write the genesis; the file must not exist."
	)
	private Path output;

	@Spec private CommandSpec spec;

	@Override
	public void run() {
		final ValidatorSet set = validatorSet();

		if (time < 0) {
			throw new ParameterException(spec.commandLine(), "--time is 0 or more, found " + time);
		}

		if (roundLength < 1) {
			throw new ParameterException(spec.commandLine(), "--round-length is at least 1 block, found "
				+ roundLength);
		}

		if (Files.exists(output)) {
			throw new ParameterException(spec.commandLine(), "--output " + output
				+ " exists and is not overwritten");
		}

		if (!Files.exists(snapshot)) {
			throw new ParameterException(spec.commandLine(), "The snapshot " + snapshot + " does not exist");
		}

		final LedgerGenesis genesis = read(set);

		write(genesis);
		print(genesis);
	}

	private ValidatorSet validatorSet() {
		if (validators.size() > ValidatorSet.MAX_SIZE) {
			throw new ParameterException(spec.commandLine(), "--validator takes at most "
				+ ValidatorSet.MAX_SIZE + " keys");
		}

		final Set<AccountKey> keys = new LinkedHashSet<>();

		for (final String key : validators) {
			if (!KEY.matcher(key).matches()) {
				throw new ParameterException(spec.commandLine(), "--validator must be " + AccountKey.SIZE * 2
					+ " hexadecimal characters");
			}

			if (!keys.add(AccountKey.fromHex(key))) {
				throw new ParameterException(spec.commandLine(), "--validator repeats an earlier key");
			}
		}

		return new ValidatorSet(List.copyOf(keys));
	}

	private LedgerGenesis read(ValidatorSet set) {
		try {
			final SnapshotReader reader = SnapshotReader.open(snapshot);

			return new LedgerGenesis(time, roundLength, set, SnapshotDigest.of(snapshot),
				reader.totalBalance());
		} catch (IOException | IllegalArgumentException ex) {
			throw new ParameterException(spec.commandLine(), "Cannot read the snapshot " + snapshot + ": "
				+ ex, ex);
		}
	}

	private void write(LedgerGenesis genesis) {
		try {
			GenesisFile.write(output, genesis);
		} catch (IOException ex) {
			throw new ParameterException(spec.commandLine(), "Cannot write the ledger genesis " + output + ": "
				+ ex, ex);
		}
	}

	private static void print(LedgerGenesis genesis) {
		System.out.println("snapshotHash: " + HEX.formatHex(genesis.getSnapshotHash()));
		System.out.println("snapshotTotal: " + genesis.getSnapshotTotal());
		System.out.println("validators: " + genesis.getValidators().size());
		System.out.println("chainId: " + GenesisFile.chainIdHex(genesis));
	}
}
