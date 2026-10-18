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
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.LegacySignature;
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmission;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmitted;
import org.unigrid.hedgehog.service.ledger.LegacyWallet;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerClaimCommandTest {
	private static final String CHAIN_ID = "11".repeat(64);
	private static final String OTHER_CHAIN_ID = "22".repeat(64);
	private static final byte[] CHAIN = HexFormat.of().parseHex(CHAIN_ID);
	private static final byte[] OTHER_CHAIN = HexFormat.of().parseHex(OTHER_CHAIN_ID);
	private static final String ACCOUNT = "ab".repeat(32);
	private static final String OTHER_ACCOUNT = "cd".repeat(32);
	private static final AccountKey RECIPIENT = AccountKey.fromHex(ACCOUNT);
	private static final String TEXT = "claim " + CHAIN_ID + " " + ACCOUNT;
	private static final String ID = "9f".repeat(64);
	private static final int SECRET = 7;
	private static final int SECRET_OF_THE_FAKE = 4242;
	private static final String ADDRESS_OPTION = "--address";
	private static final String TO_OPTION = "--to";
	private static final String SIGNATURE_OPTION = "--signature";
	private static final String CHAIN_OPTION = "--chain";
	private static final String NOT_BASE64 = "The signature is not 65 bytes of base64";
	private static final String MADE_WITH = "The signature was made with the key of ";

	private record BeforeConnecting(Result sent, HedgehogCli.Result cli) { }

	private record Outcome(List<Request> requests, boolean connected, boolean closed, HedgehogCli.Result cli) { }

	private static String[] claim(String... arguments) {
		return Stream.concat(Stream.of("cli", "ledger", "claim"), Stream.of(arguments)).toArray(String[]::new);
	}

	private static String[] signed(LegacyWallet wallet, byte[] signature, String... more) {
		return Stream.concat(Stream.of(ADDRESS_OPTION, wallet.base58(), TO_OPTION, ACCOUNT, SIGNATURE_OPTION,
			Base64.getEncoder().encodeToString(signature)), Stream.of(more)).toArray(String[]::new);
	}

	private static BeforeConnecting refusedBeforeConnecting(String... arguments) {
		final AtomicReference<HedgehogCli.Result> cli = new AtomicReference<>();
		final Result sent = RestCommandFixture.run(() -> cli.set(HedgehogCli.run(claim(arguments))),
			Response.ok().build());

		return new BeforeConnecting(sent, cli.get());
	}

	private static Outcome ask(Response info, Response submitted, String... arguments) {
		final FakeLedgerNode node = new FakeLedgerNode(info, Response.ok().build(), submitted);
		final HedgehogCli.Result cli = HedgehogCli.run(claim(arguments));

		return new Outcome(List.copyOf(node.requests()), node.connected(), node.closed(), cli);
	}

	private static Response info() {
		final LedgerInfo info = new LedgerInfo(true, false, "", true, CHAIN_ID, 3, "22".repeat(64), "12.50000000",
			7L, List.of(), "0.00100000", 0, List.of(), List.of());

		return Response.ok(info).build();
	}

	private static Response noLedger() {
		final LedgerInfo info = new LedgerInfo(false, false, null, false, null, 0, null, null, null, null,
			null, 0, List.of(), List.of());

		return Response.ok(info).build();
	}

	private static Response accepted() {
		return Response.accepted(new LedgerSubmitted(true, ID, null)).build();
	}

	private static Claim claimIn(Outcome outcome) {
		final Request posted = outcome.requests().stream()
			.filter(request -> HttpMethod.POST.equals(request.method())).findFirst().orElseThrow();
		final LedgerSubmission submission = (LedgerSubmission) posted.entity().orElseThrow().getEntity();

		return (Claim) WireCodec.decodeTransaction(HexFormat.of().parseHex(submission.transaction()));
	}

	private static void assertNothingPosted(Outcome outcome) {
		assertThat(outcome.requests().stream().anyMatch(request -> HttpMethod.POST.equals(request.method())),
			equalTo(false));
	}

	private static void assertRefused(Outcome outcome, int exitCode, String message) {
		assertThat(outcome.cli().exitCode(), equalTo(exitCode));
		assertThat(outcome.cli().err(), containsString(message));
		assertThat(outcome.cli().out(), emptyString());
		assertNothingPosted(outcome);
		outcome.cli().assertNoStackTrace();
	}

	private static void assertRefusedBeforeConnecting(BeforeConnecting outcome, String message) {
		assertThat(outcome.sent().request(), equalTo(Optional.empty()));
		assertThat(outcome.sent().connection(), equalTo(Optional.empty()));
		assertThat(outcome.cli().exitCode(), equalTo(2));
		assertThat(outcome.cli().out(), emptyString());
		assertThat(outcome.cli().err(), containsString(message));
		outcome.cli().assertNoStackTrace();
	}

	@Property(tries = 20)
	public void printsTheTextToSignAndTheLineForTheWalletAndSendsNothing(
		@ForAll @IntRange(min = 1, max = 100_000) int secret, @ForAll boolean compressed) {

		final LegacyWallet wallet = new LegacyWallet(secret, compressed);
		final Outcome outcome = ask(info(), accepted(), ADDRESS_OPTION, wallet.base58(), TO_OPTION, ACCOUNT);

		assertThat(outcome.cli().exitCode(), equalTo(0));
		assertThat(outcome.cli().err(), emptyString());
		assertThat(outcome.cli().out().lines().toList(), equalTo(List.of("Text to sign: " + TEXT,
			"Run this in the legacy wallet, then run this command again with --signature <what it prints>:",
			"signmessage \"" + wallet.base58() + "\" \"" + TEXT + "\"")));
		assertThat(outcome.requests().stream().map(Request::location).toList(), equalTo(List.of("/ledger")));
		assertThat(outcome.closed(), equalTo(true));
	}

	@Example
	public void writesTheAccountInLowerCaseWhateverCaseItWasTyped() {
		final LegacyWallet wallet = new LegacyWallet(SECRET, true);
		final Outcome outcome = ask(info(), accepted(), ADDRESS_OPTION, wallet.base58(), TO_OPTION,
			ACCOUNT.toUpperCase(Locale.ROOT));

		assertThat(outcome.cli().exitCode(), equalTo(0));
		assertThat(outcome.cli().out(), containsString("Text to sign: " + TEXT + "\n"));
		assertThat(outcome.cli().out(), not(containsString(ACCOUNT.toUpperCase(Locale.ROOT))));
	}

	@Example
	public void takesASignatureWithTheBlanksItWasCopiedWith() {
		final LegacyWallet wallet = new LegacyWallet(SECRET, true);
		final byte[] signature = wallet.sign(TEXT);
		final Outcome outcome = ask(info(), accepted(), ADDRESS_OPTION, wallet.base58(), TO_OPTION, ACCOUNT,
			SIGNATURE_OPTION, " " + Base64.getEncoder().encodeToString(signature) + "\n");

		assertThat(outcome.cli().exitCode(), equalTo(0));
		assertThat(claimIn(outcome).signature(), equalTo(signature));
	}

	@Property(tries = 20)
	public void sendsTheClaimOfTheSignatureOfTheWallet(@ForAll @IntRange(min = 1, max = 100_000) int secret,
		@ForAll boolean compressed) {

		final LegacyWallet wallet = new LegacyWallet(secret, compressed);
		final byte[] signature = wallet.sign(TEXT);
		final Outcome outcome = ask(info(), accepted(), signed(wallet, signature));
		final Claim claim = claimIn(outcome);

		assertThat(outcome.cli().exitCode(), equalTo(0));
		assertThat(outcome.cli().err(), emptyString());
		assertThat(outcome.cli().out(), containsString(ID));
		assertThat(claim.publicKey(), equalTo(wallet.publicKey()));
		assertThat(claim.recipient(), equalTo(RECIPIENT));
		assertThat(claim.signature(), equalTo(signature));
		assertThat(claim.hasValidSignature(CHAIN), equalTo(true));
		assertThat(outcome.connected(), equalTo(true));
		assertThat(outcome.closed(), equalTo(true));
	}

	@Example
	public void refusesASignatureOfAnotherTextBeforeSendingAnything() {
		for (final boolean compressed : List.of(true, false)) {
			final LegacyWallet wallet = new LegacyWallet(SECRET, compressed);

			for (final String text : List.of(Claim.message(OTHER_CHAIN, RECIPIENT),
				Claim.message(CHAIN, AccountKey.fromHex(OTHER_ACCOUNT)), TEXT + "\n", TEXT + " ", " " + TEXT,
				TEXT.toUpperCase(Locale.ROOT))) {

				final Outcome outcome = ask(info(), accepted(), signed(wallet, wallet.sign(text)));

				assertRefused(outcome, 2, MADE_WITH);
				assertThat(outcome.cli().err(), containsString(", not " + wallet.base58()));
				assertThat(outcome.cli().err(), containsString("another text than " + TEXT));
			}
		}
	}

	@Example
	public void refusesASignatureOfAnotherWalletAndNamesItsAddress() {
		for (final boolean compressed : List.of(true, false)) {
			final LegacyWallet wallet = new LegacyWallet(SECRET, compressed);
			final LegacyWallet other = new LegacyWallet(SECRET + 1, compressed);

			final Outcome outcome = ask(info(), accepted(), signed(wallet, other.sign(TEXT)));

			assertRefused(outcome, 2, MADE_WITH + other.base58() + ", not " + wallet.base58());
		}
	}

	@Example
	public void refusesASignatureThatIsNoneOfThisTextBeforeSendingAnything() {
		final LegacyWallet wallet = new LegacyWallet(SECRET, true);
		final byte[] unknownHeader = wallet.sign(TEXT);
		unknownHeader[0] = 35;

		for (final byte[] signature : List.of(new byte[LegacySignature.SIZE], unknownHeader)) {
			assertRefused(ask(info(), accepted(), signed(wallet, signature)), 2,
				"The signature is not one of this text: was it made for " + TEXT + "?");
		}
	}

	@Example
	public void refusesASignatureThatIsNotSixtyFiveBytesOfBase64BeforeConnecting() {
		final LegacyWallet wallet = new LegacyWallet(SECRET, true);
		final Base64.Encoder base64 = Base64.getEncoder();

		for (final String typed : List.of("", "not base64", "AAAA%", base64.encodeToString(new byte[64]),
			base64.encodeToString(new byte[66]))) {

			assertRefusedBeforeConnecting(refusedBeforeConnecting(ADDRESS_OPTION, wallet.base58(), TO_OPTION,
				ACCOUNT, SIGNATURE_OPTION, typed), NOT_BASE64);
		}
	}

	@Example
	public void refusesAnAddressThatIsNoLegacyAddressBeforeConnecting() {
		for (final String typed : List.of("", "nonsense", ACCOUNT, ACCOUNT.toUpperCase(Locale.ROOT))) {
			assertRefusedBeforeConnecting(refusedBeforeConnecting(ADDRESS_OPTION, typed, TO_OPTION, ACCOUNT),
				"--address is not a legacy address");
		}
	}

	@Example
	public void refusesARecipientThatIsNoLedgerAccountBeforeConnecting() {
		final LegacyWallet wallet = new LegacyWallet(SECRET, true);

		for (final String typed : List.of("", "nonsense", wallet.base58())) {
			assertRefusedBeforeConnecting(refusedBeforeConnecting(ADDRESS_OPTION, wallet.base58(), TO_OPTION,
				typed), "--to is not a ledger account");
		}
	}

	@Example
	public void printsTheReasonOfAClaimTheLedgerRefuses() {
		final LegacyWallet wallet = new LegacyWallet(SECRET, true);
		final Response refused = Response.status(Status.CONFLICT)
			.entity(new LedgerSubmitted(false, ID, "Legacy address is already claimed")).build();

		final Outcome outcome = ask(info(), refused, signed(wallet, wallet.sign(TEXT)));

		assertThat(outcome.cli().exitCode(), equalTo(1));
		assertThat(outcome.cli().out(), emptyString());
		assertThat(outcome.cli().err().lines().toList(), equalTo(List.of("Legacy address is already claimed")));
		outcome.cli().assertNoStackTrace();
	}

	@Example
	public void refusesWhenTheNodeHasNoLedger() {
		final LegacyWallet wallet = new LegacyWallet(SECRET, true);

		for (final String[] arguments : List.of(new String[] { ADDRESS_OPTION, wallet.base58(), TO_OPTION, ACCOUNT },
			signed(wallet, wallet.sign(TEXT)))) {

			assertRefused(ask(noLedger(), accepted(), arguments), 1, "The ledger is not running");
		}
	}

	@Example
	public void takesTheChainThatIsGivenInAnyCase() {
		final LegacyWallet wallet = new LegacyWallet(SECRET, true);

		for (final String typed : List.of(CHAIN_ID, CHAIN_ID.toUpperCase(Locale.ROOT))) {
			final Outcome outcome = ask(info(), accepted(), signed(wallet, wallet.sign(TEXT), CHAIN_OPTION, typed));

			assertThat(outcome.cli().exitCode(), equalTo(0));
			assertThat(claimIn(outcome).hasValidSignature(CHAIN), equalTo(true));
		}
	}

	@Example
	public void refusesAChainThatIsNotTheOneOfTheNodeBeforeSayingWhatToSign() {
		final LegacyWallet wallet = new LegacyWallet(SECRET, true);
		final String[] question = { ADDRESS_OPTION, wallet.base58(), TO_OPTION, ACCOUNT, CHAIN_OPTION, OTHER_CHAIN_ID };
		final String[] answer = signed(wallet, wallet.sign(TEXT), CHAIN_OPTION, OTHER_CHAIN_ID);

		for (final String[] arguments : List.of(question, answer)) {
			final Outcome outcome = ask(info(), accepted(), arguments);

			assertRefused(outcome, 1, "The node is on chain " + CHAIN_ID + ", not on " + OTHER_CHAIN_ID);
			assertThat(outcome.cli().err().lines().count(), equalTo(1L));
		}
	}

	@Example
	public void refusesAChainThatIsNoChainIdBeforeConnecting() {
		final LegacyWallet wallet = new LegacyWallet(SECRET, true);

		for (final String typed : List.of("1".repeat(127), "zz".repeat(64))) {
			assertRefusedBeforeConnecting(refusedBeforeConnecting(ADDRESS_OPTION, wallet.base58(), TO_OPTION,
				ACCOUNT, CHAIN_OPTION, typed), CHAIN_OPTION);
		}
	}

	@Example
	public void refusesASignatureThatDoesNotVerifyForThisChainEvenWhenTheKeyIsTheOneOfTheAddress() {
		final LegacyWallet wallet = new LegacyWallet(SECRET_OF_THE_FAKE, true);
		final byte[] ofAnotherChain = wallet.sign(Claim.message(OTHER_CHAIN, RECIPIENT));
		final FakeLedgerNode node = new FakeLedgerNode(info(), Response.ok().build(), accepted());

		/* The fake outlives the example, so it answers only for the one signature that no other example makes */
		new MockUp<LegacySignature>() {
			@Mock public Optional<byte[]> recover(Invocation invocation, String text, byte[] signature) {
				return Arrays.equals(signature, ofAnotherChain) ? Optional.of(wallet.publicKey())
					: invocation.proceed();
			}
		};

		final HedgehogCli.Result cli = HedgehogCli.run(claim(signed(wallet, ofAnotherChain)));

		assertThat(cli.exitCode(), equalTo(2));
		assertThat(cli.err(), containsString("The signature does not verify for " + TEXT));
		assertThat(node.requests().stream().map(Request::method).toList(), equalTo(List.of(HttpMethod.GET)));
		cli.assertNoStackTrace();
	}
}
