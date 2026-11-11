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

package org.unigrid.hedgehog.command.cli.spork;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.command.ExitCode;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.cli.SporkCommands;
import static org.unigrid.hedgehog.command.cli.SporkCommands.PROPOSER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.proposed;
import static org.unigrid.hedgehog.command.cli.SporkCommands.subcommand;
import static org.unigrid.hedgehog.command.cli.SporkCommands.tree;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Answer;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerFee.SporkData;
import org.unigrid.hedgehog.model.spork.LedgerFeeSporkTest;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerFeeSporkCommandTest {
	private static final String NAME = "ledger-fee";
	private static final String LOCATION = "/gridspork/ledger-fee";
	private static final String NOT_TAKEN = "This release does not take the LEDGER_FEE spork";
	private static final String HINT = "The node holds no LEDGER_FEE spork, so nothing was signed; give --first-version"
		+ " to make the first version of it";

	/* What a command that finds no fee on the node asks of it before it tells of the option, in that order */
	private static final List<String> ASKED_FOR_THE_HINT = List.of("GET " + FakeSporkNode.WIRE + "/" + Type.BOARD_KEYS,
		"GET " + FakeSporkNode.WIRE + "/" + Type.LEDGER_FEE, "GET " + LOCATION);

	private record Outcome(FakeSporkNode node, Result sent, HedgehogCli.Result cli) { }

	@Provide
	public Arbitrary<String> sporks() {
		return SporkCommands.reports();
	}

	/* The three ways a decimal comes: padded to eight places, trimmed, and as an exponent for the smallest ones */
	@Provide
	public Arbitrary<String> acceptedTexts() {
		return LedgerFeeSporkTest.fees().flatMap(fee -> Arbitraries.of(fee.toPlainString(),
			fee.stripTrailingZeros().toPlainString(), fee.toString()));
	}

	@Provide
	public Arbitrary<String> refusedTexts() {
		return Arbitraries.oneOf(LedgerFeeSporkTest.negativeFees(), LedgerFeeSporkTest.feesAboveACoin(),
			LedgerFeeSporkTest.feesWithTooManyDecimals()).map(BigDecimal::toPlainString);
	}

	@Provide
	public Arbitrary<String> notNumbers() {
		return Arbitraries.oneOf(Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20),
			Arbitraries.of("", " ", "0,5", "1.5.0", "0x10", "1e", "NaN", "Infinity", "1e99999999999"));
	}

	@BeforeTry
	public void fakeTheBoard() {
		SporkCommands.fakeTheBoard();
	}

	/* A node that holds no fee yet takes the first version of it */
	private static Result set(String fee, FakeSporkNode node) {
		return RestCommandFixture.run(subcommand(new GridSporkSet(), "-D", fee, "-k", PROPOSER.getPrivateKey(), NAME,
			"--first-version"), node);
	}

	/* The data is judged before the key, so any key will do; the long form takes any value, a negative one among them */
	private static HedgehogCli.Result setThroughTheTree(String fee) {
		return HedgehogCli.run("cli", "gridspork-set", "--data=" + fee, "-k", "0".repeat(64), NAME);
	}

	@Property(tries = 10)
	public void getsAndPrintsTheFee(@ForAll("sporks") String spork) {
		final Result result = RestCommandFixture.run(subcommand(new GridSporkGet(), NAME), Response.ok(spork).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.GET, LOCATION, Optional.empty(),
			Optional.empty()))));

		assertThat(tree(result.out()), equalTo(tree(spork)));
		assertThat(result.err(), equalTo(""));
	}

	/* An amount travels in units, so it reaches the node with eight decimals whatever it was typed with */
	private static void assertProposes(String text, SporkData expected) {
		final FakeSporkNode node = new FakeSporkNode();
		final SporkData proposed = proposed(set(text, node), node, Type.LEDGER_FEE).getData();

		assertThat(proposed.getMinimumTransferFee(), comparesEqualTo(expected.getMinimumTransferFee()));
		assertThat(proposed.getMinimumNewAccountAmount(), comparesEqualTo(expected.getMinimumNewAccountAmount()));
	}

	@Property(tries = 20)
	public void proposesTheFeeExactlyAsTypedSignedHereWithTheKey(@ForAll("acceptedTexts") String text) {
		assertProposes(text, LedgerFeeSporkTest.with(new BigDecimal(text)));
	}

	@Example
	public void readsADecimalNumberOfCoinsWithoutFloatingPoint() {
		assertProposes("0.1", LedgerFeeSporkTest.with(new BigDecimal("0.1")));
	}

	@Property(tries = 20)
	public void namesTheFeeThatTheSporkDoesNotAccept(@ForAll("refusedTexts") String text) {
		final HedgehogCli.Result result = setThroughTheTree(text);

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("The fee \"" + text + "\" is not accepted"));
		assertThat(result.out(), emptyString());
		result.assertNoStackTrace();
	}

	@Property(tries = 20)
	public void namesTheTextThatIsNoNumber(@ForAll("notNumbers") String text) {
		final HedgehogCli.Result result = setThroughTheTree(text);

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("The fee \"" + text + "\" is not accepted"));
		assertThat(result.out(), emptyString());
		result.assertNoStackTrace();
	}

	@Example
	public void readsTheFeeAndTheAmountToANewAccount() {
		final SporkData expected = LedgerFeeSporkTest.with(new BigDecimal("0.001"));

		expected.setMinimumNewAccountAmount(new BigDecimal("1.5"));
		assertProposes("0.001: 1.5", expected);
	}

	@Example
	public void refusesMoreThanTheFeeAndTheAmountToANewAccount() {
		final HedgehogCli.Result result = setThroughTheTree("0.001:1:2");

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("give the fee, or the fee and the amount to a new account"));
		result.assertNoStackTrace();
	}

	@Example
	public void saysWhyAFeeOfNineDecimalsIsRefused() {
		final HedgehogCli.Result result = setThroughTheTree("0.000000001");

		assertThat(result.err(), containsString("at most 8 decimals"));
	}

	/* A key that the board added since the release is one of them, so a key is judged by the board keys of the node */
	@Example
	public void refusesAKeyThatIsNoBoardKeyOnceItKnowsTheBoardKeysOfTheNode() {
		assertThat(SporkCommands.refusalOnceAsked(subcommand(new GridSporkSet(), "--data=0.001", "-k",
			SporkCommands.STRANGER.getPrivateKey(), NAME), new FakeSporkNode()),
			containsString("not one of the current board keys"));
	}

	@Example
	public void refusesToRunUnderAnUnknownCommand() {
		final CommandSpec unknown = CommandSpec.create().addSubcommand(NAME, new CommandLine(new LedgerFee()));

		assertThrows(UnsupportedOperationException.class, () -> subcommand(unknown, NAME).run());
	}

	/* As the shell runs it, without the option that makes a first version, against a node that holds no fee and that
	   answers where it shows the fee as it is told to */
	private static Outcome setWithoutAFirstVersion(FakeSporkNode node, Answer shown) {
		final AtomicReference<HedgehogCli.Result> cli = new AtomicReference<>();
		final Result sent = RestCommandFixture.run(() -> cli.set(HedgehogCli.run("cli", "gridspork-set", "--data", "0.001",
			"--key", PROPOSER.getPrivateKey(), NAME)), request -> LOCATION.equals(request.location())
			? shown.respond(request) : node.respond(request));

		return new Outcome(node, sent, cli.get());
	}

	private static Answer refusing(Status status, String body) {
		return request -> {
			throw new ResponseOddityException(status, body);
		};
	}

	private static List<String> asked(Outcome outcome) {
		return outcome.sent().requests().stream().map(request -> request.method() + " " + request.location()).toList();
	}

	private static void assertAsksForAFirstVersion(Outcome outcome) {
		assertThat(outcome.cli().exitCode(), equalTo(2));
		assertThat(outcome.cli().err(), startsWith(HINT));
		assertThat(outcome.cli().out(), emptyString());
		assertThat(outcome.node().submitted(), empty());
		outcome.cli().assertNoStackTrace();
	}

	/* A node that takes no fee holds none, and a first version is one that it refuses once the board key signed it. It
	   says that it takes none where it shows the fee, which the command tells in place of the option, and signs nothing. */
	@Example
	public void saysThatTheNodeTakesNoFeeRatherThanAskForAFirstVersion() {
		final Outcome outcome = setWithoutAFirstVersion(new FakeSporkNode(),
			refusing(Status.SERVICE_UNAVAILABLE, NOT_TAKEN + "\n"));

		assertThat(outcome.cli().exitCode(), equalTo(ExitCode.FAILED));
		assertThat(outcome.cli().err().lines().toList(), equalTo(List.of(
			new ResponseOddityException(Status.SERVICE_UNAVAILABLE).getMessage() + ": " + NOT_TAKEN)));
		assertThat(outcome.cli().out(), emptyString());
		assertThat(outcome.node().submitted(), empty());
		assertThat(asked(outcome), equalTo(ASKED_FOR_THE_HINT));
	}

	/* The node is asked where it shows the fee once, and for the sake of that line alone */
	@Example
	public void asksForAFirstVersionOfANodeThatTakesTheFeeAndHoldsNone() {
		final FakeSporkNode node = new FakeSporkNode();
		final Outcome outcome = setWithoutAFirstVersion(node, node);

		assertAsksForAFirstVersion(outcome);
		assertThat(asked(outcome), equalTo(ASKED_FOR_THE_HINT));
	}

	/* Only the sentence of a node that takes no fee takes the place of the option: a node that says nothing of it, one
	   that answers something else, and one that stops answering leave the command as it was */
	@Example
	public void asksForAFirstVersionWhateverElseTheNodeAnswersWhereItShowsTheFee() {
		final List<Answer> odd = List.of(refusing(Status.SERVICE_UNAVAILABLE, ""), refusing(Status.SERVICE_UNAVAILABLE, " \n"),
			refusing(Status.INTERNAL_SERVER_ERROR, "<html>Oops</html>"), refusing(Status.BAD_GATEWAY, NOT_TAKEN),
			request -> Response.status(Status.NOT_FOUND).entity(NOT_TAKEN).build(),
			request -> Response.status(Status.UNAUTHORIZED).build(), request -> Response.ok("not a spork").build(),
			RestCommandFixture.unreachable(new ConnectException("Connection reset")));

		for (Answer shown : odd) {
			final Outcome outcome = setWithoutAFirstVersion(new FakeSporkNode(), shown);

			assertAsksForAFirstVersion(outcome);
			assertThat(asked(outcome), equalTo(ASKED_FOR_THE_HINT));
		}
	}

	/* A version over what the node holds, and a first version that the operator asked for, need no such answer */
	@Example
	public void doesNotAskWhereTheNodeShowsTheFeeWhenItProposes() {
		final FakeSporkNode holding = new FakeSporkNode().holding(SporkCommands.stored(Type.LEDGER_FEE,
			LedgerFeeSporkTest.with(new BigDecimal("0.001"))));
		final FakeSporkNode fresh = new FakeSporkNode();
		final Result next = RestCommandFixture.run(subcommand(new GridSporkSet(), "-D", "0.002", "-k",
			PROPOSER.getPrivateKey(), NAME), holding);
		final Result first = set("0.002", fresh);

		proposed(next, holding, Type.LEDGER_FEE);
		proposed(first, fresh, Type.LEDGER_FEE);
		assertThat(next.requests().stream().map(Request::location).toList(), not(hasItem(LOCATION)));
		assertThat(first.requests().stream().map(Request::location).toList(), not(hasItem(LOCATION)));
	}
}
