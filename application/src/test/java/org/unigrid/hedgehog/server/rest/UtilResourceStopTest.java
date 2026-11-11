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

import jakarta.ws.rs.core.Response.Status;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.model.cdi.CDIContext;

/* The REST server closes every connection when it shuts down, so an answer that is not written by then never reaches
   `hedgehog stop`, which then reports a node that it could not reach although the node stops. The server here shuts
   down the moment the node is told to stop, with nothing in between, so the answer arrives only if the node is told
   no sooner than the answer is written. The server shuts down once, so this is the only example of the class. */
public class UtilResourceStopTest extends BaseRestClientTest {
	@Example
	@SneakyThrows
	public void shouldAnswerTheCallThatStopsTheNodeEvenWhenTheNodeShutsDownAtOnce() {
		new MockUp<CDIContext>() {
			@Mock public /* static */ void stop() {
				server.getRest().destroy();
			}
		};

		assertThat(Status.fromStatusCode(client.post("/stop", null).getStatus()), equalTo(Status.ACCEPTED));
	}
}
