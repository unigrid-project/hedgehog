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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.server.rest.entity.LedgerAccount;
import org.unigrid.hedgehog.server.rest.entity.LedgerBlock;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmission;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmitted;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerClientTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String ID = "9f".repeat(64);

	private static final LedgerInfo INFO = new LedgerInfo(true, false, "", true, "11".repeat(32), 3, "22".repeat(64),
		"12.50000000", 0L, List.of("33".repeat(32)), "0.00100000", 0, List.of(), List.of());

	private static final LedgerAccount ACCOUNT = new LedgerAccount("ab".repeat(32), "account", "6.99900000", 1L,
		"10.00000000", null, null);

	private static final LedgerBlock BLOCK = new LedgerBlock(5, 0, 1_760_000_000_000L, "aa".repeat(64),
		"bb".repeat(64), "cc".repeat(64), "dd".repeat(64), "ee".repeat(32), List.of(), List.of("11".repeat(32)), 1);

	private record Outcome(Result sent, Object value) { }

	private interface Asking {
		Object ask(LedgerClient ledger) throws LedgerClient.Refused;
	}

	@Provide
	public Arbitrary<String> reasons() {
		return Arbitraries.strings().alpha().numeric().withChars(' ', '.', ':', '"', '\\', 'ö', '\n').ofMinLength(1)
			.ofMaxLength(80);
	}

	@Provide
	public Arbitrary<Status> failures() {
		return Arbitraries.of(Status.BAD_REQUEST, Status.SERVICE_UNAVAILABLE, Status.TOO_MANY_REQUESTS,
			Status.GATEWAY_TIMEOUT);
	}

	@SneakyThrows
	private static Object attempt(final Asking asking) {
		try (LedgerClient ledger = LedgerClient.connect()) {
			return asking.ask(ledger);
		} catch (LedgerClient.Refused ex) {
			return ex;
		}
	}

	private static Outcome run(final Function<Runnable, Result> fixture, final Asking asking) {
		final AtomicReference<Object> value = new AtomicReference<>();
		final Result sent = fixture.apply(() -> value.set(attempt(asking)));

		return new Outcome(sent, value.get());
	}

	private static Outcome answered(final Response canned, final Asking asking) {
		return run(command -> RestCommandFixture.run(command, canned), asking);
	}

	private static Request get(final String location) {
		return new Request(HttpMethod.GET, location, Optional.empty(), Optional.empty());
	}

	@SneakyThrows
	private static String errorBody(final String text) {
		return MAPPER.writeValueAsString(Map.of("error", text));
	}

	private static String refusalOf(final Outcome outcome) {
		assertThat(outcome.value(), instanceOf(LedgerClient.Refused.class));
		return ((LedgerClient.Refused) outcome.value()).getMessage();
	}

	@Example
	public void readsTheStateOfTheNodeTheAccountAndTheBlock() {
		final Outcome info = answered(Response.ok(INFO).build(), LedgerClient::info);
		final Outcome account = answered(Response.ok(ACCOUNT).build(), ledger -> ledger.account(ACCOUNT.address()));
		final Outcome block = answered(Response.ok(BLOCK).build(), ledger -> ledger.block(5));

		assertThat(info.sent().request(), equalTo(Optional.of(get("/ledger"))));
		assertThat(info.value(), equalTo(INFO));
		assertThat(account.sent().request(), equalTo(Optional.of(get("/ledger/account/" + ACCOUNT.address()))));
		assertThat(account.value(), equalTo(ACCOUNT));
		assertThat(block.sent().request(), equalTo(Optional.of(get("/ledger/block/5"))));
		assertThat(block.value(), equalTo(BLOCK));
		assertThat(info.sent().closed(), equalTo(true));
	}

	@Property(tries = 30)
	public void sendsTheEncodedTransactionAsHexAndHandsBackTheIdItIsFoundBy(@ForAll @Size(min = 1, max = 163)
		byte[] encoded) {

		final LedgerSubmitted accepted = new LedgerSubmitted(true, ID, null);
		final Outcome outcome = answered(Response.accepted(accepted).build(), ledger -> ledger.submit(encoded));
		final Request request = outcome.sent().request().orElseThrow();

		assertThat(request.method(), equalTo(HttpMethod.POST));
		assertThat(request.location(), equalTo("/ledger/transaction"));
		assertThat(request.entity().orElseThrow().getMediaType(), equalTo(MediaType.APPLICATION_JSON_TYPE));
		assertThat((Object) request.entity().orElseThrow().getEntity(),
			equalTo(new LedgerSubmission(HexFormat.of().formatHex(encoded))));

		assertThat(outcome.value(), equalTo(accepted));
	}

	@Property(tries = 30)
	public void givesTheReasonOfALedgerThatRefusesTheTransactionWordForWord(@ForAll("reasons") String reason) {
		final LedgerSubmitted refused = new LedgerSubmitted(false, ID, reason);
		final Outcome outcome = answered(Response.status(Status.CONFLICT).entity(refused).build(),
			ledger -> ledger.submit(new byte[] { 4 }));

		assertThat(refusalOf(outcome), equalTo(reason));
	}

	@Property(tries = 40)
	public void givesTheErrorTextOfAnAnswerThatIsNoSuccess(@ForAll("failures") Status status,
		@ForAll("reasons") String text) {

		final ResponseOddityException oddity = new ResponseOddityException(status, errorBody(text));

		for (final Asking asking : List.<Asking>of(LedgerClient::info, ledger -> ledger.account("a"),
			ledger -> ledger.block(1), ledger -> ledger.submit(new byte[] { 4 }))) {

			assertThat(refusalOf(run(command -> RestCommandFixture.runWithOddity(command, oddity), asking)),
				equalTo(text));
		}
	}

	@Property(tries = 30)
	public void givesTheErrorTextOfAnAnswerThatIsReturnedButNoSuccess(@ForAll("reasons") String text) {
		final Response notFound = Response.status(Status.NOT_FOUND).entity(errorBody(text)).build();

		assertThat(refusalOf(answered(notFound, ledger -> ledger.block(9))), equalTo(text));
	}

	@Example
	public void givesTheStatusWhereTheNodeSaidNothingMore() {
		final String noContent = new ResponseOddityException(Status.NO_CONTENT).getMessage();
		final String notFound = new ResponseOddityException(Status.NOT_FOUND).getMessage();
		final String rejected = new ResponseOddityException(Status.UNAUTHORIZED).getMessage();

		assertThat(refusalOf(answered(Response.noContent().build(), ledger -> ledger.account("a"))),
			equalTo(noContent));

		assertThat(refusalOf(answered(Response.status(Status.NOT_FOUND).entity("<html>").build(),
			ledger -> ledger.block(1))), equalTo(notFound));

		assertThat(refusalOf(run(command -> RestCommandFixture.runWithOddity(command,
			new ResponseOddityException(Status.UNAUTHORIZED, "")), LedgerClient::info)), equalTo(rejected));
	}

	@Example
	public void failsToConnectWhereThereIsNoTokenToSend() {
		final IOException unreadable = new IOException("Unreadable");
		final AtomicReference<IOException> thrown = new AtomicReference<>();

		final Result result = RestCommandFixture.runWithoutToken(
			() -> thrown.set(assertThrows(IOException.class, LedgerClient::connect)), unreadable);

		assertThat(thrown.get(), sameInstance(unreadable));
		assertThat(result.connection(), equalTo(Optional.empty()));
	}
}
