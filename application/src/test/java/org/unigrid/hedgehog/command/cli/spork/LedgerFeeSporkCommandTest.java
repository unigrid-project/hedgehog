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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.cli.SporkCommands;
import static org.unigrid.hedgehog.command.cli.SporkCommands.signedBy;
import static org.unigrid.hedgehog.command.cli.SporkCommands.subcommand;
import static org.unigrid.hedgehog.command.cli.SporkCommands.tree;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.spork.LedgerFeeSporkTest;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerFeeSporkCommandTest {
	private static final String NAME = "ledger-fee";
	private static final String LOCATION = "/gridspork/ledger-fee";

	@Provide
	public Arbitrary<String> keys() {
		return SporkCommands.keys();
	}

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

	private static Runnable set(String key, String fee) {
		return subcommand(new GridSporkSet(), "-D", fee, "-k", key, NAME);
	}

	/* The long form takes any value, a negative one among them */
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

	@Property(tries = 40)
	public void putsTheFeeExactlyAsTypedSignedWithTheKeyAndPrintsTheStatus(@ForAll("keys") String key,
		@ForAll("acceptedTexts") String text, @ForAll Status status) {

		final Result result = RestCommandFixture.run(set(key, text), Response.status(status).build());
		final Request request = result.request().orElseThrow();

		assertThat(request.method(), equalTo(HttpMethod.PUT));
		assertThat(request.location(), equalTo(LOCATION));
		assertThat(request.entity().orElseThrow().getMediaType(), equalTo(MediaType.APPLICATION_JSON_TYPE));
		assertThat((Object) request.entity().orElseThrow().getEntity(),
			equalTo(LedgerFeeSporkTest.with(new BigDecimal(text))));
		assertThat(request.headers(), equalTo(signedBy(key)));
		assertThat(result.out().lines().toList(), equalTo(List.of(status.getReasonPhrase())));
		assertThat(result.err(), equalTo(""));
	}

	@Example
	public void readsADecimalNumberOfCoinsWithoutFloatingPoint() {
		final Result result = RestCommandFixture.run(set("0".repeat(64), "0.1"), Response.ok().build());

		assertThat((Object) result.request().orElseThrow().entity().orElseThrow().getEntity(),
			equalTo(LedgerFeeSporkTest.with(new BigDecimal("0.1"))));
	}

	@Property(tries = 40)
	public void namesTheFeeThatTheSporkDoesNotAccept(@ForAll("refusedTexts") String text) {
		final HedgehogCli.Result result = setThroughTheTree(text);

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("The fee \"" + text + "\" is not accepted"));
		assertThat(result.out(), emptyString());
		result.assertNoStackTrace();
	}

	@Property(tries = 40)
	public void namesTheTextThatIsNoNumber(@ForAll("notNumbers") String text) {
		final HedgehogCli.Result result = setThroughTheTree(text);

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("The fee \"" + text + "\" is not accepted"));
		assertThat(result.out(), emptyString());
		result.assertNoStackTrace();
	}

	@Example
	public void saysWhyAFeeOfNineDecimalsIsRefused() {
		final HedgehogCli.Result result = setThroughTheTree("0.000000001");

		assertThat(result.err(), containsString("at most 8 decimals"));
	}

	@Example
	public void refusesToRunUnderAnUnknownCommand() {
		final CommandSpec unknown = CommandSpec.create().addSubcommand(NAME, new CommandLine(new LedgerFee()));

		assertThrows(UnsupportedOperationException.class, () -> subcommand(unknown, NAME).run());
	}
}
