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

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import org.unigrid.hedgehog.ledger.Address;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.LedgerDriver;
import org.unigrid.hedgehog.ledger.Transfer;
import org.unigrid.hedgehog.model.Json;
import org.unigrid.hedgehog.model.bootstrap.Coin;
import org.unigrid.hedgehog.model.bootstrap.LedgerText;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.service.ledger.LedgerKeyFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "transfer", description = "Sign a transfer with a key file and send it to the node.")
public class LedgerTransferCommand implements Callable<Integer> {
	@Spec
	private CommandSpec spec;

	@Option(names = "--key", required = true, paramLabel = "<file>",
		description = "The key file that holds the seed of the sender."
	)
	private Path keyFile;

	@Option(names = "--to", required = true, paramLabel = "<address>",
		description = "The account or legacy address that receives the coins."
	)
	private String to;

	@Option(names = "--amount", required = true, paramLabel = "<coins>",
		description = "How many coins to send, like 12.5."
	)
	private String amount;

	@Option(names = "--fee", paramLabel = "<coins>",
		description = "The fee in coins; the minimum fee of the node when left out."
	)
	private String fee;

	@Option(names = "--nonce", paramLabel = "<n>",
		description = "The number of transfers the sender has made; asked of the node when left out."
	)
	private Long nonce;

	@Override
	public Integer call() {
		final Draft draft = draft();

		return LedgerClient.session(ledger -> send(ledger, draft));
	}

	private int send(LedgerClient ledger, Draft draft) throws LedgerClient.Refused {
		final LedgerInfo info = ledger.info();
		final byte[] chainId = chainId(info);
		final long feeUnits = draft.fee() != null ? draft.fee() : minimumFee(info);
		final long nonceUnits = draft.nonce() != null ? draft.nonce()
			: ledger.account(LedgerText.text(Ed25519.publicKey(draft.seed()))).nonce();
		final Transfer transfer = Transfer.signed(draft.seed(), draft.recipient(), draft.amount(), feeUnits,
			nonceUnits, chainId);

		System.out.println(Json.parse(ledger.submit(transfer.encode())));
		return 0;
	}

	private Draft draft() {
		return new Draft(recipient(), amountUnits(), requestedFee(), requestedNonce(), seed());
	}

	private record Draft(Address recipient, long amount, Long fee, Long nonce, byte[] seed) {
		/* Empty on purpose */
	}

	private Address recipient() {
		return LedgerText.typed(to).orElseThrow(() -> refused("--to is not a ledger account or legacy address"));
	}

	private long amountUnits() {
		return units("--amount", amount);
	}

	private Long requestedFee() {
		return fee == null ? null : units("--fee", fee);
	}

	private Long requestedNonce() {
		if (nonce != null && nonce < 0) {
			throw refused("--nonce must be 0 or more, found " + nonce);
		}

		return nonce;
	}

	private byte[] seed() {
		try {
			return LedgerKeyFile.load(keyFile);
		} catch (IllegalArgumentException ex) {
			throw refused("--key: " + ex.getMessage());
		}
	}

	/* A node may set a minimum of zero, which LedgerText.units refuses for an amount that is typed */
	private long minimumFee(LedgerInfo info) {
		if (info.minimumFee() == null) {
			throw refused("No minimum fee is set on the node; pass --fee");
		}

		return new BigDecimal(info.minimumFee()).movePointRight(Coin.DECIMALS).longValueExact();
	}

	private long units(String option, String coins) {
		try {
			return LedgerText.units(coins);
		} catch (IllegalArgumentException ex) {
			throw refused(option + ": " + ex.getMessage());
		}
	}

	private ParameterException refused(String reason) {
		return new ParameterException(spec.commandLine(), reason);
	}

	/* A node without a ledger reports no chain, and nothing can be signed for it */
	private static byte[] chainId(LedgerInfo info) throws LedgerClient.Refused {
		if (info.chainId() == null) {
			throw new LedgerClient.Refused(LedgerDriver.NOT_RUNNING);
		}

		return HexFormat.of().parseHex(info.chainId());
	}
}
