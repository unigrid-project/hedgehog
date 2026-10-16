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

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.command.option.LedgerOptions;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.LedgerDriver.Checkpoint;
import org.unigrid.hedgehog.ledger.LedgerGenesis;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LedgerSetup {
	private static final Pattern CHECKPOINT = Pattern.compile("(\\d+):(\\p{XDigit}{128})");
	private static final String NO_GENESIS = "--ledger needs a genesis: give --ledger-genesis <file> "
		+ "(no genesis is bundled with this release)";

	/* Runs before the container starts, so a bad setup stops the daemon with a readable message. A node that cannot
	   know which chain it is on has no business starting with a ledger, so a genesis is needed. A key that the
	   genesis does not list is only said: the node follows the chain and never signs, which may not be meant. */
	public static void validate() {
		if (LedgerOptions.isLedger()) {
			final Optional<byte[]> seed = Optional.ofNullable(LedgerOptions.getLedgerKeyFile())
				.map(LedgerKeyFile::load);

			trusted();

			final LedgerGenesis genesis = genesis();

			seed.ifPresent(held -> warnUnlessValidator(held, genesis));
		} else {
			warnOfOptionsThatAreIgnored();
		}
	}

	/* An operator who gives a key and forgets --ledger would otherwise run a node that does nothing of what was asked */
	private static void warnOfOptionsThatAreIgnored() {
		final List<String> given = Stream.of(named("--ledger-key", LedgerOptions.getLedgerKeyFile()),
			named("--ledger-genesis", LedgerOptions.getLedgerGenesisFile()),
			named("--ledger-trusted", LedgerOptions.getLedgerTrusted())).flatMap(Optional::stream).toList();

		if (!given.isEmpty()) {
			log.atWarn().addArgument(String.join(", ", given)).log("{} given, ignored because --ledger is off");
		}
	}

	private static Optional<String> named(String option, Object value) {
		return Optional.ofNullable(value).map(held -> option);
	}

	private static LedgerGenesis genesis() {
		return GenesisFile.load(Optional.ofNullable(LedgerOptions.getLedgerGenesisFile()))
			.orElseThrow(() -> new IllegalArgumentException(NO_GENESIS));
	}

	public static Optional<Checkpoint> trusted() {
		return Optional.ofNullable(LedgerOptions.getLedgerTrusted()).map(LedgerSetup::checkpoint);
	}

	private static void warnUnlessValidator(byte[] seed, LedgerGenesis genesis) {
		if (!genesis.getValidators().contains(Ed25519.publicKey(seed))) {
			log.atWarn().addArgument(LedgerKeyFile.publicKeyHex(seed))
				.log("The key {} is not a validator of this genesis, this node will not sign");
		}
	}

	private static Checkpoint checkpoint(String text) {
		final Matcher matcher = CHECKPOINT.matcher(text);

		if (!matcher.matches()) {
			throw refused(text, null);
		}

		try {
			return new Checkpoint(Long.parseLong(matcher.group(1)), HexFormat.of().parseHex(matcher.group(2)));
		} catch (IllegalArgumentException ex) {
			throw refused(text, ex);
		}
	}

	private static IllegalArgumentException refused(String text, Exception cause) {
		return new IllegalArgumentException("--ledger-trusted must be <height>:<128 hexadecimal characters of the "
			+ "hash>, with a height of 1 or more, not '" + text + "'", cause);
	}
}
