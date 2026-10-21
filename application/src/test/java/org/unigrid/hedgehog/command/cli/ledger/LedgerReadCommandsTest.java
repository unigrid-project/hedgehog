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

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.ACCOUNT;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.INFO;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.MAPPER;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.block;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.errorBody;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.server.rest.RestToken;
import org.unigrid.hedgehog.server.rest.entity.LedgerBlock;
import org.unigrid.hedgehog.server.rest.entity.LedgerTransactionView;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerReadCommandsTest {
	private static final String ACCOUNT_HEX = ACCOUNT.address();
	private static final String NO_TOKEN_FILE = "/home/holder/rest.token";
	private static final String LOCAL_HOST = "127.0.0.1";

	private static final LedgerBlock BLOCK = block(List.of(LedgerTransactionView.builder()
		.id("ff".repeat(64)).kind("mint").recipient(ACCOUNT_HEX).amount("10.00000000").build()));

	private record Outcome(Result sent, HedgehogCli.Result cli) { }

	@Provide
	public Arbitrary<String> typedAccounts() {
		return Arbitraries.strings().withChars("0123456789abcdefABCDEF").ofLength(64);
	}

	@Provide
	public Arbitrary<String> legacyAddresses() {
		return Arbitraries.bytes().array(byte[].class).ofSize(20).map(LegacyAddress::encode);
	}

	@Provide
	public Arbitrary<List<String>> readCommands() {
		return Arbitraries.of(List.of("info"), List.of("balance", ACCOUNT_HEX), List.of("block", "5"));
	}

	@Provide
	public Arbitrary<Status> refusedStatuses() {
		return Arbitraries.of(Status.BAD_REQUEST, Status.SERVICE_UNAVAILABLE, Status.TOO_MANY_REQUESTS,
			Status.GATEWAY_TIMEOUT);
	}

	@Provide
	public Arbitrary<String> errorTexts() {
		return Arbitraries.strings().alpha().numeric().withChars(' ', '.', ':', '\'').ofMinLength(1).ofMaxLength(60);
	}

	private static String[] ledger(final List<String> command) {
		return Stream.concat(Stream.of("cli", "ledger"), command.stream()).toArray(String[]::new);
	}

	private static String[] ledger(final String... command) {
		return ledger(List.of(command));
	}

	private static Outcome run(final Function<Runnable, Result> fixture, final String... arguments) {
		final AtomicReference<HedgehogCli.Result> cli = new AtomicReference<>();
		final Result sent = fixture.apply(() -> cli.set(HedgehogCli.run(arguments)));

		return new Outcome(sent, cli.get());
	}

	private static Outcome answered(final Response canned, final String... arguments) {
		return run(command -> RestCommandFixture.run(command, canned), arguments);
	}

	private static Outcome thrown(final ResponseOddityException oddity, final String... arguments) {
		return run(command -> RestCommandFixture.runWithOddity(command, oddity), arguments);
	}

	private static Request read(final String location) {
		return new Request(HttpMethod.GET, location, Optional.empty(), Optional.empty());
	}

	@SneakyThrows
	private static JsonNode tree(final Object value) {
		return MAPPER.readTree(value instanceof String text ? text : MAPPER.writeValueAsString(value));
	}

	private static void assertAnswered(final Outcome outcome, final String location, final Object printed) {
		assertThat(outcome.sent().request(), equalTo(Optional.of(read(location))));
		assertThat(outcome.cli().exitCode(), equalTo(0));
		assertThat(tree(outcome.cli().out()), equalTo(tree(printed)));
		assertThat(outcome.cli().out().lines().count(), greaterThan(1L));
		assertThat(outcome.cli().err(), emptyString());
	}

	private static void assertFailedWith(final Outcome outcome, final String line) {
		assertThat(outcome.cli().exitCode(), equalTo(1));
		assertThat(outcome.cli().out(), emptyString());
		assertThat(outcome.cli().err().lines().toList(), equalTo(List.of(line)));
		outcome.cli().assertNoStackTrace();
	}

	private static void assertRefusedBeforeSending(final Outcome outcome, final String named) {
		assertThat(outcome.sent().request(), equalTo(Optional.empty()));
		assertThat(outcome.sent().connection(), equalTo(Optional.empty()));
		assertThat(outcome.cli().exitCode(), equalTo(2));
		assertThat(outcome.cli().out(), emptyString());
		assertThat(outcome.cli().err(), containsString("\"" + named + "\""));
		outcome.cli().assertNoStackTrace();
	}

	@Example
	public void readsTheStatusOfTheNodeAndPrintsItAsJson() {
		assertAnswered(answered(Response.ok(INFO).build(), ledger("info")), "/ledger", INFO);
	}

	@Property(tries = 30)
	public void asksForTheAccountInLowerCaseWhateverCaseItWasTyped(@ForAll("typedAccounts") String typed) {
		assertAnswered(answered(Response.ok(ACCOUNT).build(), ledger("balance", typed)),
			"/ledger/account/" + typed.toLowerCase(Locale.ROOT), ACCOUNT);
	}

	@Property(tries = 30)
	public void asksForALegacyAddressExactlyAsTyped(@ForAll("legacyAddresses") String typed) {
		assertAnswered(answered(Response.ok(ACCOUNT).build(), ledger("balance", typed)),
			"/ledger/account/" + typed, ACCOUNT);
	}

	@Property(tries = 30)
	public void asksForTheBlockAtTheHeight(@ForAll @LongRange(min = 1) long height) {
		assertAnswered(answered(Response.ok(BLOCK).build(), ledger("block", String.valueOf(height))),
			"/ledger/block/" + height, BLOCK);
	}

	@Example
	public void refusesWhatIsNeitherAnAccountNorALegacyAddressWithoutSendingAnything() {
		final String legacy = LegacyAddress.encode(new byte[20]);
		final String badChecksum = legacy.substring(0, legacy.length() - 1) + (legacy.endsWith("2") ? "3" : "2");

		for (final String typed : List.of("", "nonsense", "0".repeat(63), "0".repeat(65), "g".repeat(64),
			"0x" + "0".repeat(62), badChecksum)) {

			assertRefusedBeforeSending(answered(Response.ok(ACCOUNT).build(), ledger("balance", typed)), typed);
		}
	}

	@Example
	public void refusesWhatIsNoHeightFromOneWithoutSendingAnything() {
		for (final String typed : List.of("0", "-1", "abc", "1.5", "0x1", "1e3", "", " 1", "99999999999999999999",
			"-9223372036854775809")) {

			assertRefusedBeforeSending(answered(Response.ok(BLOCK).build(), ledger("block", "--", typed)), typed);
		}
	}

	@Property(tries = 40)
	public void printsTheErrorTextOfAnAnswerThatTheClientRefusesToReturn(@ForAll("readCommands") List<String> command,
		@ForAll("refusedStatuses") Status status, @ForAll("errorTexts") String text) {

		assertFailedWith(thrown(new ResponseOddityException(status, errorBody(text)), ledger(command)), text);
	}

	@Property(tries = 40)
	public void printsTheErrorTextOfAnAnswerThatTheClientReturns(@ForAll("readCommands") List<String> command,
		@ForAll("errorTexts") String text) {

		final Outcome outcome = answered(Response.status(Status.NOT_FOUND).entity(errorBody(text)).build(),
			ledger(command));

		assertFailedWith(outcome, text);
	}

	@Example
	public void printsTheStatusWhenTheBodyHasNoErrorText() {
		final String status = new ResponseOddityException(Status.SERVICE_UNAVAILABLE).getMessage();

		for (final String body : List.of("", "<html>Bad gateway</html>", "{}", "{\"error\":null}", "[]", "\"text\"")) {
			assertFailedWith(thrown(new ResponseOddityException(Status.SERVICE_UNAVAILABLE, body), ledger("info")),
				status);
		}

		assertFailedWith(answered(Response.status(Status.NOT_FOUND).entity("not json").build(), ledger("block", "5")),
			new ResponseOddityException(Status.NOT_FOUND).getMessage());
	}

	@Property(tries = 10)
	public void printsTheStatusOfAnAnswerWithoutBody(@ForAll("readCommands") List<String> command) {
		final ResponseOddityException rejected = new ResponseOddityException(Status.UNAUTHORIZED);

		assertFailedWith(thrown(rejected, ledger(command)), rejected.getMessage());
	}

	@Property(tries = 10)
	public void pointsAtTheMissingTokenFileWithoutSendingAnything(@ForAll("readCommands") List<String> command) {
		final Outcome outcome = run(read -> RestCommandFixture.runWithoutToken(read,
			new NoSuchFileException(NO_TOKEN_FILE)), ledger(command));

		assertThat(outcome.sent().request(), equalTo(Optional.empty()));
		assertFailedWith(outcome, "No REST token in " + NO_TOKEN_FILE + "; is the daemon running? "
			+ "Otherwise pass --resttoken.");
	}

	@Example
	public void reportsATokenThatCannotBeRead() {
		final Outcome outcome = run(read -> RestCommandFixture.runWithoutToken(read, new IOException("Unreadable")),
			ledger("info"));

		assertFailedWith(outcome, "Unreadable");
	}

	/* Nothing listens on the port, so the connection is refused as it is when the daemon is not running */
	@Property(tries = 5)
	public void saysInOneLineThatTheNodeCannotBeReached(@ForAll("readCommands") List<String> command)
		throws IOException {

		final int port = unusedPort();

		new MockUp<RestOptions>() {
			@Mock public String getHost() {
				return LOCAL_HOST;
			}

			@Mock public int getPort() {
				return port;
			}
		};

		new MockUp<RestToken>() {
			@Mock public String resolve() {
				return "token";
			}
		};

		final HedgehogCli.Result cli = HedgehogCli.run(ledger(command));

		assertThat(cli.exitCode(), equalTo(1));
		assertThat(cli.err().lines().toList().size(), equalTo(1));
		assertThat(cli.err(), startsWith("Could not reach the node at " + LOCAL_HOST + ":" + port + ": "));
		assertThat(cli.err(), containsString("Connect"));
		cli.assertNoStackTrace();
	}

	private static int unusedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	@Example
	public void describesEachCommandInItsHelp() {
		final Map<String, String> shown = Map.of("info", "the ledger of the node", "balance", "<address>",
			"block", "<height>");

		shown.forEach((name, text) -> {
			final HedgehogCli.Result help = HedgehogCli.run(ledger(name, "--help"));

			assertThat(help.exitCode(), equalTo(0));
			assertThat(help.out(), containsString("cli ledger " + name));
			assertThat(help.out(), containsString(text));
			assertThat(help.err(), emptyString());
		});
	}
}
