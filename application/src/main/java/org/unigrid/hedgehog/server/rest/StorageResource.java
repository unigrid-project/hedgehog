/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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
	public static final String FILE_SIZE_HEADER = "X-File-Size";
	public static final long MAX_UPLOAD_BYTES = 64L << 30;

	@CDIBridgeInject
	private StorageService storageService;

	private interface StorageCall {
		Response call() throws IOException, StorageException;
	}

	private interface FingerprintCall {
		Response call(Fingerprint fingerprint) throws IOException, StorageException;
	}

	/* The container queues a body faster than a store consumes it, so only an upload that announces a length
	   within the cap is read at all */
	@POST
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	@Produces(MediaType.APPLICATION_JSON)
	public Response store(@HeaderParam(HttpHeaders.CONTENT_LENGTH) final Long length, final InputStream body) {
		if (length == null) {
			return Response.status(Response.Status.LENGTH_REQUIRED).build();
		}

		if (length > MAX_UPLOAD_BYTES) {
			return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).build();
		}

		return respond(() -> Response.status(Response.Status.CREATED)
			.entity(Map.of("fingerprint", storageService.store(body).encode())).build());
	}

	/* The size travels in its own header so that the body can stream chunked. A stripe lost after the first one can
	   only end the stream early, so clients compare what arrived with the announced size. */
	@GET
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response retrieve(@NotNull @HeaderParam(FINGERPRINT_HEADER) final String fingerprint) {
		return withFingerprint(fingerprint, parsed -> {
			final Retrieval retrieval = storageService.open(parsed);
			final StreamingOutput stream = output -> write(retrieval, output);

			return Response.ok(stream).header(FILE_SIZE_HEADER, retrieval.size()).build();
		});
	}

	@DELETE
	public Response delete(@NotNull @HeaderParam(FINGERPRINT_HEADER) final String fingerprint) {
		return withFingerprint(fingerprint, parsed -> {
			storageService.delete(parsed);
			return Response.noContent().build();
		});
	}

	private static void write(final Retrieval retrieval, final OutputStream output) throws IOException {
		try {
			retrieval.writeTo(output);
		} catch (StorageException ex) {
			throw new IOException(ex.getMessage(), ex);
		}
	}

	private static Response withFingerprint(final String encoded, final FingerprintCall call) {
		final Fingerprint fingerprint;

		try {
			fingerprint = Fingerprint.parse(encoded);
		} catch (IllegalArgumentException ex) {
			return Response.status(Response.Status.BAD_REQUEST).entity("Malformed fingerprint").build();
		}

		return respond(() -> call.call(fingerprint));
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
		} catch (StorageException | IOException | RuntimeException ex) {
			/* An unexpected exception may carry anything, the fingerprint included, so only its type is logged
			   above trace */
			log.atWarn().log("Storage request failed with {}", ex.getClass().getSimpleName());
			log.atTrace().setCause(ex).log("Storage request failure");
			return Response.serverError().build();
		}
	}
}
