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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.nio.file.FileSystem;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.jqwik.Expect;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Block;
import org.unigrid.hedgehog.ledger.BlockLog;
import org.unigrid.hedgehog.ledger.Certificate;
import org.unigrid.hedgehog.ledger.Decision;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.Ledger;
import org.unigrid.hedgehog.ledger.LedgerNode;
import org.unigrid.hedgehog.ledger.Mint;
import org.unigrid.hedgehog.ledger.Reference;
import org.unigrid.hedgehog.ledger.Timeouts;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.ledger.Transfer;
import org.unigrid.hedgehog.ledger.Vote;
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.model.network.codec.LedgerCodecs;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.server.rest.entity.LedgerAccount;
import org.unigrid.hedgehog.server.rest.entity.LedgerBlock;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.server.rest.entity.LedgerSubmission;
import org.unigrid.hedgehog.server.rest.entity.LedgerTransactionView;
import org.unigrid.hedgehog.service.ledger.LedgerService;
import org.unigrid.hedgehog.service.ledger.LedgerService.Report;
import org.unigrid.hedgehog.service.ledger.LedgerTestChain;
import org.unigrid.hedgehog.service.ledger.LegacyWallet;

/* The ledger as the node's REST interface shows it. The service is faked to hand out a real ledger that has been
   given real blocks, so that what is read is what a node would read, and the ledger it hands out can be changed
   between two requests as the node does when it replaces its ledger. What the ledger answers a submitted
   transaction is scripted, as the driver answers on its own thread whenever it likes. */
public class LedgerResourceTest extends BaseRestClientTest {
	private static final HexFormat HEX = HexFormat.of();
	private static final byte[] ALICE_SEED = LedgerTestChain.seed(11);
	private static final byte[] BOB_SEED = LedgerTestChain.seed(12);
	private static final AccountKey ALICE = Ed25519.publicKey(ALICE_SEED);
	private static final AccountKey BOB = Ed25519.publicKey(BOB_SEED);
	private static final AccountKey CAROL = Ed25519.publicKey(LedgerTestChain.seed(13));
	private static final AccountKey NEWCOMER = Ed25519.publicKey(LedgerTestChain.seed(14));
	private static final LegacyWallet WALLET = new LegacyWallet(7, true);
	private static final long TEN_COINS = 1_000_000_000L;
	private static final long OWED = 250_000_000L;
	private static final long THREE_COINS = 300_000_000L;
	private static final long FEE = 100_000L;
	private static final String NO_LEDGER = "The ledger is not running";
	private static final String SUBMIT = "/ledger/transaction";
	private static final byte[] CHAIN = LedgerTestChain.seed(99);
	private static final LegacyWallet WIDE_WALLET = new LegacyWallet(8, false);
	private static final int LONGEST_HEX = 2 * LedgerCodecs.MAX_TRANSACTION_SIZE;
	private static final int WAITING_AT_MOST = 16;
	private static final int MAX_BODY = 4096;

	/* In the hex of a transfer: the kind, the sender, the type and key of the recipient, then the amount */
	private static final int AMOUNT_AT = 2 * (1 + AccountKey.SIZE + 1 + AccountKey.SIZE);
	private static final Duration PATIENCE = Duration.ofSeconds(10);
	private static final List<String> REFUSALS = List.of("Transfer nonce is already used",
		"Balance does not cover the transfer", "Fee is below what this node demands",
		"Legacy address is already claimed", "The ledger node failed and was replaced: send it again",
		"Not queued: one waits for this nonce, or it is outside the window or not covered",
		"Could not check: \"quoted\", back\\slash, ö and a\nline break");

	/* The blocks of the chain the tests read, each made from what the ledger has at that point */
	private static final List<Function<Ledger, List<Transaction>>> BLOCKS = List.of(
		ledger -> List.of(new Mint(ALICE, TEN_COINS, reference(1)), new Mint(WALLET.address(), OWED, reference(2))),
		ledger -> List.of(Transfer.signed(ALICE_SEED, BOB, THREE_COINS, FEE, 0, ledger.chainId()),
			Vote.signed(LedgerTestChain.SEEDS.get(0), CAROL, Vote.Action.ADD, ledger.round(), ledger.chainId())),
		ledger -> List.of(WALLET.claim(CAROL, ledger.chainId())));

	@Inject
	private SporkDatabase sporkDatabase;

