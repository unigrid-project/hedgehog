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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.Optional;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.ledger.Digests;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;

/* The certificate of a node proves nothing, so a node that answers for another chain, or one that is not the node
   meant, can only be told by the chain id the person names. It is left out only where the node is on this machine,
   since anything else could be any node that answers, and could choose what is signed. */
final class ChainOption {
	private static final int HEX_LENGTH = 2 * Digests.HASH_SIZE;

	@Option(names = "--chain", paramLabel = "<hex>",
		description = "The chain id of the node, as `cli ledger info` shows it; guards against a node that is not "
			+ "the one meant. Required when the node is not on this machine."
	)
	private String chain;

	Optional<byte[]> expected(CommandSpec spec) {
		if (chain == null) {
			requireWhenRemote(spec);
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

	private static void requireWhenRemote(CommandSpec spec) {
		final String host = RestOptions.getHost();

		if (!isLoopback(host)) {
			throw new ParameterException(spec.commandLine(), "Pass --chain: a node at " + host
				+ " cannot prove which chain it is on; `cli ledger info` on a node you trust shows it");
		}
	}

	/* A name that cannot be resolved is not known to be on this machine */
	private static boolean isLoopback(String host) {
		try {
			return InetAddress.getByName(host).isLoopbackAddress();
		} catch (UnknownHostException ex) {
			return false;
		}
	}
}
