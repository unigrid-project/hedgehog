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
import java.util.List;
import java.util.Optional;
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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.command.cli.SporkCommands;
import static org.unigrid.hedgehog.command.cli.SporkCommands.PROPOSER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.json;
import static org.unigrid.hedgehog.command.cli.SporkCommands.proposed;
import static org.unigrid.hedgehog.command.cli.SporkCommands.stored;
import static org.unigrid.hedgehog.command.cli.SporkCommands.subcommand;
import static org.unigrid.hedgehog.command.cli.SporkCommands.tree;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.StorageSpork.SporkData;
import org.unigrid.hedgehog.model.spork.StorageSporkTest;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class StorageTest {
	private static final String NAME = "storage";
	private static final String LOCATION = "/gridspork/storage";

	@Provide
	public Arbitrary<String> sporks() {
		return SporkCommands.reports();
	}

	@Provide
	public Arbitrary<SporkData> validData() {
		return StorageSporkTest.sporkDataAcrossTheBounds().filter(StorageSporkTest::accepts);
	}

	@Provide
	public Arbitrary<SporkData> invalidData() {
		return StorageSporkTest.sporkDataAcrossTheBounds().filter(data -> !StorageSporkTest.accepts(data));
	}

	@BeforeTry
	public void fakeTheBoard() {
		SporkCommands.fakeTheBoard();
	}

	/* A node that holds no storage spork yet takes the first version of it */
	private static Runnable set(String data) {
		return subcommand(new GridSporkSet(), "-D", data, "-k", PROPOSER.getPrivateKey(), NAME, "--first-version");
	}

	@Property(tries = 10)
	public void getsAndPrintsTheStorageSpork(@ForAll("sporks") String spork) {
		final Result result = RestCommandFixture.run(subcommand(new GridSporkGet(), NAME), Response.ok(spork).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.GET, LOCATION, Optional.empty(),
			Optional.empty()))));

		assertThat(tree(result.out()), equalTo(tree(spork)));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 10)
	public void proposesTheParametersOfTheJsonSignedHereWithTheKey(@ForAll("validData") SporkData data) {
		final GridSpork stored = stored(Type.STORAGE, new SporkData());
		final FakeSporkNode node = new FakeSporkNode().holding(stored);
		final GridSpork proposal = proposed(RestCommandFixture.run(set(json(data)), node), node, Type.STORAGE);

		assertThat(proposal.getData(), equalTo(data));
		assertThat(proposal.getPreviousData(), equalTo(stored.getData()));
	}

	/* What the JSON leaves out is what a spork holds unless it is told otherwise */
	@Example
	public void takesTheDefaultOfWhatTheJsonLeavesOut() {
		final FakeSporkNode node = new FakeSporkNode();
		final SporkData expected = new SporkData();

		expected.setManifestCopies(5);
		assertThat(proposed(RestCommandFixture.run(set("{\"manifestCopies\":5}"), node), node, Type.STORAGE)
			.getData(), equalTo(expected));
	}

	@Property(tries = 10)
	public void refusesParametersThatNoNodeCanStoreBy(@ForAll("invalidData") SporkData data) {
		final FakeSporkNode node = new FakeSporkNode();

		assertThrows(CommandLine.ParameterException.class, () -> RestCommandFixture.run(set(json(data)), node));
		assertThat(node.submitted(), empty());
	}

	@Example
	public void refusesWhatIsNoJsonOfTheParameters() {
		for (String data : List.of("", "{", "[]", "{\"manifestCopies\":\"many\"}")) {
			assertThat(data, assertThrows(CommandLine.ParameterException.class,
				() -> set(data).run()).getMessage(), containsString("storage parameters"));
		}
	}

	@Example
	public void refusesToRunUnderAnUnknownCommand() {
		final CommandSpec unknown = CommandSpec.create().addSubcommand(NAME, new CommandLine(new Storage()));

		assertThrows(UnsupportedOperationException.class, () -> subcommand(unknown, NAME).run());
	}
}