	private final List<Chain> chains = new ArrayList<>();
	private final List<Transaction> submitted = new CopyOnWriteArrayList<>();
	private final List<CompletableFuture<Optional<String>>> unanswered = new CopyOnWriteArrayList<>();
	private final List<Duration> waited = new CopyOnWriteArrayList<>();
	private volatile Ledger newest;
	private volatile Report reported;
	private volatile Answering answering;

	/* How the ledger answers a transaction handed to it: at once, or when the test lets it */
	private interface Answering extends Function<Transaction, CompletableFuture<Optional<String>>> {
	}

	private static Reference reference(final int n) {
		final byte[] bytes = new byte[Reference.SIZE];

		bytes[0] = (byte) n;
		return new Reference(bytes);
	}

	/* A ledger of four validators on an in-memory log, to which the validators' blocks are given as decisions, which
	   is how a node that is behind gets them */
	private static final class Chain implements AutoCloseable {
		private final FileSystem files = Jimfs.newFileSystem(Configuration.unix());
		private final BlockLog log;
		private final Ledger ledger;
		private final LedgerNode node;

		@SneakyThrows
		Chain() {
			final LongSupplier clock = () -> LedgerTestChain.CLOCK_START;

			log = BlockLog.open(files.getPath("/chain/blocks.log"));
			ledger = new Ledger(LedgerTestChain.state(), log, clock);
			node = new LedgerNode(ledger, null, Timeouts.DEFAULT, null, clock);
			node.start();
		}

		@SneakyThrows
		Block append(final List<Transaction> transactions) {
			transactions.forEach(transaction -> ledger.submit(transaction).ifPresent(reason -> {
				throw new IllegalStateException("The ledger refused " + transaction + ": " + reason);
			}));

			final long height = ledger.height() + 1;
			final Block proposed = ledger.propose(height, 0, ledger.validators().proposerAt(height),
				LedgerTestChain.CLOCK_START).orElseThrow();
			Block block = proposed.toBuilder().certificate(new Certificate(proposed.getView(), List.of())).build();

			for (final byte[] seed : LedgerTestChain.SEEDS.subList(0, ledger.validators().quorum())) {
				block = block.endorsedBy(seed, ledger.chainId());
			}

			node.onMessage(new Decision(block));
			return block;
		}

		@SneakyThrows
		@Override
		public void close() {
			log.close();
			files.close();
		}
	}

	private Chain chainOfBlocks(final int blocks) {
		final Chain chain = new Chain();

		chains.add(chain);

		for (int n = 0; n < blocks; n++) {
			chain.append(BLOCKS.get(n).apply(chain.ledger));
		}

		return chain;
	}

	private Ledger chainOf(final int blocks) {
		return chainOfBlocks(blocks).ledger;
	}

	private static String hex(final byte[] bytes) {
		return HEX.formatHex(bytes);
	}

	private static List<String> idsOf(final Block block) {
		return block.getTransactions().stream().map(transaction -> hex(transaction.id())).toList();
	}

	private static Report reportOf(final Ledger ledger) {
		return new Report(ledger != null, false, "", false, ledger == null ? 0 : ledger.height(),
			ledger == null ? "" : hex(ledger.tipHash()), 0, List.of(), List.of());
	}

	private void setMinimumFee(final String coins) {
		final LedgerFee spork = new LedgerFee();

		spork.<LedgerFee.SporkData>getData().setMinimumTransferFee(new BigDecimal(coins));
		sporkDatabase.setLedgerFee(spork);
	}

	private static Answering answeringWith(final Optional<String> answer) {
		return transaction -> CompletableFuture.completedFuture(answer);
	}

	private Answering neverAnswering() {
		return transaction -> {
			final CompletableFuture<Optional<String>> future = new CompletableFuture<>();

			unanswered.add(future);
			return future;
		};
	}

	/* A ledger that is still at it when the wait runs out: the wait ends at once, whatever it was asked to be, and is
	   noted, so that no test waits for the real time and the time asked is still known */
	private Answering outlastingTheWait() {
		return transaction -> {
			final CompletableFuture<Optional<String>> future = new CompletableFuture<>() {
				@Override
				public Optional<String> get(final long timeout, final TimeUnit unit) throws TimeoutException {
					waited.add(Duration.of(timeout, unit.toChronoUnit()));
					throw new TimeoutException();
				}
			};

			unanswered.add(future);
			return future;
		};
	}

