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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.SneakyThrows;
import mockit.Expectations;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.cli.GridSporkSet;
import org.unigrid.hedgehog.command.option.RestOptions;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.CommunicationSpork;
import org.unigrid.hedgehog.model.spork.CommunicationSpork.SporkData;
import org.unigrid.hedgehog.model.spork.CommunicationSporkTest;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.server.rest.BaseRestClientTest;
import picocli.CommandLine;
import picocli.CommandLine.Command;

public class CommunicationTest extends BaseRestClientTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Command(name = "unrelated", subcommands = Communication.class)
	private static class UnrelatedParent {
		/* Empty on purpose */
	}

	private record Outcome(int exitCode, String stdout) { }

	@Inject
	private SporkDatabase sporkDatabase;

	@Provide
	public Arbitrary<SporkData> validData() {
		return CommunicationSporkTest.sporkDataAcrossTheBounds().filter(CommunicationSporkTest::accepts);
	}

	private static Object sporkCommand(String parent) {
		return new CommandLine(Hedgehog.class).getSubcommands().get("cli").getSubcommands().get(parent)
			.getSubcommands().get("communication").getCommand();
	}

	/* The options recorded for the base class can name another port than the one this server bound */
	private void aimTheCommandAtTheServer() {
		new Expectations() {{
			RestOptions.getHost(); result = server.getRest().getHostName();
			RestOptions.getPort(); result = server.getRest().getPort();
		}};
	}

	private Outcome execute(final String... arguments) {
		final PrintStream original = System.out;
		final ByteArrayOutputStream captured = new ByteArrayOutputStream();

		aimTheCommandAtTheServer();
		System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));

		try {
			final int exitCode = new CommandLine(Hedgehog.class).execute(arguments);
			return new Outcome(exitCode, captured.toString(StandardCharsets.UTF_8));
		} finally {
			System.setOut(original);
		}
	}

	@SneakyThrows
	private static String json(final SporkData data) {
		return MAPPER.writeValueAsString(data);
	}

	private Optional<SporkData> storedData() {
		return Optional.ofNullable(sporkDatabase.getCommunicationSpork()).map(CommunicationSpork::getData);
	}

	@Example
	public void isOfferedByGridSporkGetAndSet() {
		assertThat(sporkCommand("gridspork-get"), instanceOf(Communication.class));
		assertThat(sporkCommand("gridspork-set"), instanceOf(Communication.class));
	}

	@Example
	public void takesTheSporkDataAndKeyAfterTheSubcommand() {
		new CommandLine(Hedgehog.class).parseArgs("cli", "gridspork-set", "communication", "-D", "{}", "-k", "ab");

		assertThat(GridSporkSet.getData(), equalTo("{}"));
		assertThat(GridSporkSet.getKey(), equalTo("ab"));
	}

	@Property(tries = 3)
	public void setsTheSporkWithAPutCarryingTheKeyHeaderAndTheDataBody(
		@ForAll("provideSignature") final Signature signature, @ForAll("validData") final SporkData data) {

		sporkDatabase.setCommunicationSpork(null);

		final Outcome outcome = execute("cli", "gridspork-set", "communication", "-D", json(data),
			"-k", signature.getPrivateKey());

		assertThat(outcome.exitCode(), equalTo(0));
		assertThat(outcome.stdout(), containsString("OK"));
		assertThat(storedData(), equalTo(Optional.of(data)));
	}

	@SneakyThrows
	@Property(tries = 3)
	public void getsTheStoredSporkAsJson(@ForAll("validData") final SporkData data) {
		final CommunicationSpork spork = new CommunicationSpork();

		spork.setData(data);
		sporkDatabase.setCommunicationSpork(spork);

		final Outcome outcome = execute("cli", "gridspork-get", "communication");

		assertThat(outcome.exitCode(), equalTo(0));
		assertThat(MAPPER.readTree(outcome.stdout()).path("data"), equalTo(MAPPER.readTree(json(data))));
	}

	@Example
	public void refusesToRunUnderAnyOtherParentCommand() {
		final Runnable communication = (Runnable) new CommandLine(new UnrelatedParent()).getSubcommands()
			.get("communication").getCommand();

		assertThrows(UnsupportedOperationException.class, communication::run);
	}
}
