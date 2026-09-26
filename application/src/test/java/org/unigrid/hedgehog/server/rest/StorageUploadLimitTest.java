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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import mockit.Expectations;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.client.ResponseOddityException;
import org.unigrid.hedgehog.command.option.RestOptions;

/* The declared length is refused before the storage service is reached, so no bucket is needed */
public class StorageUploadLimitTest extends BaseRestClientTest {
	private static final int MAX_UPLOAD = 16;

	@BeforeTry
	public void mockMaxUpload() {
		new Expectations() {{
			RestOptions.getMaxUpload(); result = MAX_UPLOAD; minTimes = 0;
		}};
	}

	@Property(tries = 20)
	public void shouldRefuseDeclaredOversizedUpload(@ForAll @Size(min = MAX_UPLOAD + 1, max = 1024) byte[] data) {
		try {
			client.post("/storage-object/bucket/key", Entity.entity(data, MediaType.APPLICATION_OCTET_STREAM));
			assertThat("Unexpected response", false);
		} catch (ResponseOddityException ex) {
			assertThat(ex.getMessage(), startsWith(String.valueOf(Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode())));
		}
	}
}