	@BeforeTry
	public void fakeTheService() {
		newest = null;
		reported = null;
		answering = answeringWith(Optional.empty());
		submitted.clear();
		unanswered.clear();
		waited.clear();
		sporkDatabase.setLedgerFee(null);

		new MockUp<LedgerService>() {
			@Mock
			public Optional<Object> read(final Function<Ledger, Object> reading) {
				return Optional.ofNullable(newest).map(reading);
			}

			@Mock
			public Report status() {
				return Optional.ofNullable(reported).orElseGet(() -> reportOf(newest));
			}

			@Mock
			public CompletableFuture<Optional<String>> submit(final Transaction transaction) {
				submitted.add(transaction);
				return answering.apply(transaction);
			}
		};
	}

	@AfterTry
	public void answerWhatIsStillWaiting() {
		unanswered.forEach(future -> future.complete(Optional.empty()));
	}

	@AfterTry
	public void closeTheChains() {
		chains.forEach(Chain::close);
		chains.clear();
	}

	private static Transfer transfer(final long nonce) {
		return Transfer.signed(ALICE_SEED, BOB, THREE_COINS, FEE, nonce, CHAIN);
	}

	@SneakyThrows
	private Response submit(final String transaction) {
		return client.post(SUBMIT, Entity.json(new LedgerSubmission(transaction)));
	}

	private Response submit(final Transaction transaction) {
		return submit(hex(transaction.encode()));
	}

	private void sendWithoutReadingTheAnswer(final Transaction transaction) {
		try {
			submit(transaction);
		} catch (Exception ex) {
			assertThat(ex.getMessage(), not(startsWith("429 ")));
		}
	}

	private static String refusalOf(final Expect.Action submitting) {
		return refusedBy(submitting).getMessage();
	}

	private static ResponseOddityException refusedBy(final Expect.Action submitting) {
		return assertThrows(ResponseOddityException.class, submitting);
	}

	private static String errorBody(final String message) {
		return "{\"error\":\"" + message + "\"}";
	}

	private static Answering failing() {
		return transaction -> CompletableFuture.failedFuture(new IllegalStateException("The driver failed"));
	}

	private static Answering interruptedWhileWaiting() {
		return transaction -> {
			final CompletableFuture<Optional<String>> future = new CompletableFuture<>() {
				@Override
				public Optional<String> get(final long timeout, final TimeUnit unit) throws InterruptedException {
					throw new InterruptedException();
				}
			};

			future.complete(Optional.empty());
			return future;
		};
	}

	private List<String> submittedIds() {
		return submitted.stream().map(Transaction::id).map(LedgerResourceTest::hex).toList();
	}

	private static AtomicInteger countDecodings() {
		final AtomicInteger decodings = new AtomicInteger();

		new MockUp<WireCodec>() {
			@Mock
			public Transaction decodeTransaction(final Invocation invocation, final byte[] data) {
				decodings.incrementAndGet();
				return invocation.proceed();
			}
		};

		return decodings;
	}

	private Map<?, ?> failureOf(final String location, final Status expected) {
		final Response response = assertThrows(WebApplicationException.class,
			() -> client.getEntity(location, String.class)).getResponse();

		assertThat(location, Status.fromStatusCode(response.getStatus()), equalTo(expected));
		return response.readEntity(Map.class);
	}

	@SneakyThrows
	private LedgerInfo info() {
		return client.getEntity("/ledger", LedgerInfo.class);
	}

	@SneakyThrows
	private LedgerAccount account(final String address) {
		return client.getEntity("/ledger/account/" + address, LedgerAccount.class);
	}

	@SneakyThrows
	private LedgerBlock block(final long height) {
		return client.getEntity("/ledger/block/" + height, LedgerBlock.class);
	}

	@Example
	@SneakyThrows
	public void shouldTellThatNoLedgerRunsWhileThereIsNone() {
		assertThat(info(), equalTo(new LedgerInfo(false, false, "", false, null, 0, "", null, null, null, null, 0,
			List.of(), List.of())));
		assertThat(client.getEntity("/ledger", Map.class).keySet(), equalTo(Set.of("running", "halted", "haltReason",
			"signing", "height", "tipHash", "droppedInbound", "unsetSporks", "laggingSporks")));
	}

	@Example
	public void shouldDescribeTheChainOfTheNewestLedger() {
		final Ledger ledger = chainOf(3);

		newest = ledger;
		setMinimumFee("0.001");

		final LedgerInfo shown = info();

		assertThat(shown.running(), equalTo(true));
		assertThat(shown.halted(), equalTo(false));
		assertThat(shown.chainId(), equalTo(hex(ledger.chainId())));
		assertThat(shown.height(), equalTo(3L));
		assertThat(shown.tipHash(), equalTo(hex(ledger.tipHash())));
		assertThat(shown.supply(), equalTo("12.50000000"));
		assertThat(shown.round(), equalTo(ledger.round()));
		assertThat(shown.validators(), equalTo(LedgerTestChain.SEEDS.stream().map(Ed25519::publicKey)
			.map(AccountKey::toString).toList()));
		assertThat(shown.minimumFee(), equalTo("0.00100000"));
	}

