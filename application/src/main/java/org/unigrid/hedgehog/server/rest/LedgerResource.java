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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.ledger.Ledger;
import org.unigrid.hedgehog.ledger.LedgerDriver;
import org.unigrid.hedgehog.ledger.Mint;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.bootstrap.LedgerText;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.network.codec.LedgerCodecs;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.server.rest.entity.LedgerAccount;
import org.unigrid.hedgehog.server.rest.entity.LedgerBlock;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmission;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmitted;
import org.unigrid.hedgehog.service.ledger.LedgerService;
import org.unigrid.hedgehog.service.ledger.LedgerService.Report;

/* Every request reads the newest ledger the service has at that moment, so a node that replaces its ledger is read
   whole, from one ledger or the other and never a mix, and while there is none the answer is that it is not running */
@Slf4j
@Path("/ledger")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LedgerResource extends CDIBridgeResource {
	private static final Duration SUBMIT_TIMEOUT = Duration.ofSeconds(10);
	private static final int MAX_SUBMITS_IN_FLIGHT = 16;
	private static final HexFormat HEX = HexFormat.of();
	private static final ObjectMapper JSON = new JsonConfiguration().getContext(LedgerSubmission.class);
	private static final Pattern DIGITS = Pattern.compile("[0-9]+");
	private static final int MAX_BODY = 4 * 1024;
	private static final int MAX_HEX = 2 * LedgerCodecs.MAX_TRANSACTION_SIZE;
	private static final String NO_ANSWER = "The ledger could not answer";

	/* A request is served by its own resource object, so what the requests share is held by the class. Every
	   submit that waits holds a thread of the server, which has no other limit on them, and every one that the
	   ledger has not answered is queued on its driver. */
	private static final Semaphore WAITING = new Semaphore(MAX_SUBMITS_IN_FLIGHT);

	@CDIBridgeInject
	private LedgerService ledger;

	@CDIBridgeInject
	private SporkDatabase sporkDatabase;

	@GET
	public Response info() {
		final Report report = ledger.status();
		final LedgerInfo info = ledger.read(held -> LedgerInfo.of(report, held, minimumFee()))
			.orElseGet(() -> LedgerInfo.of(report));

		return Response.ok().entity(info).build();
	}

	@Path("/account/{address}") @GET
	public Response account(@PathParam("address") final String text) {
		return LedgerText.address(text).map(address -> answer(held -> ok(LedgerAccount.of(held, address))))
			.orElseGet(() -> error(Status.BAD_REQUEST,
				"An address is 64 lower-case hex characters for an account, or a legacy address"));
	}

	@Path("/block/{height}") @GET
	public Response block(@PathParam("height") final String text) {
		return heightOf(text).map(height -> answer(held -> held.block(height).map(block -> ok(LedgerBlock.of(block)))
			.orElseGet(() -> error(Status.NOT_FOUND, "No block at height " + height))))
			.orElseGet(() -> error(Status.BAD_REQUEST, "A height is a whole number in digits that fits a long"));
	}

	/* The body is read by hand and at most one byte beyond the bound, since a reader that is left to the JSON
	   library takes a string of twenty million characters before anything can refuse it */
	@Path("/transaction") @POST
	public Response submit(final InputStream body) throws IOException {
		final byte[] sent = body.readNBytes(MAX_BODY + 1);

		if (sent.length > MAX_BODY) {
			return error(Status.REQUEST_ENTITY_TOO_LARGE, "A request is at most " + MAX_BODY + " bytes");
		}

		final Transaction transaction;

		try {
			transaction = decode(JSON.readValue(sent, LedgerSubmission.class));
		} catch (JsonProcessingException ex) {
			return error(Status.BAD_REQUEST, "A request is JSON with the hex of a transaction");
		} catch (IllegalArgumentException ex) {
			return error(Status.BAD_REQUEST, ex.getMessage());
		}

		return await(transaction);
	}

	/* The length is bounded before the hex is read, so that nothing longer than a transaction can be is decoded */
	private static Transaction decode(final LedgerSubmission submission) {
		if (submission == null || submission.transaction() == null) {
			throw new IllegalArgumentException("A transaction is sent as the hex of its encoding");
		}

		if (submission.transaction().length() > MAX_HEX) {
			throw new IllegalArgumentException(
				"A transaction is at most " + LedgerCodecs.MAX_TRANSACTION_SIZE + " bytes");
		}

		final Transaction transaction = WireCodec.decodeTransaction(HEX.parseHex(submission.transaction()));

		if (transaction instanceof Mint) {
			throw new IllegalArgumentException("A mint comes only from the sporks, not from a client");
		}

		return transaction;
	}

	/* A timeout leaves the future to run, as the ledger may still take the transaction in */
	private Response await(final Transaction transaction) {
		if (!WAITING.tryAcquire()) {
			return error(Status.TOO_MANY_REQUESTS, "Too many transactions wait for the ledger");
		}

		final CompletableFuture<Optional<String>> future = submitHoldingTheWait(transaction);

		try {
			return verdict(transaction, future.get(SUBMIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
		} catch (TimeoutException ex) {
			return error(Status.GATEWAY_TIMEOUT,
				"No answer from the ledger in time, it may still take the transaction");
		} catch (ExecutionException ex) {
			log.atWarn().setCause(ex).log("The ledger failed to answer a transaction");
			return error(Status.SERVICE_UNAVAILABLE, NO_ANSWER);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return error(Status.SERVICE_UNAVAILABLE, NO_ANSWER);
		}
	}

	/* The wait is given back when the ledger has answered, not when the response is sent: a submit that timed out
	   is still queued on a driver that stalls, so freeing its wait at the timeout would let a steady client queue
	   more of them than the limit, without end */
	private CompletableFuture<Optional<String>> submitHoldingTheWait(final Transaction transaction) {
		try {
			final CompletableFuture<Optional<String>> future = ledger.submit(transaction);

			future.whenComplete((refusal, failure) -> WAITING.release());
			return future;
		} catch (RuntimeException ex) {
			WAITING.release();
			throw ex;
		}
	}

	private static Response verdict(final Transaction transaction, final Optional<String> refusal) {
		final String id = HEX.formatHex(transaction.id());

		return refusal.map(reason -> LedgerDriver.NOT_RUNNING.equals(reason)
			? error(Status.SERVICE_UNAVAILABLE, reason)
			: Response.status(Status.CONFLICT).entity(new LedgerSubmitted(false, id, reason)).build())
			.orElseGet(() -> Response.accepted(new LedgerSubmitted(true, id, null)).build());
	}

	/* Plain digits only: parseLong alone would also take a plus sign, a minus sign and the digits of other scripts */
	private static Optional<Long> heightOf(final String text) {
		try {
			return Optional.of(text).filter(DIGITS.asMatchPredicate()).map(Long::parseLong);
		} catch (NumberFormatException ex) {
			return Optional.empty();
		}
	}

	private Response answer(final Function<Ledger, Response> reading) {
		return ledger.read(reading).orElseGet(() -> error(Status.SERVICE_UNAVAILABLE, LedgerDriver.NOT_RUNNING));
	}

	/* Unset is not zero: a node with no fee spork refuses every transfer, whatever the fee. A fee the spork cannot
	   tell is left out as well, so that a spork failing its own validation does not take the status down with it */
	private String minimumFee() {
		try {
			return Optional.ofNullable(sporkDatabase.getLedgerFee())
				.map(spork -> spork.<LedgerFee.SporkData>getData()).map(LedgerFee.SporkData::units)
				.map(LedgerText::coins).orElse(null);
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static Response ok(final Object entity) {
		return Response.ok().entity(entity).build();
	}

	private static Response error(final Status status, final String message) {
		return Response.status(status).entity(Map.of("error", message)).build();
	}
}
