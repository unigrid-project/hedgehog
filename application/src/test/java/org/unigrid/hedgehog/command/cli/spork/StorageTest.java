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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.command.cli.SporkCommands;
import static org.unigrid.hedgehog.command.cli.SporkCommands.signedBy;
import static org.unigrid.hedgehog.command.cli.SporkCommands.subcommand;
import static org.unigrid.hedgehog.command.cli.SporkCommands.tree;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class StorageTest {
	private static final String NAME = "storage";
	private static final String LOCATION = "/gridspork/storage";

	@Provide
	public Arbitrary<String> keys() {
		return SporkCommands.keys();
	}

	@Provide
	public Arbitrary<String> sporks() {
		return SporkCommands.reports();
	}

	@Property(tries = 10)
	public void getsAndPrintsTheStorageSpork(@ForAll("sporks") String spork) {
		final Result result = RestCommandFixture.run(subcommand(new GridSporkGet(), NAME), Response.ok(spork).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.GET, LOCATION, Optional.empty(),
			Optional.empty()))));

		assertThat(tree(result.out()), equalTo(tree(spork)));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 30)
	public void putsTheSporkDataAsJsonSignedWithTheKeyAndPrintsTheStatus(@ForAll("keys") String key,
		@ForAll("sporks") String data, @ForAll Status status) {

		final Result result = RestCommandFixture.run(subcommand(new GridSporkSet(), "-D", data, "-k", key, NAME),
			Response.status(status).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.PUT, LOCATION,
			Optional.of(Entity.entity(data, MediaType.APPLICATION_JSON)), signedBy(key)))));

		assertThat(result.out().lines().toList(), equalTo(List.of(status.getReasonPhrase())));
		assertThat(result.err(), equalTo(""));
	}

	@Example
	public void refusesToRunUnderAnUnknownCommand() {
		final CommandSpec unknown = CommandSpec.create().addSubcommand(NAME, new CommandLine(new Storage()));

		assertThrows(UnsupportedOperationException.class, () -> subcommand(unknown, NAME).run());
	}
}