	@Example
	@SneakyThrows
	public void shouldLeaveOutTheMinimumFeeWhileTheSporkIsUnset() {
		newest = chainOf(1);

		assertThat(info().minimumFee(), equalTo(null));
		assertThat(client.getEntity("/ledger", String.class), not(containsString("minimumFee")));
	}

	@Example
	@SneakyThrows
	public void shouldLeaveOutAMinimumFeeThatTheSporkCannotTell() {
		newest = chainOf(1);

		for (final String fee : List.of("-0.001", "1.00000001", "0.000000001")) {
			setMinimumFee(fee);

			assertThat(fee, info().running(), equalTo(true));
			assertThat(fee, info().height(), equalTo(1L));
			assertThat(fee, client.getEntity("/ledger", String.class), not(containsString("minimumFee")));
		}
	}

	@Example
	public void shouldPassOnWhatTheServiceReportsOfItsState() {
		newest = chainOf(2);
		reported = new Report(false, true, "The block log is behind", false, 2, "", 5, List.of(Type.LEDGER_FEE),
			List.of(Type.MINT_SUPPLY, Type.LEDGER_BLACKLIST));

		final LedgerInfo shown = info();

		assertThat(shown.running(), equalTo(false));
		assertThat(shown.halted(), equalTo(true));
		assertThat(shown.haltReason(), equalTo("The block log is behind"));
		assertThat(shown.droppedInbound(), equalTo(5L));
		assertThat(shown.unsetSporks(), equalTo(List.of("LEDGER_FEE")));
		assertThat(shown.laggingSporks(), equalTo(List.of("MINT_SUPPLY", "LEDGER_BLACKLIST")));
		assertThat(shown.height(), equalTo(2L));
	}

	@Example
	public void shouldShowTheBalanceNonceAndMintsOfAnAccount() {
		newest = chainOf(2);

		assertThat(account(ALICE.toString()), equalTo(new LedgerAccount(ALICE.toString(), "account", "6.99900000",
			1L, "10.00000000", null, null)));
		assertThat(account(BOB.toString()), equalTo(new LedgerAccount(BOB.toString(), "account", "3.00000000",
			0L, "0.00000000", null, null)));
	}

	@Example
	public void shouldShowAnAccountTheLedgerHasNeverSeenAsEmpty() {
		newest = chainOf(2);

		assertThat(account(NEWCOMER.toString()), equalTo(new LedgerAccount(NEWCOMER.toString(), "account",
			"0.00000000", 0L, "0.00000000", null, null)));
	}

	@Example
	public void shouldShowWhatALegacyAddressHoldsBeforeAndAfterItIsClaimed() {
		final Chain chain = chainOfBlocks(2);

		newest = chain.ledger;

		assertThat(account(WALLET.base58()), equalTo(new LedgerAccount(WALLET.base58(), "legacy", "2.50000000", null,
			"2.50000000", "2.50000000", null)));

		chain.append(BLOCKS.get(2).apply(chain.ledger));

		assertThat(account(WALLET.base58()), equalTo(new LedgerAccount(WALLET.base58(), "legacy", "0.00000000", null,
			"2.50000000", "0.00000000", CAROL.toString())));
		assertThat(account(CAROL.toString()).balance(), equalTo("2.50000000"));
	}

	@Example
	public void shouldRefuseAnAddressThatIsNeitherAnAccountNorALegacyAddress() {
		newest = chainOf(1);

		for (final String address : List.of(ALICE.toString().toUpperCase(), ALICE.toString().substring(1), "nonsense",
			WALLET.base58().substring(1), "1111111111111111111114oLvT2")) {

			final Map<?, ?> body = failureOf("/ledger/account/" + address, Status.BAD_REQUEST);

			assertThat(address, body, hasKey("error"));
			assertThat(address, body.get("error"), not(equalTo(NO_LEDGER)));
		}
	}

