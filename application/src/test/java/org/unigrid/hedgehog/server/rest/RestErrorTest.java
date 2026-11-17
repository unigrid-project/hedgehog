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
import static org.hamcrest.Matchers.equalTo;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/** A refusal carries its status and its reason in the one shape every client reads. */
public class RestErrorTest {
	/** The status is kept as given and the reason is the only field of the body. */
	@Property(tries = 20)
	public void shouldAnswerWithTheStatusAndTheReasonAlone(@ForAll Response.Status status, @ForAll String reason) {
		final Response response = RestError.of(status, reason);

		assertThat(response.getStatus(), equalTo(status.getStatusCode()));
		assertThat(response.getEntity(), equalTo(Map.of(RestError.FIELD, reason)));
	}
}
