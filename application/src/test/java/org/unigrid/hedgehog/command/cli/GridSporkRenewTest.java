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

package org.unigrid.hedgehog.command.cli;

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
import static org.unigrid.hedgehog.command.cli.SporkCommands.signedBy;
import static org.unigrid.hedgehog.command.cli.SporkCommands.tree;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import picocli.CommandLine;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class GridSporkRenewTest {
	@Provide
	public Arbitrary<String> keys() {
		return SporkCommands.keys();
	}

	@Provide
	public Arbitrary<String> proposals() {
		return SporkCommands.reports();
	}

	private static GridSporkRenew renew(String key) {
		final GridSporkRenew command = new GridSporkRenew();

		new CommandLine(command).parseArgs("--key", key);
		return command;
	}

	@Property(tries = 30)
	public void putsAKeySignedRenewalAndPrintsTheProposals(@ForAll("keys") String key, @ForAll("proposals") String proposals,
		@ForAll Status status) {

		Assume.that(status != Status.UNAUTHORIZED);

		final Result result = RestCommandFixture.run(renew(key), Response.status(status).entity(proposals).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.PUT, "/gridspork/renew",
			Optional.of(Entity.text("")), signedBy(key)))));

		assertThat(tree(result.out()), equalTo(tree(proposals)));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 10)
	public void saysSoWhenNothingCameBackToRenew(@ForAll Status status) {
		Assume.that(status != Status.UNAUTHORIZED);

		final Result result = RestCommandFixture.run(renew("key"), Response.status(status).build());

		assertThat(result.out().lines().toList(), equalTo(List.of("No sporks to renew")));
	}

	@Example
	public void reportsARejectedKey() {
		final Result result = RestCommandFixture.run(renew("key"), Response.status(Status.UNAUTHORIZED).build());

		assertThat(result.out().lines().toList(), equalTo(List.of(Status.UNAUTHORIZED.getReasonPhrase())));
	}

	@Example
	public void requiresAKey() {
		assertThrows(CommandLine.MissingParameterException.class,
			() -> new CommandLine(new GridSporkRenew()).parseArgs());
	}
}