	@Example
	public void shouldAnswerThatTheLedgerIsNotRunningWhenThereIsNoneToAskAboutAnAccountOrABlock() {
		assertThat(failureOf("/ledger/account/" + ALICE, Status.SERVICE_UNAVAILABLE), equalTo(Map.of("error", NO_LEDGER)));
		assertThat(failureOf("/ledger/block/1", Status.SERVICE_UNAVAILABLE), equalTo(Map.of("error", NO_LEDGER)));
		assertThat(failureOf("/ledger/account/nonsense", Status.BAD_REQUEST), hasKey("error"));
		assertThat(failureOf("/ledger/block/abc", Status.BAD_REQUEST), hasKey("error"));
	}

	@Example
	public void shouldShowTheMintsOfABlock() {
		final Ledger ledger = chainOf(3);
		final Block first = ledger.block(1).orElseThrow();

		newest = ledger;

		final LedgerBlock shown = block(1);

		assertThat(shown.height(), equalTo(1L));
		assertThat(shown.view(), equalTo(0L));
		assertThat(shown.time(), equalTo(first.getTime()));
		assertThat(shown.hash(), equalTo(hex(first.hash())));
		assertThat(shown.previousHash(), equalTo(hex(ledger.chainId())));
		assertThat(shown.stateRoot(), equalTo(hex(first.getStateRoot())));
		assertThat(shown.transactionRoot(), equalTo(hex(first.getTransactionRoot())));
		assertThat(shown.proposer(), equalTo(first.getProposer().toString()));
		assertThat(shown.certifiedBy(), equalTo(first.getEndorsements().stream()
			.map(endorsement -> endorsement.signer().toString()).toList()));
		assertThat(shown.endorsements(), equalTo(3));
		assertThat(shown.transactions().stream().map(LedgerTransactionView::id).toList(), equalTo(idsOf(first)));
		assertThat(shown.transactions(), containsInAnyOrder(
			LedgerTransactionView.builder().id(hex(new Mint(ALICE, TEN_COINS, reference(1)).id())).kind("mint")
				.recipient(ALICE.toString()).amount("10.00000000").build(),
			LedgerTransactionView.builder().id(hex(new Mint(WALLET.address(), OWED, reference(2)).id())).kind("mint")
				.recipient(WALLET.base58()).amount("2.50000000").build()));
	}

	@Example
	public void shouldShowTheTransferAndTheVoteOfABlockWithEveryFieldOfTheirKind() {
		final Ledger ledger = chainOf(3);
		final Block second = ledger.block(2).orElseThrow();

		newest = ledger;

		final LedgerBlock shown = block(2);
		final Transfer transfer = Transfer.signed(ALICE_SEED, BOB, THREE_COINS, FEE, 0, ledger.chainId());
		final Vote vote = Vote.signed(LedgerTestChain.SEEDS.get(0), CAROL, Vote.Action.ADD, 0, ledger.chainId());

		assertThat(shown.previousHash(), equalTo(hex(ledger.block(1).orElseThrow().hash())));
		assertThat(shown.transactions().stream().map(LedgerTransactionView::id).toList(), equalTo(idsOf(second)));
		assertThat(shown.transactions(), containsInAnyOrder(
			LedgerTransactionView.builder().id(hex(transfer.id())).kind("transfer").sender(ALICE.toString())
				.recipient(BOB.toString()).amount("3.00000000").fee("0.00100000").nonce(0L).build(),
			LedgerTransactionView.builder().id(hex(vote.id())).kind("vote").voter(vote.voter().toString())
				.candidate(CAROL.toString()).action("add").round(0L).build()));
	}

	@Example
	public void shouldShowTheClaimOfABlockWithTheLegacyAddressItMoved() {
		final Ledger ledger = chainOf(3);

		newest = ledger;

		assertThat(block(3).transactions(), equalTo(List.of(
			LedgerTransactionView.builder().id(hex(WALLET.claim(CAROL, ledger.chainId()).id())).kind("claim")
				.recipient(CAROL.toString()).claimedAddress(WALLET.base58()).build())));
		assertThat(block(3).height(), equalTo(ledger.height()));
	}

	@Example
	public void shouldAnswerNotFoundForAHeightOutsideTheChain() {
		newest = chainOf(2);

		for (final String height : List.of("0", "-1", "3", "9223372036854775807", "-9223372036854775808")) {
			assertThat(height, failureOf("/ledger/block/" + height, Status.NOT_FOUND),
				equalTo(Map.of("error", "No block at height " + height)));
		}
	}

	@Example
	public void shouldRefuseAHeightThatIsNoNumberOfALong() {
		newest = chainOf(2);

		for (final String height : List.of("abc", "99999999999999999999", "-9223372036854775809", "1.5", "0x1", "1e3")) {
			assertThat(height, failureOf("/ledger/block/" + height, Status.BAD_REQUEST), hasKey("error"));
		}
	}

