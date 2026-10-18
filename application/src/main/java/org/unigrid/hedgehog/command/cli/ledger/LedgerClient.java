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

package org.unigrid.hedgehog.command.cli.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.Json;
import org.unigrid.hedgehog.server.rest.entity.LedgerAccount;
import org.unigrid.hedgehog.server.rest.entity.LedgerBlock;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmission;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmitted;

/* What the commands of `cli ledger` ask of a node, over one connection that lives as long as the command. A method
   gives what the node answered, or throws what it said when it would not: the error text of the node in its own
   words, such as "Balance does not cover the transfer", and the status line only where it said nothing more. A
   command that asks for several things runs them in one session, so a refusal of any one ends it the same way. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class LedgerClient implements AutoCloseable {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final HexFormat HEX = HexFormat.of();

	@FunctionalInterface
	interface Session {
		int run(LedgerClient ledger) throws Refused;
	}

	@FunctionalInterface
	interface Question<T> {
		T ask(LedgerClient ledger) throws Refused;
	}

	@FunctionalInterface
	private interface Sending {
		Response send() throws ResponseOddityException;
	}

	static final class Refused extends Exception {
		Refused(final String text) {
			super(text);
		}

		private Refused(final ResponseOddityException oddity) {
			this(errorIn(oddity.getBody()).orElse(oddity.getMessage()));
		}

		private static Optional<String> errorIn(final String body) {
			try {
				return Optional.ofNullable(MAPPER.readTree(body).path("error").textValue());
			} catch (JsonProcessingException ex) {
				return Optional.empty();
			}
		}
	}

	private final RestClient rest;

	static LedgerClient connect() throws IOException {
		return new LedgerClient(RestClientCommand.connect());
	}

	/* Runs the work against the node of the options and gives its exit code: 1 where the node refused or could not
	   be reached, with the reason on standard error */
	static int session(final Session work) {
		try (LedgerClient ledger = connect()) {
			return work.run(ledger);
		} catch (Refused | IOException ex) {
			return failed(RestClientCommand.describe(ex));
		} catch (ProcessingException ex) {
			return failed("Could not reach the node at %s:%d: %s".formatted(RestOptions.getHost(),
				RestOptions.getPort(), ExceptionUtils.getRootCauseMessage(ex)));
		}
	}

	private static int failed(final String reason) {
		System.err.println(reason);
		return 1;
	}

	static int print(final Question<?> question) {
		return session(ledger -> {
			System.out.println(Json.parse(question.ask(ledger)));
			return 0;
		});
	}

	LedgerInfo info() throws Refused {
		return read("/ledger", LedgerInfo.class);
	}

	LedgerAccount account(final String address) throws Refused {
		return read("/ledger/account/" + address, LedgerAccount.class);
	}

	LedgerBlock block(final long height) throws Refused {
		return read("/ledger/block/" + height, LedgerBlock.class);
	}

	/* A transaction the ledger will not take comes back as the reason it gives */
	LedgerSubmitted submit(final byte[] encoded) throws Refused {
		final Entity<LedgerSubmission> body = Entity.json(new LedgerSubmission(HEX.formatHex(encoded)));
		final LedgerSubmitted answer = answer(() -> rest.post("/ledger/transaction", body), LedgerSubmitted.class,
			Status.ACCEPTED, Status.CONFLICT);

		if (!answer.accepted()) {
			throw new Refused(answer.reason());
		}

		return answer;
	}

	private <T> T read(final String location, final Class<T> type) throws Refused {
		return answer(() -> rest.get(location), type, Status.OK);
	}

	private static <T> T answer(final Sending sending, final Class<T> type, final Status... success) throws Refused {
		try {
			final Response response = sending.send();

			if (!List.of(success).contains(Status.fromStatusCode(response.getStatus()))) {
				throw ResponseOddityException.of(response);
			}

			return response.readEntity(type);
		} catch (ResponseOddityException ex) {
			throw new Refused(ex);
		}
	}

	@Override
	public void close() {
		rest.close();
	}
}
