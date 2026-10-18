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
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.server.rest.entity.LedgerAccount;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmitted;
import static org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures.KEY_FILE;
import org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures;
import org.unigrid.hedgehog.service.ledger.LegacyWallet;
import picocli.CommandLine;

/* What transfer, vote and claim have in common: a node that cannot prove who it is must not be the one to say what
   is signed, so where the node is not on this machine the chain is named by the person and what is signed is shown
   before the node answers, and whatever a node answers, the command ends with a line and no stack trace */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerSigningCommandsTest {
	private static final String CHAIN_ID = "11".repeat(64);
	private static final byte[] SEED = HexFormat.of().parseHex("42".repeat(32));
	private static final String ACCOUNT = "ab".repeat(32);
	private static final String ID = "9f".repeat(64);
	private static final LegacyWallet WALLET = new LegacyWallet(7, true);
	private static final String REMOTE = "192.0.2.7";
	private static final String REASON = "Balance does not cover the transfer";
	private static final String UNREADABLE = "The node answered something this command cannot read: ";

	private record Command(List<String> arguments, String signed) { }

	private record Outcome(FakeLedgerNode node, HedgehogCli.Result cli) { }

	private static Command transfer() {
		return new Command(List.of("transfer", "--key", KEY_FILE, "--to", ACCOUNT, "--amount", "1.5", "--fee",
			"0.001", "--nonce", "7"), "Signed for chain " + CHAIN_ID + ": transfer 1.50000000 to " + ACCOUNT
			+ " fee 0.00100000 nonce 7");
	}

	private static Command vote() {
		return new Command(List.of("vote", "--key", KEY_FILE, "--candidate", ACCOUNT, "--add"),
			"Signed for chain " + CHAIN_ID + ": vote to add " + ACCOUNT + " in round 7");
	}

	private static Command claim() {
		final String text = "claim " + CHAIN_ID + " " + ACCOUNT;

		return new Command(List.of("claim", "--address", WALLET.base58(), "--to", ACCOUNT, "--signature",
			Base64.getEncoder().encodeToString(WALLET.sign(text))),
			"Signed for chain " + CHAIN_ID + ": claim of " + WALLET.base58() + " to " + ACCOUNT);
	}

	private static List<Command> commands() {
		return List.of(transfer(), vote(), claim());
	}

	private static Response info(final String chainId, final Long round, final List<String> validators) {
		return Response.ok(new LedgerInfo(true, false, "", true, chainId, 3, "22".repeat(64), "12.50000000", round,
			validators, "0.00100000", 0, List.of(), List.of())).build();
	}

	private static Response info() {
		return info(CHAIN_ID, 7L, List.of(Ed25519.publicKey(SEED).toString()));
	}

	private static Response account(final Long nonce) {
		return Response.ok(new LedgerAccount(ACCOUNT, "account", "10.00000000", nonce, "10.00000000", null, null))
			.build();
	}

	private static Response accepted() {
		return Response.accepted(new LedgerSubmitted(true, ID, null)).build();
	}

	private static Response refused() {
		return Response.status(Status.CONFLICT).entity(new LedgerSubmitted(false, ID, REASON)).build();
	}

	@SneakyThrows
	private static Outcome run(final String host, final Response info, final Response account,
		final Response submitted, final Command command, final String... more) {

		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			final FakeLedgerNode node = new FakeLedgerNode(host, info, account, submitted);
			final HedgehogCli.Result cli = HedgehogCli.run(
				() -> new CommandLine(Hedgehog.class).registerConverter(Path.class, fs::getPath),
				Stream.of(Stream.of("cli", "ledger"), command.arguments().stream(), Stream.of(more))
					.flatMap(stream -> stream).toArray(String[]::new));

			return new Outcome(node, cli);
		}
	}

	private static Outcome run(final String host, final Response info, final Response submitted,
		final Command command) {

		return run(host, info, account(0L), submitted, command);
	}

	private static Outcome run(final String host, final Response submitted, final Command command,
		final String... more) {

		return run(host, info(), account(0L), submitted, command, more);
	}

	private static long posts(final Outcome outcome) {
		return outcome.node().requests().stream().filter(request -> HttpMethod.POST.equals(request.method())).count();
	}

	private static void assertEndsWithOneLine(final Outcome outcome, final String line) {
		assertThat(outcome.cli().exitCode(), equalTo(1));
		assertThat(outcome.cli().out(), emptyString());
		assertThat(outcome.cli().err().lines().toList(), equalTo(List.of(line)));
		assertThat(posts(outcome), equalTo(0L));
		outcome.cli().assertNoStackTrace();
	}

	@Example
	public void showsWhatWasSignedOnTheErrorStreamAndKeepsTheOutputToTheAnswer() {
		for (final Command command : commands()) {
			final Outcome outcome = run(FakeLedgerNode.LOCAL, accepted(), command);

			assertThat(outcome.cli().exitCode(), equalTo(0));
			assertThat(outcome.cli().err().lines().toList(), equalTo(List.of(command.signed())));
			assertThat(outcome.cli().out(), containsString(ID));
		}
	}

	@Example
	public void showsWhatWasSignedBeforeTheNodeSaysAnythingOfIt() {
		for (final Command command : commands()) {
			final Outcome outcome = run(FakeLedgerNode.LOCAL, refused(), command);

			assertThat(outcome.cli().exitCode(), equalTo(1));
			assertThat(outcome.cli().err().lines().toList(), equalTo(List.of(command.signed(), REASON)));
			assertThat(outcome.cli().out(), emptyString());
		}
	}

	@Example
	public void asksForTheChainOfANodeThatIsNotOnThisMachineAndDoesNotConnectWithoutIt() {
		for (final Command command : commands()) {
			final Outcome outcome = run(REMOTE, accepted(), command);

			assertThat(outcome.cli().exitCode(), equalTo(2));
			assertThat(outcome.cli().err(), containsString("Pass --chain: a node at " + REMOTE
				+ " cannot prove which chain it is on; `cli ledger info` on a node you trust shows it"));
			assertThat(outcome.cli().out(), emptyString());
			assertThat(outcome.node().connected(), equalTo(false));
			assertThat(outcome.node().requests().isEmpty(), equalTo(true));
			outcome.cli().assertNoStackTrace();
		}
	}

	@Example
	public void goesAheadWhereTheChainOfANodeThatIsNotOnThisMachineIsGiven() {
		for (final Command command : commands()) {
			final Outcome outcome = run(REMOTE, accepted(), command, "--chain", CHAIN_ID);

			assertThat(outcome.cli().exitCode(), equalTo(0));
			assertThat(posts(outcome), equalTo(1L));
		}
	}

	@Example
	public void goesAheadWithoutTheChainWhereTheNodeIsOnThisMachine() {
		for (final String host : List.of("localhost", "LOCALHOST", "127.0.0.1", "127.0.0.2", "::1")) {
			for (final Command command : commands()) {
				final Outcome outcome = run(host, accepted(), command);

				assertThat(host, outcome.cli().exitCode(), equalTo(0));
				assertThat(posts(outcome), equalTo(1L));
			}
		}
	}

	@Example
	public void saysInOneLineThatAStatusOfTheNodeIsNoStatus() {
		for (final Command command : commands()) {
			assertEndsWithOneLine(run(FakeLedgerNode.LOCAL, Response.status(599).entity("<html>").build(),
				accepted(), command), UNREADABLE + "status 599");
		}
	}

	@Example
	public void saysInOneLineThatTheChainIdOfTheNodeIsNoChainId() {
		for (final Command command : commands()) {
			for (final String odd : List.of("zz", "1", "11".repeat(63), "11".repeat(65))) {
				assertEndsWithOneLine(run(FakeLedgerNode.LOCAL, info(odd, 7L, List.of()), accepted(), command),
					UNREADABLE + "the chain id is not 128 hex characters");
			}
		}
	}

	@Example
	public void saysInOneLineThatTheNodeLeftOutTheRoundTheValidatorsOrTheNonce() {
		final List<String> voter = List.of(Ed25519.publicKey(SEED).toString());
		final Command withoutNonce = new Command(
			transfer().arguments().subList(0, transfer().arguments().size() - 2), "");

		assertEndsWithOneLine(run(FakeLedgerNode.LOCAL, info(CHAIN_ID, null, voter), accepted(), vote()),
			UNREADABLE + "no round");
		assertEndsWithOneLine(run(FakeLedgerNode.LOCAL, info(CHAIN_ID, 7L, null), accepted(), vote()),
			UNREADABLE + "no validators");
		assertEndsWithOneLine(run(FakeLedgerNode.LOCAL, info(), account(null), accepted(), withoutNonce),
			UNREADABLE + "no nonce");
	}
}