	/* The command line prints this text: the status line alone would not say what to change */
	@Example
	public void shouldKeepTheErrorTextOfARefusedRequestInTheExceptionTheClientThrows() {
		final ResponseOddityException height = assertThrows(ResponseOddityException.class,
			() -> client.get("/ledger/block/abc"));

		final ResponseOddityException notRunning = assertThrows(ResponseOddityException.class,
			() -> client.get("/ledger/account/" + ALICE));

		final ResponseOddityException transaction = assertThrows(ResponseOddityException.class, () -> submit("zz"));

		assertThat(height.getMessage(), startsWith("400 "));
		assertThat(height.getBody(), containsString("A height is a whole number"));
		assertThat(notRunning.getMessage(), startsWith("503 "));
		assertThat(notRunning.getBody(), equalTo("{\"error\":\"" + NO_LEDGER + "\"}"));
		assertThat(transaction.getMessage(), startsWith("400 "));
		assertThat(transaction.getBody(), startsWith("{\"error\":"));
	}

	@Example
	public void shouldReadTheNewestLedgerWhenTheNodeReplacesItBetweenTwoRequests() {
		newest = chainOf(1);

		assertThat(info().height(), equalTo(1L));
		assertThat(failureOf("/ledger/block/3", Status.NOT_FOUND), hasKey("error"));

		newest = chainOf(3);

		assertThat(info().height(), equalTo(3L));
		assertThat(block(3).height(), equalTo(3L));
		assertThat(account(CAROL.toString()).balance(), equalTo("2.50000000"));

		newest = null;

		assertThat(info().running(), equalTo(false));
		assertThat(failureOf("/ledger/block/3", Status.SERVICE_UNAVAILABLE), equalTo(Map.of("error", NO_LEDGER)));
		assertThat(failureOf("/ledger/account/" + CAROL, Status.SERVICE_UNAVAILABLE),
			equalTo(Map.of("error", NO_LEDGER)));
	}

	@Example
	public void shouldTakeASignedTransferAndGiveBackTheIdByWhichItIsFound() {
		final Transfer transfer = transfer(0);
		final Response response = submit(transfer);

		assertThat(response.getStatus(), equalTo(Status.ACCEPTED.getStatusCode()));
		assertThat(response.readEntity(Map.class), equalTo(Map.of("accepted", true, "id", hex(transfer.id()))));
		assertThat(submittedIds(), equalTo(List.of(hex(transfer.id()))));
	}

	@Example
	public void shouldTakeEveryKindOfTransactionAClientSignsAndTheLongestOneThereIs() {
		final List<Transaction> signed = List.of(
			Vote.signed(LedgerTestChain.SEEDS.get(0), CAROL, Vote.Action.ADD, 0, CHAIN),
			WALLET.claim(CAROL, CHAIN), WIDE_WALLET.claim(CAROL, CHAIN));

		assertThat(signed.getLast().encode().length, equalTo(LedgerCodecs.MAX_TRANSACTION_SIZE));

		for (final Transaction transaction : signed) {
			assertThat(submit(transaction).getStatus(), equalTo(Status.ACCEPTED.getStatusCode()));
		}

		assertThat(submittedIds(), equalTo(signed.stream().map(Transaction::id).map(LedgerResourceTest::hex).toList()));
	}

	@Example
	public void shouldGiveTheReasonOfALedgerThatRefusesATransactionWordForWord() {
		final Transfer transfer = transfer(0);

		for (final String reason : REFUSALS) {
			answering = answeringWith(Optional.of(reason));

			final Response response = submit(transfer);

			assertThat(reason, response.getStatus(), equalTo(Status.CONFLICT.getStatusCode()));
			assertThat(reason, response.readEntity(Map.class),
				equalTo(Map.of("accepted", false, "id", hex(transfer.id()), "reason", reason)));
		}

		assertThat(submittedIds().size(), equalTo(REFUSALS.size()));
	}

	@Example
	public void shouldAnswerThatTheLedgerIsNotRunningWhenTheLedgerSaysSo() {
		answering = answeringWith(Optional.of(NO_LEDGER));

		final ResponseOddityException refused = refusedBy(() -> submit(transfer(0)));

		assertThat(refused.getMessage(), startsWith("503 "));
		assertThat(refused.getBody(), equalTo(errorBody(NO_LEDGER)));
	}

