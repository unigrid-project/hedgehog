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
import java.math.BigDecimal;
import java.util.Optional;
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
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.cli.SporkCommands;
import static org.unigrid.hedgehog.command.cli.SporkCommands.PROPOSER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.proposed;
import static org.unigrid.hedgehog.command.cli.SporkCommands.subcommand;
import static org.unigrid.hedgehog.command.cli.SporkCommands.tree;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
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

	private static Result set(String fee, FakeSporkNode node) {
		return RestCommandFixture.run(subcommand(new GridSporkSet(), "-D", fee, "-k", PROPOSER.getPrivateKey(), NAME),
			node);
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

	@Example
	public void refusesAKeyThatIsNoBoardKeyThroughTheTree() {
		final HedgehogCli.Result result = setThroughTheTree("0.001");

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("not one of the current board keys"));
		assertThat(result.err(), containsString("Usage"));
		result.assertNoStackTrace();
	}

	@Example
	public void refusesToRunUnderAnUnknownCommand() {
		final CommandSpec unknown = CommandSpec.create().addSubcommand(NAME, new CommandLine(new LedgerFee()));

		assertThrows(UnsupportedOperationException.class, () -> subcommand(unknown, NAME).run());
	}
}
