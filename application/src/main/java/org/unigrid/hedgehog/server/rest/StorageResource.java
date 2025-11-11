/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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

import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.storage.crypto.Fingerprint;
import org.unigrid.hedgehog.service.storage.DataLossException;
import org.unigrid.hedgehog.service.storage.FingerprintNotFoundException;
import org.unigrid.hedgehog.service.storage.InsufficientGridnodesException;
import org.unigrid.hedgehog.service.storage.Retrieval;
import org.unigrid.hedgehog.service.storage.StorageDisabledException;
import org.unigrid.hedgehog.service.storage.StorageException;
import org.unigrid.hedgehog.service.storage.StorageService;

/* The fingerprint is the only key to a file, so it travels in a header rather than the path where access logs
   and proxies would record it. */
@Slf4j
@Path("/storage")
public class StorageResource extends CDIBridgeResource {
	public static final String FINGERPRINT_HEADER = "X-Fingerprint";

	@CDIBridgeInject
	private StorageService storageService;

	private interface StorageCall {
		Response call() throws IOException, StorageException;
	}

	@POST
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	@Produces(MediaType.APPLICATION_JSON)
	public Response store(final InputStream body) {
		return respond(() -> Response.status(Response.Status.CREATED)
			.entity(Map.of("fingerprint", storageService.store(body).encode())).build());
	}

	@GET
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response retrieve(@NotNull @HeaderParam(FINGERPRINT_HEADER) final String fingerprint) {
		return respond(() -> {
			final Retrieval retrieval = storageService.open(Fingerprint.parse(fingerprint));
			final StreamingOutput stream = output -> write(retrieval, output);

			return Response.ok(stream).header(HttpHeaders.CONTENT_LENGTH, retrieval.size()).build();
		});
	}

	@DELETE
	public Response delete(@NotNull @HeaderParam(FINGERPRINT_HEADER) final String fingerprint) {
		return respond(() -> {
			storageService.delete(Fingerprint.parse(fingerprint));
			return Response.noContent().build();
		});
	}

	/* Stripes are recovered while the body streams, after the status and Content-Length have gone out. A stripe
	   lost at that point can only cut the body short, so clients compare what they received with the length. */
	private static void write(final Retrieval retrieval, final OutputStream output) throws IOException {
		try {
			retrieval.writeTo(output);
		} catch (StorageException ex) {
			throw new IOException(ex.getMessage(), ex);
		}
	}

	private static Response respond(final StorageCall call) {
		try {
			return call.call();
		} catch (StorageDisabledException | InsufficientGridnodesException ex) {
			return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(ex.getMessage()).build();
		} catch (FingerprintNotFoundException ex) {
			return Response.status(Response.Status.NOT_FOUND).build();
		} catch (DataLossException ex) {
			return Response.status(Response.Status.GONE).entity(ex.getMessage()).build();
		} catch (IllegalArgumentException ex) {
			return Response.status(Response.Status.BAD_REQUEST).entity("Malformed fingerprint").build();
		} catch (StorageException | IOException ex) {
			log.atWarn().log("Storage request failed: {}", ex.getMessage());
			return Response.serverError().build();
		}
	}
}
