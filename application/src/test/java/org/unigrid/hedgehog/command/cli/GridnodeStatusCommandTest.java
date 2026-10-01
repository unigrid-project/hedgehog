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

import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

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
}
