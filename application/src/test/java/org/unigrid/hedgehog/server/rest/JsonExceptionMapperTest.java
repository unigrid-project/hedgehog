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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class JsonExceptionMapperTest {
	private static final String MESSAGE = "Cannot deserialize value of type `long` from String \"x\"";

	@Example
	@SneakyThrows
	public void shouldAnswerABadRequestThatNamesTheErrorAndNothingOfTheStack() {
		final Response response = new JsonExceptionMapper().toResponse(
			JsonMappingException.from((JsonParser) null, MESSAGE));
		final String entity = (String) response.getEntity();
		final JsonNode json = new ObjectMapper().readTree(entity);

		assertThat(response.getStatus(), equalTo(Response.Status.BAD_REQUEST.getStatusCode()));
		assertThat(json.get("error").asText(), equalTo(MESSAGE));
		assertThat(entity, not(containsString("\tat ")));
		assertThat(entity, not(containsString(JsonMappingException.class.getName())));
	}
}