	@Example
	public void shouldRefuseAMintBecauseMintsComeOnlyFromTheSporks() {
		final ResponseOddityException refused = refusedBy(() -> submit(new Mint(ALICE, TEN_COINS, reference(9))));

		assertThat(refused.getMessage(), startsWith("400 "));
		assertThat(refused.getBody(), equalTo(errorBody("A mint comes only from the sporks, not from a client")));
		assertThat(submitted, empty());
	}

	@Example
	public void shouldSayInTheErrorBodyWhyATransactionWasNotRead() {
		final String tooLong = "A transaction is at most " + LedgerCodecs.MAX_TRANSACTION_SIZE + " bytes";
		final ResponseOddityException missing = refusedBy(() -> client.post(SUBMIT, Entity.json("{}")));
		final ResponseOddityException longer = refusedBy(() -> submit("0".repeat(LONGEST_HEX + 1)));
		final ResponseOddityException notHex = refusedBy(() -> submit("zz"));

		assertThat(missing.getBody(), equalTo(errorBody("A transaction is sent as the hex of its encoding")));
		assertThat(longer.getBody(), equalTo(errorBody(tooLong)));
		assertThat(notHex.getBody(), startsWith("{\"error\":\""));
		assertThat(Stream.of(missing, longer, notHex).map(ResponseOddityException::getMessage).toList(),
			everyItem(startsWith("400 ")));
	}

	@Example
	public void shouldAnswerThatTheLedgerCouldNotAnswerAndFreeTheWaitOfEverySubmit() {
		answering = failing();

		for (int n = 0; n <= WAITING_AT_MOST; n++) {
			final long nonce = n;
			final ResponseOddityException refused = refusedBy(() -> submit(transfer(nonce)));

			assertThat(refused.getMessage(), startsWith("503 "));
			assertThat(refused.getBody(), equalTo(errorBody("The ledger could not answer")));
		}

		answering = answeringWith(Optional.empty());
		assertThat(submit(transfer(0)).getStatus(), equalTo(Status.ACCEPTED.getStatusCode()));
	}

	/* The thread of an interrupted wait cannot be counted on to finish writing the answer, so what is watched is that
	   every submit reached the ledger: one that was turned away for want of a wait would not have */
	@Example
	public void shouldFreeTheWaitOfASubmitThatIsInterrupted() {
		answering = interruptedWhileWaiting();

		for (int n = 0; n <= WAITING_AT_MOST; n++) {
			sendWithoutReadingTheAnswer(transfer(n));
		}

		assertThat(submitted.size(), equalTo(WAITING_AT_MOST + 1));
		answering = answeringWith(Optional.empty());
		assertThat(submit(transfer(0)).getStatus(), equalTo(Status.ACCEPTED.getStatusCode()));
	}

	@Example
	@SneakyThrows
	public void shouldRefuseWhatIsNoTransactionWithoutHandingItToTheLedger() {
		final String encoded = hex(transfer(0).encode());
		final List<String> bodies = List.of("{}", "{\"transaction\": null}", "{\"transaction\": 4}", "", "{",
			"[]", "{\"transaction\": \"\"}");
		final String withoutAmount = encoded.substring(0, AMOUNT_AT) + "0".repeat(2 * Long.BYTES)
			+ encoded.substring(AMOUNT_AT + 2 * Long.BYTES);
		final List<String> transactions = List.of("0", "abc", "zz", "0x04", " " + encoded, encoded + " ",
			encoded + "00", encoded.substring(0, encoded.length() - 2), "09" + encoded.substring(2),
			encoded.substring(0, 2), "05".repeat(40), withoutAmount);

		for (final String body : bodies) {
			assertThat(body, refusalOf(() -> client.post(SUBMIT, Entity.json(body))), startsWith("400 "));
		}

		for (final String transaction : transactions) {
			assertThat(transaction, refusalOf(() -> submit(transaction)), startsWith("400 "));
		}

		assertThat(submitted, empty());
	}

	@Example
	public void shouldNotDecodeAnythingLongerThanATransactionCanBe() {
		final AtomicInteger decodings = countDecodings();
		final String encoded = hex(WIDE_WALLET.claim(CAROL, CHAIN).encode());

		for (final String transaction : List.of("0".repeat(LONGEST_HEX + 1), "0".repeat(LONGEST_HEX + 2),
			encoded + "00", "0".repeat(MAX_BODY - LONGEST_HEX))) {

			assertThat(refusalOf(() -> submit(transaction)), startsWith("400 "));
		}

		assertThat(decodings.get(), equalTo(0));
		assertThat(refusalOf(() -> submit("05".repeat(LedgerCodecs.MAX_TRANSACTION_SIZE))), startsWith("400 "));
		assertThat(decodings.get(), equalTo(1));
		assertThat(submitted, empty());
	}

