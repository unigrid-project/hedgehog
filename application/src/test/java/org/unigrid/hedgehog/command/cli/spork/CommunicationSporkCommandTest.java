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
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import org.unigrid.hedgehog.Hedgehog;
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
import org.unigrid.hedgehog.model.spork.CommunicationSpork.SporkData;
import org.unigrid.hedgehog.model.spork.CommunicationSporkTest;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class CommunicationSporkCommandTest {
	private static final String NAME = "communication";
	private static final String LOCATION = "/gridspork/communication";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Provide
	public Arbitrary<String> sporks() {
		return SporkCommands.reports();
	}

	@Provide
	public Arbitrary<SporkData> validData() {
		return CommunicationSporkTest.sporkDataAcrossTheBounds().filter(CommunicationSporkTest::accepts);
	}

	@Provide
	public Arbitrary<SporkData> invalidData() {
		return CommunicationSporkTest.sporkDataAcrossTheBounds().filter(data -> !CommunicationSporkTest.accepts(data));
	}

	@BeforeTry
	public void fakeTheBoard() {
		SporkCommands.fakeTheBoard();
	}

	@SneakyThrows
	private static String json(final SporkData data) {
		return MAPPER.writeValueAsString(data);
	}

	/* A node that holds no communication spork yet takes the first version of it */
	private static Result set(String data, FakeSporkNode node) {
		return RestCommandFixture.run(subcommand(new GridSporkSet(), "-D", data, "-k", PROPOSER.getPrivateKey(), NAME,
			"--first-version"), node);
	}

	/* The data is judged before the key, so any key will do */
	private static HedgehogCli.Result setThroughTheTree(String data) {
		return HedgehogCli.run("cli", "gridspork-set", "--data=" + data, "-k", "0".repeat(64), NAME);
	}

	@Example
	public void isOfferedByGridSporkGetAndSet() {
		final CommandLine tree = new CommandLine(Hedgehog.class).getSubcommands().get("cli");

		for (final String parent : new String[] { "gridspork-get", "gridspork-set" }) {
			assertThat(tree.getSubcommands().get(parent).getSubcommands().get(NAME).getCommand(),
				instanceOf(Communication.class));
		}
	}

	@Property(tries = 10)
	public void getsAndPrintsTheSpork(@ForAll("sporks") String spork) {
		final Result result = RestCommandFixture.run(subcommand(new GridSporkGet(), NAME), Response.ok(spork).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.GET, LOCATION, Optional.empty(),
			Optional.empty()))));

		assertThat(tree(result.out()), equalTo(tree(spork)));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 10)
	public void proposesTheParametersExactlyAsGiven(@ForAll("validData") SporkData data) {
		final FakeSporkNode node = new FakeSporkNode();
		final SporkData proposed = proposed(set(json(data), node), node, Type.COMMUNICATION).getData();

		assertThat(proposed, equalTo(data));
	}

	@Example
	public void takesAParameterLeftOutAtItsDefault() {
		final FakeSporkNode node = new FakeSporkNode();
		final SporkData proposed = proposed(set("{\"groupSize\":3}", node), node, Type.COMMUNICATION).getData();
		final SporkData expected = new SporkData();

		expected.setGroupSize(3);
		assertThat(proposed, equalTo(expected));
	}

	@Property(tries = 10)
	public void namesTheParametersThatTheSporkDoesNotAccept(@ForAll("invalidData") SporkData data) {
		final HedgehogCli.Result result = setThroughTheTree(json(data));

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("The communication parameters are not accepted"));
		assertThat(result.out(), emptyString());
		result.assertNoStackTrace();
	}

	@Example
	public void namesTheTextThatIsNoJson() {
		final HedgehogCli.Result result = setThroughTheTree("not json");

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("The communication parameters are not accepted"));
		result.assertNoStackTrace();
	}

	@Example
	public void namesAnUnknownParameter() {
		final HedgehogCli.Result result = setThroughTheTree("{\"groupsize\":3}");

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("The communication parameters are not accepted"));
		result.assertNoStackTrace();
	}

	/* A key that the board added since the release is one of them, so a key is judged by the board keys of the node */
	@Example
	public void refusesAKeyThatIsNoBoardKeyOnceItKnowsTheBoardKeysOfTheNode() {
		assertThat(SporkCommands.refusalOnceAsked(subcommand(new GridSporkSet(), "--data={\"groupSize\":8}", "-k",
			SporkCommands.STRANGER.getPrivateKey(), NAME), new FakeSporkNode()),
			containsString("not one of the current board keys"));
	}

	@Example
	public void refusesToRunUnderAnUnknownCommand() {
		final CommandSpec unknown = CommandSpec.create().addSubcommand(NAME, new CommandLine(new Communication()));

		assertThrows(UnsupportedOperationException.class, () -> subcommand(unknown, NAME).run());
	}
}
