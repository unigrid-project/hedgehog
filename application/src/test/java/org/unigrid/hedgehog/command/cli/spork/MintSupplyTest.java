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
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
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
public class MintSupplyTest {
	private static final String NAME = "mint-supply";
	private static final String LOCATION = "/gridspork/mint-supply";

	@Provide
	public Arbitrary<String> keys() {
		return SporkCommands.keys();
	}

	@Provide
	public Arbitrary<String> supplies() {
		return SporkCommands.amounts();
	}

	@Provide
	public Arbitrary<String> sporks() {
		return SporkCommands.reports();
	}

	private static Runnable set(String supply, String key, boolean optionsLast) {
		return optionsLast ? subcommand(new GridSporkSet(), NAME, "-D", supply, "-k", key)
			: subcommand(new GridSporkSet(), "--data", supply, "--key", key, NAME);
	}

	@Property(tries = 10)
	public void getsAndPrintsTheMintSupply(@ForAll("sporks") String spork) {
		final Result result = RestCommandFixture.run(subcommand(new GridSporkGet(), NAME), Response.ok(spork).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.GET, LOCATION, Optional.empty(),
			Optional.empty()))));

		assertThat(tree(result.out()), equalTo(tree(spork)));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 30)
	public void putsTheSupplySignedWithTheKeyAndPrintsTheSpork(@ForAll("keys") String key,
		@ForAll("supplies") String supply, @ForAll("sporks") String spork, @ForAll Status status,
		@ForAll boolean optionsLast) {

		Assume.that(status != Status.UNAUTHORIZED);

		final Result result = RestCommandFixture.run(set(supply, key, optionsLast), Response.status(status)
			.entity(spork).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.PUT, LOCATION,
			Optional.of(Entity.text(supply)), signedBy(key)))));

		assertThat(tree(result.out()), equalTo(tree(spork)));
		assertThat(result.err(), equalTo(""));
	}

	@Example
	public void reportsARejectedKey() {
		final Result result = RestCommandFixture.run(set("1", "key", false), Response.status(Status.UNAUTHORIZED)
			.build());

		assertThat(result.out().lines().toList(), equalTo(List.of(Status.UNAUTHORIZED.getReasonPhrase())));
	}

	@Example
	public void requiresASupplyAndAKeyToSet() {
		assertThrows(CommandLine.MissingParameterException.class,
			() -> subcommand(new GridSporkSet(), "-k", "key", NAME));

		assertThrows(CommandLine.MissingParameterException.class,
			() -> subcommand(new GridSporkSet(), "-D", "1", NAME));
	}

	@Example
	public void refusesToRunUnderAnUnknownCommand() {
		final CommandSpec unknown = CommandSpec.create().addSubcommand(NAME, new CommandLine(new MintSupply()));

		assertThrows(UnsupportedOperationException.class, () -> subcommand(unknown, NAME).run());
	}
}
