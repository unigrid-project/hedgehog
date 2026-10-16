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
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.command.option.LedgerOptions;
import org.unigrid.hedgehog.ledger.LedgerDriver.Checkpoint;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LedgerSetup {
	private static final Pattern CHECKPOINT = Pattern.compile("(\\d+):(\\p{XDigit}{128})");

	/* Runs before the container starts, so a bad setup stops the daemon with a readable message */
	public static void validate() {
		if (LedgerOptions.isLedger()) {
			Optional.ofNullable(LedgerOptions.getLedgerKeyFile()).ifPresent(LedgerKeyFile::load);
			Optional.ofNullable(LedgerOptions.getLedgerGenesisFile()).ifPresent(LedgerSetup::requireFile);
			trusted();
		}
	}

	public static Optional<Checkpoint> trusted() {
		return Optional.ofNullable(LedgerOptions.getLedgerTrusted()).map(LedgerSetup::checkpoint);
	}

	private static void requireFile(Path genesis) {
		if (!Files.isRegularFile(genesis)) {
			throw new IllegalArgumentException("The ledger genesis " + genesis + " is not an existing file");
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
