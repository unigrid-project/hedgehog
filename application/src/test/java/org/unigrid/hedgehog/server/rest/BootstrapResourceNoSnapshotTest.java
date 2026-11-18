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

package org.unigrid.hedgehog.server.rest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.model.bootstrap.BootstrapSnapshot;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;

/**
 * The holder endpoints while the node holds no snapshot. A class of its own, as a mock made here lasts until the end of
 * the class, and the snapshot of the other bootstrap tests must stay readable.
 */
public class BootstrapResourceNoSnapshotTest extends BaseRestClientTest {
	/** Stands in for a missing snapshot, as one that was opened stays open after its file is gone. */
	@BeforeTry
	public void holdNoSnapshot() {
		new MockUp<BootstrapSnapshot>() {
			@Mock public Optional<SnapshotReader> getReader() {
				return Optional.empty();
			}
		};
	}

	/** A list of addresses gets a bare 503 too. */
	@Example
	public void shouldAnswerALookUpOfManyAddressesWith503() {
		try {
			client.post("/bootstrap/addresses", Entity.json(List.of("H8")));
			throw new AssertionError("A list of addresses was answered without a snapshot");

		} catch (ResponseOddityException expected) {
			assertThat(expected.getMessage(), startsWith(
				String.valueOf(Response.Status.SERVICE_UNAVAILABLE.getStatusCode())));
		}
	}

	/** Both holder endpoints answer a bare 503, as the other bootstrap endpoints do without a snapshot. */
	@Example
	public void shouldAnswerBothHolderEndpointsWith503() {
		for (final String location : List.of("/bootstrap/holders/0", "/bootstrap/holders")) {
			try {
				client.get(location);
				throw new AssertionError(location + " answered without a snapshot");

			} catch (ResponseOddityException expected) {
				assertThat(location, expected.getMessage(), startsWith(
					String.valueOf(Response.Status.SERVICE_UNAVAILABLE.getStatusCode())));
			}
		}
	}
}
