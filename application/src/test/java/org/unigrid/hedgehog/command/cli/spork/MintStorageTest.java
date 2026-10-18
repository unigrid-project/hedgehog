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
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Positive;
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
public class MintStorageTest {
	private static final String NAME = "mint-storage";
	private static final String LOCATION = "/gridspork/mint-storage";
	private static final int ADDRESS_LENGTH = 40;

	@Provide
	public Arbitrary<String> keys() {
		return SporkCommands.keys();
	}

	@Provide
	public Arbitrary<String> amounts() {
		return SporkCommands.amounts();
	}

	@Provide
	public Arbitrary<String> sporks() {
		return SporkCommands.reports();
	}

	@Provide
	public Arbitrary<String> addresses() {
		return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(ADDRESS_LENGTH);
	}

	private static Runnable grow(String amount, String key, Optional<String> address, Optional<Integer> height) {
		final Stream<String> mint = Stream.concat(address.stream().flatMap(value -> Stream.of("--address", value)),
			height.stream().flatMap(value -> Stream.of("--height", value.toString())));

		return subcommand(new GridSporkGrow(), Stream.concat(Stream.of("-D", amount, "-k", key, NAME), mint)
			.toArray(String[]::new));
	}

	@Property(tries = 10)
	public void getsAndPrintsTheMintStorage(@ForAll("sporks") String spork) {
		final Result result = RestCommandFixture.run(subcommand(new GridSporkGet(), NAME), Response.ok(spork).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.GET, LOCATION, Optional.empty(),
			Optional.empty()))));

		assertThat(tree(result.out()), equalTo(tree(spork)));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 30)
	public void putsTheMintForTheAddressAndHeightSignedWithTheKey(@ForAll("keys") String key,
		@ForAll("amounts") String amount, @ForAll("addresses") String address, @ForAll @Positive int height,
		@ForAll("sporks") String spork, @ForAll Status status) {

		Assume.that(status != Status.UNAUTHORIZED);

		final Result result = RestCommandFixture.run(grow(amount, key, Optional.of(address), Optional.of(height)),
			Response.status(status).entity(spork).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.PUT, LOCATION + "/" + address + "/"
			+ height, Optional.of(Entity.text(amount)), signedBy(key)))));

		assertThat(tree(result.out()), equalTo(tree(spork)));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 10)
	public void sendsNothingWithoutAnAddress(@ForAll("amounts") String amount, @ForAll Optional<@Positive Integer> height) {
		final Result result = RestCommandFixture.run(grow(amount, "key", Optional.empty(), height),
			Response.ok().build());

		assertThat(result.connection(), equalTo(Optional.empty()));
		assertThat(result.out().lines().toList(), equalTo(List.of("Both block height and address have to be specified")));
	}

	@Example
	public void requiresAnAmountAndAKeyToGrow() {
		assertThrows(CommandLine.MissingParameterException.class,
			() -> subcommand(new GridSporkGrow(), "-k", "key", NAME));

		assertThrows(CommandLine.MissingParameterException.class,
			() -> subcommand(new GridSporkGrow(), "-D", "1", NAME));
	}

	@Example
	public void refusesToRunUnderAnUnknownCommand() {
		final CommandSpec unknown = CommandSpec.create().addSubcommand(NAME, new CommandLine(new MintStorage()));

		assertThrows(UnsupportedOperationException.class, () -> subcommand(unknown, NAME).run());
	}
}
