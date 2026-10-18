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

package org.unigrid.hedgehog.command.cli.ledger;

import java.util.HexFormat;
import java.util.Optional;
import org.unigrid.hedgehog.ledger.Digests;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;

/* The certificate of a node proves nothing, so the chain id must be named when the node is not on the local machine */
final class ChainOption {
	private static final int HEX_LENGTH = 2 * Digests.HASH_SIZE;

	@Option(names = "--chain", paramLabel = "<hex>",
		description = "The chain id of the node, as `cli ledger info` shows it; guards against a node that is not "
			+ "the one meant."
	)
	private String chain;

	Optional<byte[]> expected(CommandSpec spec) {
		if (chain == null) {
			return Optional.empty();
		}

		try {
			final byte[] parsed = HexFormat.of().parseHex(chain);

			if (parsed.length == Digests.HASH_SIZE) {
				return Optional.of(parsed);
			}
		} catch (IllegalArgumentException ex) {
			/* Refused below, in the same words as a wrong length */
		}

		throw new ParameterException(spec.commandLine(),
			"--chain is not a chain id of " + HEX_LENGTH + " hex characters");
	}
}
