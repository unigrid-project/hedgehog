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

package org.unigrid.hedgehog.service.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Delegate;
import mockit.Expectations;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.service.ledger.LedgerFleet.COIN;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.model.bootstrap.LedgerText;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.server.rest.RestServer;
import org.unigrid.hedgehog.server.rest.entity.LedgerAccount;
import org.unigrid.hedgehog.server.rest.entity.LedgerBlock;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmitted;
import org.unigrid.hedgehog.server.rest.entity.LedgerTransactionView;
import picocli.CommandLine;

/* The commands of `cli ledger` as a person runs them, against the REST servers of a running network: what a command
   shows is what its node answers, every transaction is signed by the command, with the key file of the holder or the
   signature of the legacy wallet, and the ledger of every node is looked at afterwards to see that what the command
   said happened did. The options of the command line are read from where picocli puts them, as a daemon does, and
   put back after each example. The fleet is shared by the examples, so each one has accounts and wallets of its own. */
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerCommandsAcceptanceTest extends BaseLedgerAcceptanceTest {
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final HexFormat HEX = HexFormat.of();
	private static final BigDecimal MINIMUM_FEE = new BigDecimal("0.001");
	private static final long FEE = 100_000L;
	private static final long ONE_AND_A_HALF = 150_000_000L;
	private static final String TEXT_TO_SIGN = "Text to sign: ";
	private static final String NOT_RUNNING = "The ledger is not running";
	private static final int FAILED = 1;
	private static final int MISUSED = 2;

	private FileSystem keys;

	@BeforeTry
	void openTheKeyFiles() {
		keys = LedgerKeyFixtures.posixFileSystem();
	}

	@AfterTry
	@SneakyThrows
	void closeTheKeyFiles() {
		keys.close();
	}

	/* Every spork that a node needs before it acts on a mint, a claim or a transfer is set, with a fee to see */
	@Override
	protected void ready() {
		awaitSigning();

		if (Objects.isNull(fleet().sporkOn(0, Type.LEDGER_FEE))) {
			final LedgerFee.SporkData fee = new LedgerFee.SporkData();

			fee.setMinimumTransferFee(MINIMUM_FEE);
			fleet().publishSpork(Type.LEDGER_FEE, fee);
		}

		if (Objects.isNull(fleet().sporkOn(0, Type.MINT_STORAGE))) {
			publishMints(Map.of());
		}

		super.ready();
	}

	/* The options are mocked in the base of the fleet tests, so their getters answer what the servers were set up with.
	   They read the fields instead, where picocli puts what the line says. */
	private static void readTheLine() {
		new Expectations() {{
			RestOptions.getHost(); minTimes = 0; result = fieldOf("host");
			RestOptions.getPort(); minTimes = 0; result = fieldOf("port");
			RestOptions.getToken(); minTimes = 0; result = fieldOf("token");
		}};
	}

	private static Delegate<Object> fieldOf(final String name) {
		return new Delegate<Object>() {
			@SneakyThrows
			Object read() {
				final Field field = RestOptions.class.getDeclaredField(name);

				field.setAccessible(true);
				return field.get(null);
			}
		};
	}

	private HedgehogCli.Result cli(final RestServer node, final String... command) {
		readTheLine();

		return HedgehogCli.run(() -> new CommandLine(Hedgehog.class).registerConverter(Path.class, keys::getPath),
			Stream.concat(Stream.of("cli", "-R", node.getHostName(), "-r", String.valueOf(node.getPort()),
				"--resttoken", node.getToken(), "ledger"), Stream.of(command)).toArray(String[]::new));
	}

	private HedgehogCli.Result cli(final int node, final String... command) {
		return cli(fleet().restOf(node), command);
	}

	@SneakyThrows
	private <T> T answer(final HedgehogCli.Result result, final Class<T> type) {
		assertThat(result.err(), result.exitCode(), is(0));
		result.assertNoStackTrace();
		return JSON.readValue(result.out(), type);
	}

	private void assertRefused(final HedgehogCli.Result result, final int exitCode, final String reason) {
		assertThat(result.out() + result.err(), result.exitCode(), is(exitCode));
		assertThat(result.err(), containsString(reason));
		result.assertNoStackTrace();
	}

	private String chain() {
		return HEX.formatHex(fleet().chainId());
	}

	private String keyFileOf(final byte[] seed) {
		return LedgerKeyFixtures.write(keys, "/keys/" + Ed25519.publicKey(seed), HEX.formatHex(seed) + "\n",
			LedgerKeyFixtures.OWNER_ONLY).toString();
	}

	private void fund(final AccountKey who, final long units) {
		publishMints(Map.of(entry(who, secondsAgo(60)), BigDecimal.valueOf(units, 8)));
		awaitBalance(who, units);
	}

	private List<String> validators() {
		return IntStream.range(0, fleet().validators()).mapToObj(n -> Ed25519.publicKey(fleet().seed(n)).toString())
			.toList();
	}

	private Map<String, Long> heldByValidators(final int node) {
		return validators().stream().collect(Collectors.toMap(Function.identity(), validator ->
			fleet().balanceOf(node, AccountKey.fromHex(validator))));
	}

	private Map<String, Long> paidSince(final int node, final Map<String, Long> before) {
		return heldByValidators(node).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, held ->
			held.getValue() - before.get(held.getKey())));
	}

	/* A fee is shared in equal parts by the validators of the last commit of the next block, which need not be the
	   ones of the certificate the block was stored with, and what does not divide goes a unit each to the first */
	private static List<Long> partsOf(final long fee, final int signers) {
		return IntStream.range(0, signers).mapToObj(n -> fee / signers + (n < fee % signers ? 1 : 0)).sorted()
			.toList();
	}

	private List<Long> paymentsSince(final int node, final Map<String, Long> before) {
		return paidSince(node, before).values().stream().filter(part -> part > 0).sorted().toList();
	}

	private LedgerBlock blockHolding(final String id, final long after) {
		final AtomicReference<LedgerBlock> found = new AtomicReference<>();

		await().atMost(PATIENCE).until(() -> {
			LongStream.rangeClosed(after + 1, fleet().height(0)).mapToObj(this::blockAt)
				.filter(block -> block.transactions().stream().anyMatch(held -> id.equals(held.id())))
				.findFirst().ifPresent(found::set);
			return Objects.nonNull(found.get());
		});

		return found.get();
	}

	private LedgerBlock blockAt(final long height) {
		return answer(cli(0, "block", String.valueOf(height)), LedgerBlock.class);
	}

	private String textToSign(final HedgehogCli.Result prompt, final LegacyWallet wallet) {
		assertThat(prompt.err(), prompt.exitCode(), is(0));

		final String text = prompt.out().lines().filter(line -> line.startsWith(TEXT_TO_SIGN))
			.map(line -> line.substring(TEXT_TO_SIGN.length())).findFirst().orElseThrow();

		assertThat(prompt.out(), containsString("signmessage \"" + wallet.base58() + "\" \"" + text + "\""));
		return text;
	}

	/* The wallet signs the text that the command prints, which is all the claim is made from */
	private void claimByTheCommand(final int wallet) {
		final LegacyWallet legacy = fleet().wallet(wallet);
		final AccountKey owner = account();
		final long holding = fleet().holding(wallet);
		final List<String> line = List.of("claim", "--chain", chain(), "--address", legacy.base58(), "--to",
			owner.toString());
		final String text = textToSign(cli(1, line.toArray(String[]::new)), legacy);
		final String signature = Base64.getEncoder().encodeToString(legacy.sign(text));
		final String[] signed = Stream.concat(line.stream(), Stream.of("--signature", signature))
			.toArray(String[]::new);

		assertThat(text, equalTo(Claim.message(fleet().chainId(), owner)));
		assertThat(answer(cli(3, "balance", legacy.base58()), LedgerAccount.class).claimable(),
			equalTo(LedgerText.coins(holding)));
		assertThat(answer(cli(0, signed), LedgerSubmitted.class).accepted(), is(true));
		awaitBalance(owner, holding);
		fleet().awaitSameChain();

		final LedgerAccount spent = answer(cli(3, "balance", legacy.base58()), LedgerAccount.class);

		assertThat(answer(cli(2, "balance", owner.toString()), LedgerAccount.class).balance(),
			equalTo(LedgerText.coins(holding)));
		assertThat(spent.claimedBy(), equalTo(owner.toString()));
		assertThat(spent.claimable(), equalTo(LedgerText.coins(0)));
		assertThat(spent.balance(), equalTo(LedgerText.coins(0)));
		assertThat(balances(legacy.address()), everyItem(is(0L)));
		assertRefused(cli(2, signed), FAILED, "Legacy address is already claimed");
	}

	@Example
	public void shouldShowTheChainTheTipTheSupplyTheValidatorsAndTheMinimumFeeOfEveryNode() {
		ready();
		fleet().awaitSameChain();

		IntStream.range(0, fleet().size()).forEach(node -> {
			final LedgerInfo info = answer(cli(node, "info"), LedgerInfo.class);

			assertThat(info.running(), is(true));
			assertThat(info.chainId(), equalTo(chain()));
			assertThat(info.height(), equalTo(fleet().height(node)));
			assertThat(info.supply(), equalTo(LedgerText.coins(fleet().supplyOn(node))));
			assertThat(info.validators(), containsInAnyOrder(validators().toArray(String[]::new)));
			assertThat(info.minimumFee(), equalTo(LedgerText.coins(FEE)));
			assertThat(info.signing(), is(node < fleet().validators()));
		});
	}

	@Example
	public void shouldSendTheTransferOfAKeyFileWithTheMinimumFeeAndPayTheFeeToTheSigners() {
		final byte[] seed = newSeed();
		final AccountKey sender = Ed25519.publicKey(seed);
		final AccountKey recipient = account();
		final String key = keyFileOf(seed);
		final int quorum = fleet().validators() * 2 / 3 + 1;

		ready();
		fund(sender, 10 * COIN);

		final long supply = fleet().supplyOn(0);
		final long height = fleet().height(0);
		final Map<String, Long> before = heldByValidators(0);
		final LedgerAccount account = answer(cli(0, "balance", sender.toString()), LedgerAccount.class);

		assertThat(account.balance(), equalTo(LedgerText.coins(10 * COIN)));
		assertThat(account.nonce(), equalTo(0L));

		final LedgerSubmitted sent = answer(cli(1, "transfer", "--chain", chain(), "--key", key, "--to",
			recipient.toString(), "--amount", "1.5"), LedgerSubmitted.class);

		assertThat(sent.accepted(), is(true));
		awaitBalance(recipient, ONE_AND_A_HALF);
		awaitBalance(sender, 10 * COIN - ONE_AND_A_HALF - FEE);

		final LedgerBlock block = blockHolding(sent.id(), height);

		assertThat(block.transactions(), hasItem(LedgerTransactionView.builder().id(sent.id()).kind("transfer")
			.sender(sender.toString()).recipient(recipient.toString()).amount(LedgerText.coins(ONE_AND_A_HALF))
			.fee(LedgerText.coins(FEE)).nonce(0L).build()));
		assertThat(block.endorsements(), greaterThanOrEqualTo(quorum));
		assertThat(validators(), hasItem(block.proposer()));
		await().atMost(PATIENCE).until(() -> everywhere(node -> paidSince(node, before).values().stream()
			.mapToLong(Long::longValue).sum() == FEE));
		IntStream.range(0, fleet().size()).forEach(node -> {
			final List<Long> payments = paymentsSince(node, before);

			assertThat("node " + node, payments, equalTo(partsOf(FEE, payments.size())));
			assertThat("node " + node, payments.size(), greaterThanOrEqualTo(quorum));
			assertThat("node " + node, paidSince(node, before), equalTo(paidSince(0, before)));
		});
		assertThat(everywhere(node -> fleet().supplyOn(node) == supply), is(true));

		assertRefused(cli(2, "transfer", "--chain", chain(), "--key", key, "--to", recipient.toString(),
			"--amount", "1", "--nonce", "0"), FAILED, "Transfer nonce is already used");
		assertThat(balances(recipient), everyItem(is(ONE_AND_A_HALF)));
		assertThat(answer(cli(3, "balance", sender.toString()), LedgerAccount.class).nonce(), equalTo(1L));
	}

	@Example
	public void shouldMoveTheBalanceOfALegacyAddressOnceByTheSignatureOfTheWalletThatTheCommandAsksFor() {
		ready();

		final long supply = fleet().supplyOn(0);

		claimByTheCommand(0);
		claimByTheCommand(1);
		assertThat(everywhere(node -> fleet().supplyOn(node) == supply), is(true));
	}

	@Example
	public void shouldCastTheVoteOfAValidatorAndRefuseTheKeyOfAnAccountThatIsNone() {
		final byte[] validator = fleet().seed(2);
		final AccountKey candidate = account();

		ready();

		final long height = fleet().height(0);
		final LedgerInfo info = answer(cli(0, "info"), LedgerInfo.class);
		final LedgerSubmitted sent = answer(cli(3, "vote", "--chain", chain(), "--key", keyFileOf(validator),
			"--candidate", candidate.toString(), "--add"), LedgerSubmitted.class);

		assertThat(sent.accepted(), is(true));
		assertThat(blockHolding(sent.id(), height).transactions(), hasItem(LedgerTransactionView.builder()
			.id(sent.id()).kind("vote").voter(Ed25519.publicKey(validator).toString())
			.candidate(candidate.toString()).action("add").round(info.round()).build()));
		assertThat(answer(cli(1, "info"), LedgerInfo.class).validators(), not(hasItem(candidate.toString())));

		assertRefused(cli(0, "vote", "--chain", chain(), "--key", keyFileOf(newSeed()), "--candidate",
			candidate.toString(), "--add"), MISUSED, "This key is not a validator of the chain");
	}

	@Example
	public void shouldSendNothingWhenTheNodeIsOnAnotherChainThanTheOneNamed() {
		final byte[] other = fleet().chainId();

		other[0] ^= 1;

		final String wrong = HEX.formatHex(other);
		final String reason = "The node is on chain " + chain() + ", not on " + wrong;
		final String key = keyFileOf(newSeed());
		final String account = account().toString();

		assertRefused(cli(2, "transfer", "--chain", wrong, "--key", key, "--to", account, "--amount", "1"),
			FAILED, reason);
		assertRefused(cli(2, "vote", "--chain", wrong, "--key", keyFileOf(fleet().seed(0)), "--candidate", account,
			"--add"), FAILED, reason);

		final HedgehogCli.Result claim = cli(2, "claim", "--chain", wrong, "--address",
			fleet().wallet(2).base58(), "--to", account);

		assertRefused(claim, FAILED, reason);
		assertThat(claim.out(), not(containsString(TEXT_TO_SIGN)));
	}

	@Example
	public void shouldAnswerThatTheLedgerIsNotRunningFromANodeThatRunsNone() {
		final RestServer bare = fleet().restOf(servers().size() - 1);
		final String key = keyFileOf(newSeed());
		final String account = account().toString();

		final LedgerInfo info = answer(cli(bare, "info"), LedgerInfo.class);

		assertThat(info.running(), is(false));
		assertThat(info.chainId(), nullValue());
		assertRefused(cli(bare, "balance", account), FAILED, NOT_RUNNING);
		assertRefused(cli(bare, "block", "1"), FAILED, NOT_RUNNING);
		assertRefused(cli(bare, "transfer", "--key", key, "--to", account, "--amount", "1"), FAILED, NOT_RUNNING);
		assertRefused(cli(bare, "vote", "--key", key, "--candidate", account, "--add"), FAILED, NOT_RUNNING);
		assertRefused(cli(bare, "claim", "--address", fleet().wallet(3).base58(), "--to", account), FAILED,
			NOT_RUNNING);
	}
}
