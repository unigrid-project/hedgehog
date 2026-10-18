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

import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.LegacyAddress;
import org.unigrid.hedgehog.ledger.LegacySignature;
import org.unigrid.hedgehog.model.Json;
import org.unigrid.hedgehog.model.bootstrap.LedgerText;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/* The wallet of the legacy chain prints a signature and no key, and the key of a legacy address is never asked of its
   owner: the signature of the text this command prints is all the claim is made from */
@Command(name = "claim", description = "Claim the coins of a legacy address with a signature from the legacy wallet.")
public class LedgerClaimCommand implements Callable<Integer> {
	@Spec
	private CommandSpec spec;

	@Mixin
	private ChainOption chain;

	@Option(names = "--address", required = true, paramLabel = "<address>",
		description = "The legacy address that holds the coins."
	)
	private String address;

	@Option(names = "--to", required = true, paramLabel = "<account>",
		description = "The ledger account that receives them."
	)
	private String to;

	@Option(names = "--signature", paramLabel = "<base64>",
		description = "What signmessage printed in the legacy wallet; the text to sign is printed when left out."
	)
	private String signature;

	@Override
	public Integer call() {
		final Draft draft = draft();

		return LedgerClient.session(ledger -> send(ledger, draft));
	}

	private int send(LedgerClient ledger, Draft draft) throws LedgerClient.Refused {
		final byte[] chainId = LedgerClient.chainId(ledger.info(), draft.chain());
		final String text = Claim.message(chainId, draft.recipient());

		if (draft.signature().isEmpty()) {
			System.out.println("Text to sign: " + text);
			System.out.println("Run this in the legacy wallet, then run this command again with --signature "
				+ "<what it prints>:");
			System.out.println("signmessage \"" + LedgerText.text(draft.address()) + "\" \"" + text + "\"");
			return 0;
		}

		System.out.println(Json.parse(ledger.submit(claimOf(draft, chainId, text).encode())));
		return 0;
	}

	/* Everything the node would find wrong with the claim is found here first, so nothing that cannot be accepted is
	   sent. A signature of another text than this one is recovered to some other key, which is why an address that
	   does not match may mean a wrong text as much as a wrong wallet. */
	private Claim claimOf(Draft draft, byte[] chainId, String text) {
		final byte[] signed = draft.signature().orElseThrow();
		final byte[] key = LegacySignature.recover(text, signed).orElseThrow(
			() -> refused("The signature is not one of this text: was it made for " + text + "?"));
		final Claim claim = new Claim(key, draft.recipient(), signed);

		if (!claim.address().equals(draft.address())) {
			throw refused(("The signature was made with the key of %s, not %s: was it made in another wallet, "
				+ "or for another text than %s?")
				.formatted(LedgerText.text(claim.address()), LedgerText.text(draft.address()), text));
		}

		if (!claim.hasValidSignature(chainId)) {
			throw refused("The signature does not verify for " + text);
		}

		return claim;
	}

	private Draft draft() {
		return new Draft(legacyAddress(), recipient(), decoded(), chain.expected(spec));
	}

	private record Draft(LegacyAddress address, AccountKey recipient, Optional<byte[]> signature,
		Optional<byte[]> chain) {
		/* Empty on purpose */
	}

	private LegacyAddress legacyAddress() {
		return LedgerText.typed(address)
			.filter(LegacyAddress.class::isInstance)
			.map(LegacyAddress.class::cast)
			.orElseThrow(() -> refused("--address is not a legacy address"));
	}

	private AccountKey recipient() {
		return LedgerText.typed(to)
			.filter(AccountKey.class::isInstance)
			.map(AccountKey.class::cast)
			.orElseThrow(() -> refused("--to is not a ledger account"));
	}

	private Optional<byte[]> decoded() {
		return Optional.ofNullable(signature).map(this::sixtyFiveBytes);
	}

	private byte[] sixtyFiveBytes(String base64) {
		try {
			final byte[] bytes = Base64.getDecoder().decode(base64);

			if (bytes.length == LegacySignature.SIZE) {
				return bytes;
			}
		} catch (IllegalArgumentException ex) {
			/* Refused below, in the same words as a wrong length */
		}

		throw refused("The signature is not " + LegacySignature.SIZE + " bytes of base64");
	}

	private ParameterException refused(String reason) {
		return new ParameterException(spec.commandLine(), reason);
	}
}