	@Example
	@SneakyThrows
	public void shouldReadNoMoreOfABodyThanATransactionNeedsAndSayWhenItIsTooLong() {
		final String opening = "{\"transaction\":\"" + hex(transfer(0).encode()) + "\"";
		final String atTheBound = opening + " ".repeat(MAX_BODY - opening.length() - 1) + "}";

		assertThat(atTheBound.length(), equalTo(MAX_BODY));
		assertThat(client.post(SUBMIT, Entity.json(atTheBound)).getStatus(), equalTo(Status.ACCEPTED.getStatusCode()));

		for (final String body : List.of(atTheBound + " ", "x".repeat(1 << 20),
			opening + ",\"padding\":\"" + "0".repeat(1 << 20) + "\"}")) {

			final ResponseOddityException refused = refusedBy(() -> client.post(SUBMIT, Entity.json(body)));

			assertThat(refused.getMessage(), startsWith("413 "));
			assertThat(refused.getBody(), equalTo(errorBody("A request is at most " + MAX_BODY + " bytes")));
		}

		assertThat(submitted.size(), equalTo(1));
	}

	@Example
	@SneakyThrows
	public void shouldRefuseTheSeventeenthSubmitThatWaitsAndTakeOneAgainOnceAnAnswerHasComeBack() {
		final ExecutorService callers = Executors.newFixedThreadPool(WAITING_AT_MOST);

		answering = neverAnswering();

		try {
			final List<Future<Response>> calls = IntStream.range(0, WAITING_AT_MOST)
				.mapToObj(n -> callers.submit(() -> submit(transfer(n)))).toList();

			await().atMost(PATIENCE).until(unanswered::size, equalTo(WAITING_AT_MOST));
			final ResponseOddityException refused = refusedBy(() -> submit(transfer(WAITING_AT_MOST)));

			assertThat(refused.getMessage(), startsWith("429 "));
			assertThat(refused.getBody(), equalTo(errorBody("Too many transactions wait for the ledger")));
			assertThat(submitted.size(), equalTo(WAITING_AT_MOST));

			unanswered.get(0).complete(Optional.empty());
			await().atMost(PATIENCE).until(() -> calls.stream().filter(Future::isDone).count(), equalTo(1L));
			answering = answeringWith(Optional.empty());
			assertThat(submit(transfer(WAITING_AT_MOST + 1)).getStatus(), equalTo(Status.ACCEPTED.getStatusCode()));

			unanswered.forEach(future -> future.complete(Optional.empty()));

			for (final Future<Response> call : calls) {
				assertThat(call.get().getStatus(), equalTo(Status.ACCEPTED.getStatusCode()));
			}
		} finally {
			unanswered.forEach(future -> future.complete(Optional.empty()));
			callers.shutdownNow();
		}
	}

	/* A submit that was answered 504 is still queued on the driver, so it keeps its wait until the ledger answers it */
	@Example
	public void shouldAnswerGatewayTimeoutAndKeepTheWaitOfTheSubmitUntilTheLedgerAnswers() {
		answering = outlastingTheWait();

		for (int n = 0; n < WAITING_AT_MOST; n++) {
			final long nonce = n;
			final ResponseOddityException timedOut = refusedBy(() -> submit(transfer(nonce)));

			assertThat(timedOut.getMessage(), startsWith("504 "));
			assertThat(timedOut.getBody(), equalTo(errorBody(
				"No answer from the ledger in time, it may still take the transaction")));
		}

		final ResponseOddityException turnedAway = refusedBy(() -> submit(transfer(WAITING_AT_MOST)));

		assertThat(waited, equalTo(Collections.nCopies(WAITING_AT_MOST, Duration.ofSeconds(10))));
		assertThat(turnedAway.getMessage(), startsWith("429 "));
		assertThat(submitted.size(), equalTo(WAITING_AT_MOST));
		assertThat(unanswered.stream().filter(CompletableFuture::isCancelled).count(), equalTo(0L));

		unanswered.get(0).complete(Optional.empty());
		answering = answeringWith(Optional.empty());
		assertThat(submit(transfer(WAITING_AT_MOST)).getStatus(), equalTo(Status.ACCEPTED.getStatusCode()));
	}
}
