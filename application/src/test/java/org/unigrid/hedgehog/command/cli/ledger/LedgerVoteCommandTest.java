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

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.nio.file.FileSystem;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.CHAIN;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.CHAIN_ID;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.ID;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.OTHER_CHAIN_ID;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.SEED;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.assertNothingPosted;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.noLedger;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.runAgainst;
import static org.unigrid.hedgehog.command.cli.ledger.FakeLedgerNode.accepted;
import static org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures.KEY_FILE;
import org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.Outcome;
import org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.Refusal;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.Ledger;
import org.unigrid.hedgehog.ledger.Vote;
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmission;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmitted;
import org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerVoteCommandTest {
	private static final String CANDIDATE = "ab".repeat(32);
	private static final String OTHER_VALIDATOR = "33".repeat(32);
	private static final long ROUND = 7;
	private static final String KEY_OPTION = "--key";
	private static final String CANDIDATE_OPTION = "--candidate";
	private static final String ADD_OPTION = "--add";
	private static final String REMOVE_OPTION = "--remove";
	private static final String CHAIN_OPTION = "--chain";

	@Provide
	public Arbitrary<byte[]> seeds() {
		return Arbitraries.bytes().array(byte[].class).ofSize(Ed25519.SEED_SIZE);
	}

	@Provide
	public Arbitrary<String> reasons() {
		return Arbitraries.strings().alpha().numeric().withChars(' ', '.', ':', '\'').ofMinLength(1).ofMaxLength(60);
	}

	private static Refusal refusedOn(FileSystem fs, String... arguments) {
		return LedgerCommandFixtures.refusedOn(fs, "vote", arguments);
	}

	private static Outcome sendOn(FileSystem fs, Response info, Response submitted, String... arguments) {
		return runAgainst(new FakeLedgerNode(info, Response.ok().build(), submitted), fs, "vote", arguments);
	}

	private static Response info(long round, String... validators) {
		return infoEnding(LedgerCommandFixtures.ROUND_ENDS, round, validators);
	}

	private static Response infoEnding(long roundEnds, long round, String... validators) {
		return info(roundEnds, round, List.of(validators), List.of(validators));
	}

	private static Response info(long roundEnds, long round, List<String> validators, List<String> roundValidators) {
		final LedgerInfo info = new LedgerInfo(true, false, "", true, CHAIN_ID, 3, "22".repeat(64), "12.50000000",
			round, roundEnds, validators, roundValidators, "0.00100000", 0, List.of(), List.of());

		return Response.ok(info).build();
	}

	/* The set that signs blocks, and the one the round of the node has left once the block that closes the round of the
	   tip comes, which a vote is judged by */
	private static Response changingSet(List<String> validators, List<String> roundValidators) {
		return info(LedgerCommandFixtures.ROUND_ENDS, ROUND, validators, roundValidators);
	}

	private static Vote voteIn(Outcome outcome) {
		final Request posted = outcome.requests().stream()
			.filter(request -> HttpMethod.POST.equals(request.method())).findFirst().orElseThrow();
		final LedgerSubmission submission = (LedgerSubmission) posted.entity().orElseThrow().getEntity();

		return (Vote) WireCodec.decodeTransaction(HexFormat.of().parseHex(submission.transaction()));
	}

	private static String signed() {
		return "Signed for chain " + CHAIN_ID + ": vote to add " + CANDIDATE + " in round " + ROUND;
	}

	private static void assertRefusedBeforeSending(Refusal outcome) {
		assertThat(outcome.sent().request(), equalTo(Optional.empty()));
		assertThat(outcome.sent().connection(), equalTo(Optional.empty()));
		assertThat(outcome.cli().exitCode(), equalTo(2));
		assertThat(outcome.cli().out(), emptyString());
		outcome.cli().assertNoStackTrace();
	}

	@Property(tries = 20)
	@SneakyThrows
	public void signsTheVoteWithTheVoterOfTheKeyFile(@ForAll("seeds") byte[] seed) {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, seed);
			final AccountKey voter = Ed25519.publicKey(seed);

			final Outcome outcome = sendOn(fs, info(ROUND, voter.toString()), accepted(), KEY_OPTION, KEY_FILE,
				CANDIDATE_OPTION, CANDIDATE, ADD_OPTION);
			final Vote vote = voteIn(outcome);

			assertThat(outcome.cli().exitCode(), equalTo(0));
			assertThat(outcome.cli().err().lines().toList(), equalTo(List.of(signed())));
			assertThat(outcome.cli().out(), containsString(HexFormat.of().formatHex(vote.id())));
			assertThat(vote.voter(), equalTo(voter));
			assertThat(vote.candidate(), equalTo(AccountKey.fromHex(CANDIDATE)));
			assertThat(vote.action(), equalTo(Vote.Action.ADD));
			assertThat(vote.round(), equalTo(ROUND));
			assertThat(vote.hasValidSignature(CHAIN), equalTo(true));
			assertThat(outcome.connected(), equalTo(true));
			assertThat(outcome.closed(), equalTo(true));
		}
	}

	@Example
	@SneakyThrows
	public void takesTheChainThatIsGivenInAnyCase() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);
			final AccountKey voter = Ed25519.publicKey(SEED);

			for (final String typed : List.of(CHAIN_ID, CHAIN_ID.toUpperCase(Locale.ROOT))) {
				final Outcome outcome = sendOn(fs, info(ROUND, voter.toString()), accepted(), KEY_OPTION,
					KEY_FILE, CANDIDATE_OPTION, CANDIDATE, ADD_OPTION, CHAIN_OPTION, typed);

				assertThat(outcome.cli().exitCode(), equalTo(0));
				assertThat(voteIn(outcome).hasValidSignature(CHAIN), equalTo(true));
			}
		}
	}

	@Example
	@SneakyThrows
	public void refusesAChainThatIsNotTheOneOfTheNode() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);
			final AccountKey voter = Ed25519.publicKey(SEED);

			final Outcome outcome = sendOn(fs, info(ROUND, voter.toString()), accepted(), KEY_OPTION, KEY_FILE,
				CANDIDATE_OPTION, CANDIDATE, ADD_OPTION, CHAIN_OPTION, OTHER_CHAIN_ID);

			assertThat(outcome.cli().exitCode(), equalTo(1));
			assertThat(outcome.cli().err().lines().toList(),
				equalTo(List.of("The node is on chain " + CHAIN_ID + ", not on " + OTHER_CHAIN_ID)));
			assertNothingPosted(outcome);
			outcome.cli().assertNoStackTrace();
		}
	}

	@Example
	@SneakyThrows
	public void refusesAChainThatIsNoChainId() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			for (final String typed : List.of("1".repeat(127), "zz".repeat(64))) {
				final Refusal outcome = refusedOn(fs, KEY_OPTION, KEY_FILE, CANDIDATE_OPTION,
					CANDIDATE, ADD_OPTION, CHAIN_OPTION, typed);

				assertRefusedBeforeSending(outcome);
				assertThat(outcome.cli().err(), containsString(CHAIN_OPTION));
			}
		}
	}

	@Example
	@SneakyThrows
	public void votesToRemoveAValidator() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);
			final AccountKey voter = Ed25519.publicKey(SEED);

			final Outcome outcome = sendOn(fs, info(ROUND, voter.toString(), OTHER_VALIDATOR), accepted(),
				KEY_OPTION, KEY_FILE, CANDIDATE_OPTION, OTHER_VALIDATOR, REMOVE_OPTION);
			final Vote vote = voteIn(outcome);

			assertThat(vote.action(), equalTo(Vote.Action.REMOVE));
			assertThat(vote.candidate(), equalTo(AccountKey.fromHex(OTHER_VALIDATOR)));
			assertThat(vote.hasValidSignature(CHAIN), equalTo(true));
		}
	}

	@Example
	@SneakyThrows
	public void refusesAVoteWithoutExactlyOneOfAddAndRemove() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			assertRefusedBeforeSending(refusedOn(fs, KEY_OPTION, KEY_FILE, CANDIDATE_OPTION, CANDIDATE));
			assertRefusedBeforeSending(refusedOn(fs, KEY_OPTION, KEY_FILE, CANDIDATE_OPTION, CANDIDATE,
				ADD_OPTION, REMOVE_OPTION));
		}
	}

	/* A proposer may close a round up to MAX_CLOCK_SKEW_MILLIS before its end by the clock of another node, so a vote
	   signed that close to the end may be taken in and then dropped; the voter is told to vote in the next round */
	@Example
	@SneakyThrows
	public void refusesToSignWhenTheRoundIsAboutToEnd() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);
			final AccountKey voter = Ed25519.publicKey(SEED);
			final long ends = System.currentTimeMillis() + Ledger.MAX_CLOCK_SKEW_MILLIS / 2;

			final Outcome outcome = sendOn(fs, infoEnding(ends, ROUND, voter.toString()), accepted(), KEY_OPTION,
				KEY_FILE, CANDIDATE_OPTION, CANDIDATE, ADD_OPTION);

			assertThat(outcome.cli().exitCode(), equalTo(2));
			assertThat(outcome.cli().err(), containsString("round " + (ROUND + 1) + ", which starts at "
				+ Instant.ofEpochMilli(ends)));
			assertThat(outcome.cli().err(), not(containsString("Signed for chain")));
			assertNothingPosted(outcome);
			outcome.cli().assertNoStackTrace();
		}
	}

	@Example
	@SneakyThrows
	public void signsWhileTheRoundHasLongerToRun() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);
			final AccountKey voter = Ed25519.publicKey(SEED);
			final long ends = System.currentTimeMillis() + 10 * Ledger.MAX_CLOCK_SKEW_MILLIS;

			final Outcome outcome = sendOn(fs, infoEnding(ends, ROUND, voter.toString()), accepted(), KEY_OPTION,
				KEY_FILE, CANDIDATE_OPTION, CANDIDATE, ADD_OPTION);

			assertThat(outcome.cli().exitCode(), equalTo(0));
			assertThat(voteIn(outcome).round(), equalTo(ROUND));
		}
	}

	@Example
	@SneakyThrows
	public void letsAKeyThatTheRoundThatPassedElectedVote() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);
			final String voter = Ed25519.publicKey(SEED).toString();

			final Outcome outcome = sendOn(fs, changingSet(List.of(OTHER_VALIDATOR), List.of(OTHER_VALIDATOR, voter)),
				accepted(), KEY_OPTION, KEY_FILE, CANDIDATE_OPTION, CANDIDATE, ADD_OPTION);

			assertThat(outcome.cli().exitCode(), equalTo(0));
			assertThat(voteIn(outcome).voter().toString(), equalTo(voter));
		}
	}

	@Example
	@SneakyThrows
	public void refusesAKeyThatTheRoundThatPassedRemoved() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);
			final String voter = Ed25519.publicKey(SEED).toString();

			final Outcome outcome = sendOn(fs, changingSet(List.of(OTHER_VALIDATOR, voter), List.of(OTHER_VALIDATOR)),
				accepted(), KEY_OPTION, KEY_FILE, CANDIDATE_OPTION, CANDIDATE, ADD_OPTION);

			assertThat(outcome.cli().exitCode(), equalTo(2));
			assertThat(outcome.cli().err(), containsString("This key is not a validator of the chain"));
			assertNothingPosted(outcome);
		}
	}

	@Example
	@SneakyThrows
	public void refusesAKeyThatIsNoValidator() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			final Outcome outcome = sendOn(fs, info(ROUND, OTHER_VALIDATOR), accepted(), KEY_OPTION, KEY_FILE,
				CANDIDATE_OPTION, CANDIDATE, ADD_OPTION);

			assertThat(outcome.cli().exitCode(), equalTo(2));
			assertThat(outcome.cli().err(), containsString("This key is not a validator of the chain"));
			assertNothingPosted(outcome);
			outcome.cli().assertNoStackTrace();
		}
	}

	@Example
	@SneakyThrows
	public void refusesToAddAnAccountThatIsAValidatorAlready() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);
			final AccountKey voter = Ed25519.publicKey(SEED);

			final Outcome outcome = sendOn(fs, info(ROUND, voter.toString(), OTHER_VALIDATOR), accepted(),
				KEY_OPTION, KEY_FILE, CANDIDATE_OPTION, OTHER_VALIDATOR, ADD_OPTION);

			assertThat(outcome.cli().exitCode(), equalTo(2));
			assertThat(outcome.cli().err(), containsString(OTHER_VALIDATOR + " is a validator already"));
			assertNothingPosted(outcome);
			outcome.cli().assertNoStackTrace();
		}
	}

	@Example
	@SneakyThrows
	public void refusesToRemoveAnAccountThatIsNoValidator() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);
			final AccountKey voter = Ed25519.publicKey(SEED);

			final Outcome outcome = sendOn(fs, info(ROUND, voter.toString()), accepted(), KEY_OPTION, KEY_FILE,
				CANDIDATE_OPTION, CANDIDATE, REMOVE_OPTION);

			assertThat(outcome.cli().exitCode(), equalTo(2));
			assertThat(outcome.cli().err(), containsString(CANDIDATE + " is no validator"));
			assertNothingPosted(outcome);
			outcome.cli().assertNoStackTrace();
		}
	}

	@Property(tries = 20)
	@SneakyThrows
	public void printsTheReasonOfAVoteTheLedgerRefuses(@ForAll("reasons") String reason) {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);
			final AccountKey voter = Ed25519.publicKey(SEED);

			final Response refused = Response.status(Status.CONFLICT)
				.entity(new LedgerSubmitted(false, ID, reason)).build();
			final Outcome outcome = sendOn(fs, info(ROUND, voter.toString()), refused, KEY_OPTION, KEY_FILE,
				CANDIDATE_OPTION, CANDIDATE, ADD_OPTION);

			assertThat(outcome.cli().exitCode(), equalTo(1));
			assertThat(outcome.cli().out(), emptyString());
			assertThat(outcome.cli().err().lines().toList(), equalTo(List.of(signed(), reason)));
			outcome.cli().assertNoStackTrace();
		}
	}

	@Example
	@SneakyThrows
	public void refusesWhenTheNodeHasNoLedger() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			final Outcome outcome = sendOn(fs, noLedger(), accepted(), KEY_OPTION, KEY_FILE, CANDIDATE_OPTION,
				CANDIDATE, ADD_OPTION);

			assertThat(outcome.cli().exitCode(), equalTo(1));
			assertThat(outcome.cli().err().lines().toList(), equalTo(List.of("The ledger is not running")));
			assertNothingPosted(outcome);
			outcome.cli().assertNoStackTrace();
		}
	}

	@Example
	@SneakyThrows
	public void refusesACandidateThatIsNoLedgerAccount() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			for (final String typed : List.of("", "nonsense")) {
				final Refusal outcome = refusedOn(fs, KEY_OPTION, KEY_FILE, CANDIDATE_OPTION, typed,
					ADD_OPTION);

				assertRefusedBeforeSending(outcome);
				assertThat(outcome.cli().err(), containsString("--candidate is not a ledger account"));
			}
		}
	}

	@Example
	@SneakyThrows
	public void refusesAKeyFileThatOthersCanRead() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.write(fs, KEY_FILE, HexFormat.of().formatHex(SEED) + "\n", "rw-r--r--");

			final Refusal outcome = refusedOn(fs, KEY_OPTION, KEY_FILE, CANDIDATE_OPTION, CANDIDATE,
				ADD_OPTION);

			assertRefusedBeforeSending(outcome);
			assertThat(outcome.cli().err(), containsString(KEY_FILE));
		}
	}

	@Property(tries = 20)
	@SneakyThrows
	public void neverPrintsTheSeed(@ForAll("seeds") byte[] seed) {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, seed);
			final String hex = HexFormat.of().formatHex(seed);
			final AccountKey voter = Ed25519.publicKey(seed);

			final Outcome outcome = sendOn(fs, info(ROUND, voter.toString()), accepted(), KEY_OPTION, KEY_FILE,
				CANDIDATE_OPTION, CANDIDATE, ADD_OPTION);

			assertThat(outcome.cli().out(), not(containsString(hex)));
			assertThat(outcome.cli().err(), not(containsString(hex)));
		}
	}
}
