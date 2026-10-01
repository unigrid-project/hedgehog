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
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class GridnodeStatusCommandTest {
	private static String[] outputOf(GridnodeStatusCommand command, Response response) {
		final PrintStream out = System.out;
		final PrintStream err = System.err;
		final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

		try {
			System.setOut(new PrintStream(stdout, true));
			System.setErr(new PrintStream(stderr, true));
			command.execute(response);
		} finally {
			System.setOut(out);
			System.setErr(err);
		}

		return new String[] { stdout.toString(), stderr.toString() };
	}

	@Example
	public void shouldTellWhenTheRequestWasAccepted() {
		final String[] output = outputOf(new GridnodeStart(), Response.accepted().build());

		assertThat(output[0], containsString("Accepted"));
		assertThat(output[1], is(""));
	}

	@Example
	public void shouldExplainAConflictOnStartAndStop() {
		for (GridnodeStatusCommand command : new GridnodeStatusCommand[] { new GridnodeStart(), new GridnodeStop() }) {
			final String[] output = outputOf(command, Response.status(Response.Status.CONFLICT).build());

			assertThat(output[0], is(""));
			assertThat(output[1], containsString("-G"));
		}
	}

	@Property(tries = 30)
	public void putsAnEmptyTextAndPrintsTheStatusUnlessThereIsNoGridnode(@ForAll boolean start,
		@ForAll Response.Status status) {

		Assume.that(status != Response.Status.CONFLICT);

		final GridnodeStatusCommand command = start ? new GridnodeStart() : new GridnodeStop();
		final String location = start ? "/gridnode/start" : "/gridnode/stop";
		final Result result = RestCommandFixture.run(command, Response.status(status).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.PUT, location,
			Optional.of(Entity.text("")), Optional.empty()))));

		assertThat(result.out().lines().toList(), equalTo(List.of(status.getReasonPhrase())));
		assertThat(result.err(), is(""));
	}
}
