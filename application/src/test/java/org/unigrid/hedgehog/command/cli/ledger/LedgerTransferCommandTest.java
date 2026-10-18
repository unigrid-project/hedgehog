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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures.KEY_FILE;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.Transfer;
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.model.bootstrap.LedgerText;
import org.unigrid.hedgehog.server.rest.RestToken;
import org.unigrid.hedgehog.server.rest.entity.LedgerAccount;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmission;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmitted;
import org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures;
import picocli.CommandLine;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerTransferCommandTest {
	private static final String CHAIN_ID = "11".repeat(32);
	private static final byte[] CHAIN = HexFormat.of().parseHex(CHAIN_ID);
	private static final byte[] SEED = HexFormat.of().parseHex("42".repeat(32));
	private static final String RECIPIENT = "ab".repeat(32);
	private static final String NODE = "node.test";
	private static final int PORT = 4321;
	private static final String TOKEN = "rest-token";
	private static final String ID = "9f".repeat(64);
	private static final String MINIMUM_FEE = "0.00100000";
	private static final long MINIMUM_FEE_UNITS = 100_000L;
	private static final String KEY_OPTION = "--key";
	private static final String TO_OPTION = "--to";
	private static final String AMOUNT_OPTION = "--amount";
	private static final String FEE_OPTION = "--fee";
	private static final String NONCE_OPTION = "--nonce";

	static {
		LedgerTransferCommandTest.class.getModule().addOpens(
			LedgerTransferCommandTest.class.getPackageName(), MockUp.class.getModule());
	}

	private record BeforeSending(Result sent, HedgehogCli.Result cli) { }

	private record Outcome(List<Request> requests, boolean connected, boolean closed, HedgehogCli.Result cli) { }

	@Provide
	public Arbitrary<byte[]> seeds() {
		return Arbitraries.bytes().array(byte[].class).ofSize(Ed25519.SEED_SIZE);
	}

	@Provide
	public Arbitrary<String> upperCaseAccounts() {
		return Arbitraries.strings().withChars("0123456789ABCDEF").ofLength(AccountKey.SIZE * 2);
	}

	@Provide
	public Arbitrary<String> reasons() {
		return Arbitraries.strings().alpha().numeric().withChars(' ', '.', ':', '\'').ofMinLength(1).ofMaxLength(60);
	}

	private static String[] ledger(String... arguments) {
		return Stream.concat(Stream.of("cli", "ledger", "transfer"), Stream.of(arguments)).toArray(String[]::new);
	}

	private static HedgehogCli.Result runOn(FileSystem fs, String... arguments) {
		return HedgehogCli.run(() -> new CommandLine(Hedgehog.class).registerConverter(Path.class, fs::getPath),
			ledger(arguments));
	}

	private static BeforeSending refusedOn(FileSystem fs, String... arguments) {
		final AtomicReference<HedgehogCli.Result> cli = new AtomicReference<>();
		final Result sent = RestCommandFixture.run(() -> cli.set(runOn(fs, arguments)), Response.ok().build());

		return new BeforeSending(sent, cli.get());
	}

	private static Outcome sendOn(FileSystem fs, Response info, Response account, Response submitted,
		String... arguments) {

		fakeOptions();
		final FakeNode node = new FakeNode(info, account, submitted);
		final HedgehogCli.Result cli = runOn(fs, arguments);

		return new Outcome(List.copyOf(node.requests()), node.connected(), node.closed(), cli);
	}

	private static void fakeOptions() {
		new MockUp<RestOptions>() {
			@Mock public String getHost() {
				return NODE;
			}

			@Mock public int getPort() {
				return PORT;
			}
		};

		new MockUp<RestToken>() {
			@Mock public String resolve() {
				return TOKEN;
			}
		};
	}

	private static void readBackItsEntity(Response canned) {
		new MockUp<Response>(canned.getClass()) {
			@Mock public Object readEntity(Invocation invocation, Class<?> type) {
				return invocation.<Response>getInvokedInstance().getEntity();
			}

			@Mock public Object readEntity(Invocation invocation, GenericType<?> type) {
				return invocation.<Response>getInvokedInstance().getEntity();
			}
		};
	}

	private static Response info(String minimumFee) {
		return Response.ok(new LedgerInfo(true, false, "", true, CHAIN_ID, 3, "22".repeat(64), "12.50000000", 0L,
			List.of("33".repeat(32)), minimumFee, 0, List.of(), List.of())).build();
	}

	private static Response account(long nonce) {
		return Response.ok(new LedgerAccount(RECIPIENT, "account", "10.00000000", nonce, "10.00000000", null,
			null)).build();
	}

	private static Response accepted() {
		return Response.accepted(new LedgerSubmitted(true, ID, null)).build();
	}

	private static Transfer transferIn(Outcome outcome) {
		final Request posted = outcome.requests().stream()
			.filter(request -> HttpMethod.POST.equals(request.method())).findFirst().orElseThrow();
		final LedgerSubmission submission = (LedgerSubmission) posted.entity().orElseThrow().getEntity();

		return (Transfer) WireCodec.decodeTransaction(HexFormat.of().parseHex(submission.transaction()));
	}

	private static void assertRefusedBeforeSending(BeforeSending outcome, String named) {
		assertThat(outcome.sent().request(), equalTo(Optional.empty()));
		assertThat(outcome.sent().connection(), equalTo(Optional.empty()));
		assertThat(outcome.cli().exitCode(), equalTo(2));
		assertThat(outcome.cli().out(), emptyString());
		assertThat(outcome.cli().err(), containsString(named));
		outcome.cli().assertNoStackTrace();
	}

	@Property(tries = 20)
	@SneakyThrows
	public void signsTheTransferWithTheSenderOfTheKeyFile(@ForAll("seeds") byte[] seed,
		@ForAll @LongRange(min = 0, max = 1_000_000) long nonce,
		@ForAll @LongRange(min = 1, max = 100_000_000_000L) long units) {

		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, seed);

			final Outcome outcome = sendOn(fs, info(MINIMUM_FEE), account(0), accepted(), KEY_OPTION, KEY_FILE,
				TO_OPTION, RECIPIENT, AMOUNT_OPTION, LedgerText.coins(units), NONCE_OPTION,
				String.valueOf(nonce));
			final Transfer transfer = transferIn(outcome);

			assertThat(outcome.cli().exitCode(), equalTo(0));
			assertThat(outcome.cli().err(), emptyString());
			assertThat(outcome.cli().out(), containsString(ID));
			assertThat(transfer.sender(), equalTo(Ed25519.publicKey(seed)));
			assertThat(transfer.recipient(), equalTo(AccountKey.fromHex(RECIPIENT)));
			assertThat(transfer.amount(), equalTo(units));
			assertThat(transfer.fee(), equalTo(MINIMUM_FEE_UNITS));
			assertThat(transfer.nonce(), equalTo(nonce));
			assertThat(transfer.hasValidSignature(CHAIN), equalTo(true));
			assertThat(outcome.connected(), equalTo(true));
			assertThat(outcome.closed(), equalTo(true));
		}
	}

	@Example
	@SneakyThrows
	public void takesTheFeeThatIsPassedRatherThanTheMinimumOfTheNode() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			final Outcome outcome = sendOn(fs, info("0.00100000"), account(0), accepted(), KEY_OPTION, KEY_FILE,
				TO_OPTION, RECIPIENT, AMOUNT_OPTION, "1", FEE_OPTION, "0.50000000", NONCE_OPTION, "0");

			assertThat(transferIn(outcome).fee(), equalTo(50_000_000L));
		}
	}

	@Example
	@SneakyThrows
	public void takesTheMinimumFeeOfTheNodeWhenNoneIsPassed() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			final Outcome outcome = sendOn(fs, info("0.00250000"), account(0), accepted(), KEY_OPTION, KEY_FILE,
				TO_OPTION, RECIPIENT, AMOUNT_OPTION, "1", NONCE_OPTION, "0");

			assertThat(transferIn(outcome).fee(), equalTo(250_000L));
		}
	}

	@Example
	@SneakyThrows
	public void takesAZeroMinimumFeeOfTheNode() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			final Outcome outcome = sendOn(fs, info("0.00000000"), account(0), accepted(), KEY_OPTION, KEY_FILE,
				TO_OPTION, RECIPIENT, AMOUNT_OPTION, "1", NONCE_OPTION, "0");

			assertThat(transferIn(outcome).fee(), equalTo(0L));
		}
	}

	@Example
	@SneakyThrows
	public void refusesWhenThereIsNoMinimumFeeToTake() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			final Outcome outcome = sendOn(fs, info(null), account(0), accepted(), KEY_OPTION, KEY_FILE,
				TO_OPTION, RECIPIENT, AMOUNT_OPTION, "1", NONCE_OPTION, "0");

			assertThat(outcome.cli().exitCode(), equalTo(2));
			assertThat(outcome.cli().out(), emptyString());
			assertThat(outcome.cli().err(), containsString("No minimum fee is set on the node; pass --fee"));
			assertThat(outcome.requests().stream().anyMatch(request -> HttpMethod.POST.equals(request.method())),
				equalTo(false));
			outcome.cli().assertNoStackTrace();
		}
	}

	@Example
	@SneakyThrows
	public void asksForNoNonceWhenOneIsGiven() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			final Outcome outcome = sendOn(fs, info(MINIMUM_FEE), account(0), accepted(), KEY_OPTION, KEY_FILE,
				TO_OPTION, RECIPIENT, AMOUNT_OPTION, "1", NONCE_OPTION, "7");

			assertThat(outcome.requests().stream().map(Request::location).toList(),
				equalTo(List.of("/ledger", "/ledger/transaction")));
			assertThat(transferIn(outcome).nonce(), equalTo(7L));
		}
	}

	@Example
	@SneakyThrows
	public void asksTheNodeForTheNonceWhenNoneIsGiven() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			final Outcome outcome = sendOn(fs, info(MINIMUM_FEE), account(9), accepted(), KEY_OPTION, KEY_FILE,
				TO_OPTION, RECIPIENT, AMOUNT_OPTION, "1");

			assertThat(outcome.requests().stream().map(Request::location).toList(), equalTo(List.of("/ledger",
				"/ledger/account/" + Ed25519.publicKey(SEED), "/ledger/transaction")));
			assertThat(transferIn(outcome).nonce(), equalTo(9L));
		}
	}

	@Property(tries = 20)
	@SneakyThrows
	public void printsTheReasonOfATransferTheLedgerRefuses(@ForAll("reasons") String reason) {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			final Response refused = Response.status(Status.CONFLICT)
				.entity(new LedgerSubmitted(false, ID, reason)).build();
			final Outcome outcome = sendOn(fs, info(MINIMUM_FEE), account(0), refused, KEY_OPTION, KEY_FILE,
				TO_OPTION, RECIPIENT, AMOUNT_OPTION, "1", NONCE_OPTION, "0");

			assertThat(outcome.cli().exitCode(), equalTo(1));
			assertThat(outcome.cli().out(), emptyString());
			assertThat(outcome.cli().err().lines().toList(), equalTo(List.of(reason)));
			outcome.cli().assertNoStackTrace();
		}
	}

	@Example
	@SneakyThrows
	public void refusesAmountsThatAreNoPlainPositiveNumber() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			for (final String typed : List.of("0", "-1", "1e3", "0.000000001", "1,5")) {
				assertRefusedBeforeSending(refusedOn(fs, KEY_OPTION, KEY_FILE, TO_OPTION, RECIPIENT,
					AMOUNT_OPTION + "=" + typed, NONCE_OPTION, "0"), AMOUNT_OPTION);
			}
		}
	}

	@Example
	@SneakyThrows
	public void refusesAToThatIsNoLedgerAddress() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			for (final String typed : List.of("", "nonsense")) {
				assertRefusedBeforeSending(refusedOn(fs, KEY_OPTION, KEY_FILE, TO_OPTION, typed,
					AMOUNT_OPTION, "1", NONCE_OPTION, "0"), TO_OPTION);
			}
		}
	}

	@Example
	@SneakyThrows
	public void refusesANegativeNonce() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			assertRefusedBeforeSending(refusedOn(fs, KEY_OPTION, KEY_FILE, TO_OPTION, RECIPIENT,
				AMOUNT_OPTION, "1", NONCE_OPTION + "=-1"), NONCE_OPTION);
		}
	}

	@Example
	@SneakyThrows
	public void refusesAKeyFileThatOthersCanRead() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.write(fs, KEY_FILE, HexFormat.of().formatHex(SEED) + "\n", "rw-r--r--");

			assertRefusedBeforeSending(refusedOn(fs, KEY_OPTION, KEY_FILE, TO_OPTION, RECIPIENT,
				AMOUNT_OPTION, "1", NONCE_OPTION, "0"), KEY_FILE);
		}
	}

	@Property(tries = 20)
	@SneakyThrows
	public void neverPrintsTheSeed(@ForAll("seeds") byte[] seed) {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, seed);

			final Outcome outcome = sendOn(fs, info(MINIMUM_FEE), account(0), accepted(), KEY_OPTION, KEY_FILE,
				TO_OPTION, RECIPIENT, AMOUNT_OPTION, "1", NONCE_OPTION, "0");
			final String hex = HexFormat.of().formatHex(seed);

			assertThat(outcome.cli().out(), not(containsString(hex)));
			assertThat(outcome.cli().err(), not(containsString(hex)));
		}
	}

	@Property(tries = 20)
	@SneakyThrows
	public void takesAnAccountInAnyCaseAsTheRecipient(@ForAll("upperCaseAccounts") String typed) {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, SEED);

			final Outcome outcome = sendOn(fs, info(MINIMUM_FEE), account(0), accepted(), KEY_OPTION, KEY_FILE,
				TO_OPTION, typed, AMOUNT_OPTION, "1", NONCE_OPTION, "0");

			assertThat(outcome.cli().exitCode(), equalTo(0));
			assertThat(transferIn(outcome).recipient(),
				equalTo(AccountKey.fromHex(typed.toLowerCase(Locale.ROOT))));
		}
	}

	private static final class FakeNode extends MockUp<RestClient> {
		private final Response info;
		private final Response account;
		private final Response submitted;
		private final List<Request> requests = new ArrayList<>();
		private boolean connected;
		private boolean closed;

		private FakeNode(Response info, Response account, Response submitted) {
			this.info = info;
			this.account = account;
			this.submitted = submitted;
			readBackItsEntity(info);
			readBackItsEntity(account);
			readBackItsEntity(submitted);
		}

		@Mock public void $init(String host, int port, boolean secure, String token, Duration readTimeout) {
			connected = true;
		}

		@Mock public Response get(String location) {
			return answer(HttpMethod.GET, location, null);
		}

		@Mock public Response post(String location, Entity<?> entity) {
			return answer(HttpMethod.POST, location, entity);
		}

		@Mock public void close() {
			closed = true;
		}

		private Response answer(String method, String location, Entity<?> entity) {
			requests.add(new Request(method, location, Optional.ofNullable(entity), Optional.empty()));

			return switch (location) {
				case "/ledger" -> info;
				case "/ledger/transaction" -> submitted;
				default -> account;
			};
		}

		private List<Request> requests() {
			return requests;
		}

		private boolean connected() {
			return connected;
		}

		private boolean closed() {
			return closed;
		}
	}
}
