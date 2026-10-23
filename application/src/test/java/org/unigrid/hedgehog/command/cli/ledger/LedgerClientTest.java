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

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
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
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.ACCOUNT;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.CHAIN;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.ID;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.INFO;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.ROUND_ENDS;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.SEED;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.UNREADABLE;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.block;
import static org.unigrid.hedgehog.command.cli.ledger.LedgerCommandFixtures.errorBody;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Transfer;
import org.unigrid.hedgehog.server.rest.entity.LedgerAccount;
import org.unigrid.hedgehog.server.rest.entity.LedgerBlock;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmission;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmitted;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerClientTest {
	private static final AccountKey RECIPIENT = AccountKey.fromHex(ACCOUNT.address());
	private static final Transfer TRANSFER = Transfer.signed(SEED, RECIPIENT, 1, 1, 0, CHAIN);
	private static final String TRANSFER_ID = HexFormat.of().formatHex(TRANSFER.id());
	private static final LedgerBlock BLOCK = block(List.of());

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
	public void sendsTheEncodedTransactionAsHexAndHandsBackTheIdItIsFoundBy(
		@ForAll @LongRange(min = 0, max = 1_000_000) long nonce) {

		final Transfer transfer = Transfer.signed(SEED, RECIPIENT, 1, 1, nonce, CHAIN);
		final LedgerSubmitted accepted = new LedgerSubmitted(true, HexFormat.of().formatHex(transfer.id()), null);
		final Outcome outcome = answered(Response.accepted(accepted).build(), ledger -> ledger.submit(transfer));
		final Request request = outcome.sent().request().orElseThrow();

		assertThat(request.method(), equalTo(HttpMethod.POST));
		assertThat(request.location(), equalTo("/ledger/transaction"));
		assertThat(request.entity().orElseThrow().getMediaType(), equalTo(MediaType.APPLICATION_JSON_TYPE));
		assertThat((Object) request.entity().orElseThrow().getEntity(),
			equalTo(new LedgerSubmission(HexFormat.of().formatHex(transfer.encode()))));

		assertThat(outcome.value(), equalTo(accepted));
	}

	@Provide
	public Arbitrary<String> otherIds() {
		return Arbitraries.strings().withChars("0123456789abcdef").ofLength(128).filter(id -> !id.equals(TRANSFER_ID));
	}

	@Property(tries = 30)
	public void refusesAnAcceptanceThatNamesAnotherTransaction(@ForAll("otherIds") String id) {
		final Outcome outcome = answered(Response.accepted(new LedgerSubmitted(true, id, null)).build(),
			ledger -> ledger.submit(TRANSFER));

		assertThat(refusalOf(outcome), equalTo("The node answered for the transaction " + id
			+ " and this one is " + TRANSFER_ID));
	}

	@Example
	public void refusesAnAcceptanceThatNamesNothing() {
		final Outcome outcome = answered(Response.accepted(new LedgerSubmitted(true, null, null)).build(),
			ledger -> ledger.submit(TRANSFER));

		assertThat(refusalOf(outcome), equalTo("The node answered for the transaction (no id) and this one is "
			+ TRANSFER_ID));
	}

	@Example
	public void saysRefusedWhereTheNodeRefusesWithoutAReason() {
		final Outcome outcome = answered(Response.status(Status.CONFLICT)
			.entity(new LedgerSubmitted(false, ID, null)).build(), ledger -> ledger.submit(TRANSFER));

		assertThat(refusalOf(outcome), equalTo("refused"));
	}

	@Property(tries = 30)
	public void givesTheReasonOfALedgerThatRefusesTheTransactionWordForWord(@ForAll("reasons") String reason) {
		final LedgerSubmitted refused = new LedgerSubmitted(false, ID, reason);
		final Outcome outcome = answered(Response.status(Status.CONFLICT).entity(refused).build(),
			ledger -> ledger.submit(TRANSFER));

		assertThat(refusalOf(outcome), equalTo(reason));
	}

	@Property(tries = 40)
	public void givesTheErrorTextOfAnAnswerThatIsNoSuccess(@ForAll("failures") Status status,
		@ForAll("reasons") String text) {

		final ResponseOddityException oddity = new ResponseOddityException(status, errorBody(text));

		for (final Asking asking : List.<Asking>of(LedgerClient::info, ledger -> ledger.account("a"),
			ledger -> ledger.block(1), ledger -> ledger.submit(TRANSFER))) {

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

	@Provide
	public Arbitrary<String> notChainIds() {
		return Arbitraries.oneOf(Arbitraries.strings().ofMaxLength(200).filter(text -> !text.matches("[0-9a-f]{128}")),
			Arbitraries.strings().withChars("0123456789abcdef").ofMaxLength(200).filter(text -> text.length() != 128));
	}

	@Example
	public void saysInOneLineThatTheStatusOfTheNodeIsNoStatus() {
		for (final Asking asking : List.<Asking>of(LedgerClient::info, ledger -> ledger.account("a"),
			ledger -> ledger.block(1), ledger -> ledger.submit(TRANSFER))) {

			assertThat(refusalOf(answered(Response.status(599).entity("<html>").build(), asking)),
				equalTo(UNREADABLE + "status 599"));
		}
	}

	@Property(tries = 50)
	public void refusesAChainIdThatIsNoHexOfAChainId(@ForAll("notChainIds") String chainId) {
		final LedgerInfo odd = new LedgerInfo(true, false, "", true, chainId, 3, "22".repeat(64), "12.50000000", 0L,
			ROUND_ENDS, List.of(), "0.00100000", 0, List.of(), List.of());

		assertThat(assertThrows(LedgerClient.Refused.class,
			() -> LedgerClient.chainId(odd, Optional.empty())).getMessage(),
			equalTo(UNREADABLE + "the chain id is not 128 hex characters"));
	}

	@Example
	public void refusesAnAnswerThatLacksWhatTheCommandNeeds() {
		final LedgerInfo noRound = new LedgerInfo(true, false, "", true, "11".repeat(64), 3, "22".repeat(64),
			"12.50000000", null, ROUND_ENDS, List.of(), "0.00100000", 0, List.of(), List.of());
		final LedgerInfo noRoundEnd = new LedgerInfo(true, false, "", true, "11".repeat(64), 3, "22".repeat(64),
			"12.50000000", 0L, null, List.of(), "0.00100000", 0, List.of(), List.of());
		final LedgerInfo noValidators = new LedgerInfo(true, false, "", true, "11".repeat(64), 3, "22".repeat(64),
			"12.50000000", 0L, ROUND_ENDS, null, "0.00100000", 0, List.of(), List.of());
		final LedgerAccount noNonce = new LedgerAccount("ab".repeat(32), "account", "6.99900000", null,
			"10.00000000", null, null);

		assertThat(assertThrows(LedgerClient.Refused.class, () -> LedgerClient.round(noRound)).getMessage(),
			equalTo(UNREADABLE + "no round"));
		assertThat(assertThrows(LedgerClient.Refused.class, () -> LedgerClient.roundEnds(noRoundEnd)).getMessage(),
			equalTo(UNREADABLE + "no roundEnds"));
		assertThat(assertThrows(LedgerClient.Refused.class,
			() -> LedgerClient.validators(noValidators)).getMessage(), equalTo(UNREADABLE + "no validators"));
		assertThat(assertThrows(LedgerClient.Refused.class, () -> LedgerClient.nonce(noNonce)).getMessage(),
			equalTo(UNREADABLE + "no nonce"));
	}

	@Example
	public void takesWhatTheNodeSaysWhenItIsThere() throws LedgerClient.Refused {
		assertThat(LedgerClient.round(INFO), equalTo(0L));
		assertThat(LedgerClient.roundEnds(INFO), equalTo(ROUND_ENDS));
		assertThat(LedgerClient.validators(INFO), equalTo(INFO.validators()));
		assertThat(LedgerClient.nonce(ACCOUNT), equalTo(1L));
	}
}
