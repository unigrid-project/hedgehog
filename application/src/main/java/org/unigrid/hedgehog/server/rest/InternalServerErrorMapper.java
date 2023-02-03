/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.io.PrintWriter;
import java.io.StringWriter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Provider
public class InternalServerErrorMapper implements ExceptionMapper<Exception> {
	@Override
	public Response toResponse(Exception exception) {
		final ObjectNode json = new ObjectMapper().createObjectNode();
		final StringWriter exceptionMessage = new StringWriter();

		json.put("error", exception.getMessage());

		log.atWarn().log(() -> {
			exception.printStackTrace(new PrintWriter(exceptionMessage));

			return String.format("Failed execute server code, %s, %s",
				exception.getMessage(),
				exceptionMessage
			);
		});

		return Response.status(Response.Status.BAD_REQUEST).entity(json.toPrettyString()).build();
	}

}
