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

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.Vote;
import org.unigrid.hedgehog.model.Json;
import org.unigrid.hedgehog.model.bootstrap.LedgerText;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.service.ledger.LedgerKeyFile;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "vote", description = "Sign a vote on the validator set with a key file and send it to the node.")
public class LedgerVoteCommand implements Callable<Integer> {
	@Spec
	private CommandSpec spec;

	@Mixin
	private ChainOption chain;

	@Option(names = "--key", required = true, paramLabel = "<file>",
		description = "The key file that holds the seed of the voter."
	)
	private Path keyFile;

	@Option(names = "--candidate", required = true, paramLabel = "<account>",
		description = "The ledger account to add to or remove from the validator set."
	)
	private String candidate;

	@ArgGroup(exclusive = true, multiplicity = "1")
	private VoteChoice voting;

	private static final class VoteChoice {
		@Option(names = "--add", description = "Vote to add the candidate to the validator set.")
		private boolean add;

		@Option(names = "--remove", description = "Vote to remove the candidate from the validator set.")
		private boolean remove;

		private Vote.Action action() {
			return add ? Vote.Action.ADD : Vote.Action.REMOVE;
		}
	}

	@Override
	public Integer call() {
		final Draft draft = draft();

		return LedgerClient.session(ledger -> send(ledger, draft));
	}

	private int send(LedgerClient ledger, Draft draft) throws LedgerClient.Refused {
		final LedgerInfo info = ledger.info();
		final byte[] chainId = LedgerClient.chainId(info, draft.chain());
		final long round = LedgerClient.round(info);

		refuseWhenNotAllowed(draft, LedgerClient.validators(info));
		final Vote vote = Vote.signed(draft.seed(), draft.candidate(), draft.action(), round, chainId);

		LedgerClient.announce(chainId, "vote to %s %s in round %d".formatted(
			draft.action().name().toLowerCase(Locale.ROOT), draft.candidate(), round));
		System.out.println(Json.parse(ledger.submit(vote.encode())));
		return 0;
	}

	private Draft draft() {
		return new Draft(requestedCandidate(), voting.action(), seed(), chain.expected(spec));
	}

	private record Draft(AccountKey candidate, Vote.Action action, byte[] seed, Optional<byte[]> chain) {
		/* Empty on purpose */
	}

	private AccountKey requestedCandidate() {
		return LedgerText.typed(candidate)
			.filter(AccountKey.class::isInstance)
			.map(AccountKey.class::cast)
			.orElseThrow(() -> refused("--candidate is not a ledger account"));
	}

	private byte[] seed() {
		try {
			return LedgerKeyFile.load(keyFile);
		} catch (IllegalArgumentException ex) {
			throw refused("--key: " + ex.getMessage());
		}
	}

	private void refuseWhenNotAllowed(Draft draft, List<String> validators) {
		final String voter = Ed25519.publicKey(draft.seed()).toString();

		if (!validators.contains(voter)) {
			throw refused("This key is not a validator of the chain");
		}

		final boolean listed = validators.contains(draft.candidate().toString());

		if (draft.action() == Vote.Action.ADD && listed) {
			throw refused(draft.candidate() + " is a validator already");
		}

		if (draft.action() == Vote.Action.REMOVE && !listed) {
			throw refused(draft.candidate() + " is no validator");
		}
	}

	private ParameterException refused(String reason) {
		return new ParameterException(spec.commandLine(), reason);
	}
}
