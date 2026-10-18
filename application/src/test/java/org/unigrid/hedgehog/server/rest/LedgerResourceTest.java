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
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.nio.file.FileSystem;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
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
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.server.rest.entity.LedgerAccount;
import org.unigrid.hedgehog.server.rest.entity.LedgerBlock;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.server.rest.entity.LedgerTransactionView;
import org.unigrid.hedgehog.service.ledger.LedgerService;
import org.unigrid.hedgehog.service.ledger.LedgerService.Report;
import org.unigrid.hedgehog.service.ledger.LedgerTestChain;
import org.unigrid.hedgehog.service.ledger.LegacyWallet;

/* The ledger as the node's REST interface shows it. The service is faked to hand out a real ledger that has been
   given real blocks, so that what is read is what a node would read, and the ledger it hands out can be changed
   between two requests as the node does when it replaces its ledger. */
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

	/* The blocks of the chain the tests read, each made from what the ledger has at that point */
	private static final List<Function<Ledger, List<Transaction>>> BLOCKS = List.of(
		ledger -> List.of(new Mint(ALICE, TEN_COINS, reference(1)), new Mint(WALLET.address(), OWED, reference(2))),
		ledger -> List.of(Transfer.signed(ALICE_SEED, BOB, THREE_COINS, FEE, 0, ledger.chainId()),
			Vote.signed(LedgerTestChain.SEEDS.get(0), CAROL, Vote.Action.ADD, ledger.round(), ledger.chainId())),
		ledger -> List.of(WALLET.claim(CAROL, ledger.chainId())));

	@Inject
	private SporkDatabase sporkDatabase;

	private final List<Chain> chains = new ArrayList<>();
	private volatile Ledger newest;
	private volatile Report reported;

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

	@BeforeTry
	public void fakeTheService() {
		newest = null;
		reported = null;
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
		};
	}

	@AfterTry
	public void closeTheChains() {
		chains.forEach(Chain::close);
		chains.clear();
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
}
