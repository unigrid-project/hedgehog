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
import java.util.Arrays;
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
import org.unigrid.hedgehog.ledger.Digests;
import org.unigrid.hedgehog.ledger.LedgerDriver;
import org.unigrid.hedgehog.ledger.Transaction;
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
	private static final String UNREADABLE = "The node answered something this command cannot read: ";

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
			this(errorIn(oddity.getBody()).orElseGet(() -> describe(oddity)));
		}

		static Refused unreadable(final String what) {
			return new Refused(UNREADABLE + what);
		}

		private static String describe(final ResponseOddityException oddity) {
			return Status.fromStatusCode(oddity.getStatusCode()) == null
				? UNREADABLE + "status " + oddity.getStatusCode() : oddity.getMessage();
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
		System.err.println(printable(reason));
		return 1;
	}

	/* The words of a node are shown as they are, but a control character in them, which could move the cursor or
	   recolour a terminal, is dropped. A tab is a space that stays. */
	private static String printable(final String text) {
		return String.valueOf(text).chars()
			.filter(character -> !Character.isISOControl(character) || character == '\t')
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
	}

	/* A node without a ledger reports no chain, and nothing can be signed for it. A chain typed by the user guards
	   against a node that answers for another chain, as its certificate proves nothing */
	static byte[] chainId(final LedgerInfo info, final Optional<byte[]> expected) throws Refused {
		if (info.chainId() == null) {
			throw new Refused(LedgerDriver.NOT_RUNNING);
		}

		final byte[] chainId = parsedChainId(info.chainId());

		if (expected.isPresent() && !Arrays.equals(expected.get(), chainId)) {
			throw new Refused("The node is on chain " + HEX.formatHex(chainId) + ", not on "
				+ HEX.formatHex(expected.get()));
		}

		return chainId;
	}

	private static byte[] parsedChainId(final String hex) throws Refused {
		try {
			final byte[] chainId = HEX.parseHex(hex);

			if (chainId.length == Digests.HASH_SIZE) {
				return chainId;
			}
		} catch (IllegalArgumentException ex) {
			/* Refused below, in the same words as a wrong length */
		}

		throw Refused.unreadable("the chain id is not " + 2 * Digests.HASH_SIZE + " hex characters");
	}

	/* Whoever answers for the node may leave out what a command needs, and then there is nothing to sign */
	static long round(final LedgerInfo info) throws Refused {
		return required(info.round(), "round");
	}

	static long roundEnds(final LedgerInfo info) throws Refused {
		return required(info.roundEnds(), "roundEnds");
	}

	static List<String> roundValidators(final LedgerInfo info) throws Refused {
		return required(info.roundValidators(), "roundValidators");
	}

	static long nonce(final LedgerAccount account) throws Refused {
		return required(account.nonce(), "nonce");
	}

	private static <T> T required(final T value, final String name) throws Refused {
		if (value == null) {
			throw Refused.unreadable("no " + name);
		}

		return value;
	}

	/* What was signed is told before the node answers, on the error stream so that the output stays the answer of
	   the node alone. A node that cannot be vouched for chose the chain, and may have chosen the fee and the nonce
	   as well, so the person gets to see them. */
	private static void announce(final byte[] chainId, final String signed) {
		System.err.println("Signed for chain " + HEX.formatHex(chainId) + ": " + signed);
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

	/* A transaction the ledger will not take comes back as the reason it gives. A node that takes it names it by its
	   id, which is known here, so an acceptance that names another transaction is not taken for this one. */
	LedgerSubmitted submit(final Transaction transaction) throws Refused {
		final Entity<LedgerSubmission> body = Entity.json(new LedgerSubmission(HEX.formatHex(transaction.encode())));
		final LedgerSubmitted answer = answer(() -> rest.postBounded("/ledger/transaction", body),
			LedgerSubmitted.class, Status.ACCEPTED, Status.CONFLICT);

		if (!answer.accepted()) {
			throw new Refused(Optional.ofNullable(answer.reason()).orElse("refused"));
		}

		final String id = HEX.formatHex(transaction.id());

		if (!id.equals(answer.id())) {
			throw new Refused("The node answered for the transaction " + Optional.ofNullable(answer.id())
				.orElse("(no id)") + " and this one is " + id);
		}

		return answer;
	}

	/* Tells what was signed, sends it and prints the answer of the node */
	int send(final byte[] chainId, final String signed, final Transaction transaction) throws Refused {
		announce(chainId, signed);
		System.out.println(Json.parse(submit(transaction)));
		return 0;
	}

	private <T> T read(final String location, final Class<T> type) throws Refused {
		return answer(() -> rest.get(location), type, Status.OK);
	}

	private static <T> T answer(final Sending sending, final Class<T> type, final Status... success) throws Refused {
		try {
			final Response response = sending.send();

			if (Arrays.stream(success).noneMatch(status -> status.getStatusCode() == response.getStatus())) {
				throw ResponseOddityException.of(response);
			}

			return entityOf(response, type);
		} catch (ResponseOddityException ex) {
			throw new Refused(ex);
		}
	}

	/* A body that is not what was asked for is a bad answer, not a node that could not be reached */
	private static <T> T entityOf(final Response response, final Class<T> type) throws Refused {
		try {
			return response.readEntity(type);
		} catch (ProcessingException ex) {
			throw Refused.unreadable("the body is not what was asked for");
		}
	}

	@Override
	public void close() {
		rest.close();
	}
}
