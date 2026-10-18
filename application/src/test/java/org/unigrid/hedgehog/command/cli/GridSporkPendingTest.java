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
import jakarta.ws.rs.core.Response;
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
import static org.unigrid.hedgehog.command.cli.SporkCommands.tree;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class GridSporkPendingTest {
	@Provide
	public Arbitrary<String> listings() {
		return SporkCommands.reports();
	}

	@Property(tries = 20)
	public void getsAndPrintsTheSporksAwaitingACosignature(@ForAll("listings") String pending) {
		final Result result = RestCommandFixture.run(new GridSporkPending(), Response.ok(pending).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.GET, "/gridspork/pending",
			Optional.empty(), Optional.empty()))));

		assertThat(tree(result.out()), equalTo(tree(pending)));
		assertThat(result.err(), equalTo(""));
	}

	@Example
	public void saysSoWhenNothingAwaitsACosignature() {
		final Result result = RestCommandFixture.run(new GridSporkPending(), Response.noContent().build());

		assertThat(result.out().lines().toList(), equalTo(List.of("No sporks await a co-signature")));
	}
}
