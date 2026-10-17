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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.service.ledger.FleetContainers.bean;
import static org.unigrid.hedgehog.service.ledger.LedgerFleet.COIN;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Address;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.Mint;
import org.unigrid.hedgehog.ledger.Reference;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.ledger.Transfer;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

/* A legacy address holds what the bootstrap snapshot says, on a running network: validators and an observer that dial
   each other over QUIC, each with its own spork database and ledger service. The owner of the address moves it to a
   ledger account by a claim signed with the legacy key; every node judges the claim against the snapshot, its
   signature and its blacklist, and once a block holds it the address is spent for good: another claim, a mint and a
   transfer find nothing to take, on the nodes that were there and on one that joins later. What a claim does not say
   is an amount (the snapshot says it), and what a transfer cannot name is a legacy address as the sender (it names a
   key), so those two are shown through what the account gets and through a sender that only looks like the address.
   The fleet is shared by the examples, so each one has wallets and accounts of its own and leaves nothing waiting. */
public class LedgerClaimAcceptanceTest extends BaseLedgerFleetTest {
	private static final int WALLETS = 12;
	private static final BigDecimal CEILING = BigDecimal.valueOf(1_000_000_000L);
	private static final Duration PATIENCE = Duration.ofSeconds(60);
	private static final Duration QUIET = Duration.ofMillis(2_500);
	private static final Optional<String> CLAIMED = Optional.of("Legacy address is already claimed");
	private static final Optional<String> BAD_SIGNATURE = Optional.of("Claim signature is not valid");
	private static final Optional<String> NOTHING = Optional.of("Nothing to claim");
	private static final Optional<String> HELD_BACK = Optional.of("Claim is not authorized");
	private static final Optional<String> WAITING = Optional.of("Already waiting for a block");
	private static final Optional<String> USED = Optional.of("Reference already used");
	private static final Optional<String> NO_MINT = Optional.of("Mint is not authorized");
	private static final Optional<String> NOT_COVERED = Optional.of("Balance does not cover the transfer");
	private static final AtomicInteger NEXT = new AtomicInteger(50_000);

	private static byte[] newSeed() {
		return LedgerTestChain.seed(NEXT.incrementAndGet());
	}

	private static AccountKey account() {
		return Ed25519.publicKey(newSeed());
	}

	private static LegacyWallet outsider() {
		return new LegacyWallet(NEXT.incrementAndGet(), true);
	}

	private static Location entry(final LegacyWallet wallet, final long height) {
		return entry(wallet.base58(), height);
	}

	private static Location entry(final AccountKey account, final long seconds) {
		return entry(account.toString(), seconds);
	}

	private static Location entry(final String address, final long number) {
		return new Location(new org.unigrid.hedgehog.model.Address(address), (int) number);
	}

	private static MintStorage.SporkData mints(final Map<Location, BigDecimal> entries) {
		final MintStorage.SporkData data = new MintStorage.SporkData();

		data.setMints(new HashMap<>(entries));
		return data;
	}

	private static MintSupply.SporkData ceilingOf(final BigDecimal coins) {
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(coins);
		return data;
	}

	@Override
	protected LedgerFleet.Settings settings() {
		return LedgerFleet.Settings.builder().balances(IntStream.range(0, WALLETS)
			.mapToObj(n -> (40 + n) * COIN + 12_345L * (n + 1)).toList()).build();
	}

	/* Every spork that a node needs before it acts on a mint, a claim or a transfer is set, an unset one meaning no */
	private void ready() {
		awaitSigning();

		if (Objects.isNull(fleet().sporkOn(0, Type.MINT_SUPPLY))) {
			fleet().publishSpork(Type.MINT_SUPPLY, ceilingOf(CEILING));
		}

		if (Objects.isNull(fleet().sporkOn(0, Type.MINT_STORAGE))) {
			fleet().publishSpork(Type.MINT_STORAGE, mints(Map.of()));
		}

		if (Objects.isNull(fleet().sporkOn(0, Type.LEDGER_FEE))) {
			fleet().publishSpork(Type.LEDGER_FEE, new LedgerFee.SporkData());
		}

		if (Objects.isNull(fleet().sporkOn(0, Type.LEDGER_BLACKLIST))) {
			fleet().publishSpork(Type.LEDGER_BLACKLIST, new LedgerBlacklist.SporkData());
		}
	}

	/* A transaction taken while the validators are still catching up after a restart may not reach the proposer, and
	   then waits for good, so nothing is submitted before every validator signs */
	private void awaitSigning() {
		await().atMost(PATIENCE).until(() -> IntStream.range(0, fleet().validators()).filter(fleet()::isRunning)
			.allMatch(node -> fleet().status(node).signing()));
	}

	private void restartEveryNode() {
		IntStream.range(0, fleet().size()).forEach(fleet()::stop);
		IntStream.range(0, fleet().size()).forEach(fleet()::restart);
		fleet().awaitSameChain();
		awaitSigning();
	}

	private long secondsAgo(final long seconds) {
		return fleet().clock().now() / 1000 - seconds;
	}

	private Claim claimOf(final int wallet, final AccountKey owner) {
		return fleet().wallet(wallet).claim(owner, fleet().chainId());
	}

	/* The wallet claims onto the account at a validator, and the account has the snapshot balance on every node */
	private void claim(final int wallet, final AccountKey owner) {
		assertThat(fleet().submit(1, claimOf(wallet, owner)), equalTo(Optional.empty()));
		awaitBalance(owner, fleet().holding(wallet));
		fleet().awaitSameChain();
	}

	private boolean everywhere(final IntPredicate test) {
		return IntStream.range(0, fleet().size()).filter(fleet()::isRunning).allMatch(test);
	}

	private List<Long> balances(final Address who) {
		return IntStream.range(0, fleet().size()).filter(fleet()::isRunning)
			.mapToObj(node -> fleet().balanceOf(node, who)).toList();
	}

	private List<Long> supplies() {
		return IntStream.range(0, fleet().size()).filter(fleet()::isRunning)
			.mapToObj(node -> fleet().supplyOn(node)).toList();
	}

	private void awaitBalance(final Address who, final long units) {
		await().atMost(PATIENCE).until(() -> everywhere(node -> fleet().balanceOf(node, who) == units));
	}

	private void staysTrue(final BooleanSupplier condition) {
		await().during(QUIET).atMost(QUIET.plusSeconds(5)).until(condition::getAsBoolean);
	}

	/* A refusal is the same on every node, whichever it is put to, and makes no block */
	private void assertRefusedEverywhere(final Transaction transaction, final Optional<String> reason) {
		IntStream.range(0, fleet().size()).filter(fleet()::isRunning).forEach(node ->
			assertThat("node " + node, fleet().submit(node, transaction), equalTo(reason)));
	}

	private void staysAsItIs() {
		fleet().awaitSameChain();

		final long height = fleet().height(0);
		final long supply = fleet().supplyOn(0);

		staysTrue(() -> everywhere(node -> fleet().height(node) == height && fleet().supplyOn(node) == supply));
	}

	private Mint snapshotBalanceAgain(final int wallet) {
		final LegacyWallet holder = fleet().wallet(wallet);

		return new Mint(holder.address(), fleet().holding(wallet),
			Reference.ofClaim(fleet().genesis().getSnapshotHash(), holder.address()));
	}

	@Example
	public void shouldGiveTheAccountTheSnapshotBalanceOnEveryNodeAndMintNothing() {
		final LegacyWallet wallet = fleet().wallet(0);
		final AccountKey owner = account();

		ready();

		final long supply = fleet().supplyOn(0);

		assertThat(balances(owner), everyItem(is(0L)));
		claim(0, owner);

		assertThat(balances(owner), everyItem(is(fleet().holding(0))));
		assertThat(balances(wallet.address()), everyItem(is(0L)));
		assertThat(supplies(), everyItem(is(supply)));
	}

	@Example
	public void shouldRefuseEveryRepeatOfAClaimOnEveryNode() {
		final LegacyWallet wallet = fleet().wallet(1);
		final AccountKey owner = account();

		ready();
		claim(1, owner);

		assertRefusedEverywhere(claimOf(1, owner), CLAIMED);
		assertRefusedEverywhere(claimOf(1, account()), CLAIMED);
		assertRefusedEverywhere(snapshotBalanceAgain(1), USED);
		staysAsItIs();

		assertThat(balances(owner), everyItem(is(fleet().holding(1))));
		assertThat(balances(wallet.address()), everyItem(is(0L)));
	}

	@Example
	public void shouldNotMintToAClaimedAddressWhatTheSnapshotAlreadyGaveIt() {
		final LegacyWallet wallet = fleet().wallet(2);
		final AccountKey owner = account();
		final AccountKey control = account();
		final Map<Location, BigDecimal> entries = new HashMap<>();

		ready();
		claim(2, owner);

		final long supply = fleet().supplyOn(0);

		entries.put(entry(wallet, fleet().tipHeight()), BigDecimal.TEN);
		entries.put(entry(wallet, fleet().tipHeight() - 7), BigDecimal.TEN);
		entries.put(entry(control, secondsAgo(60)), BigDecimal.ONE);
		fleet().publishSpork(Type.MINT_STORAGE, mints(entries));

		awaitBalance(control, COIN);
		fleet().clock().advance(Duration.ofDays(1));
		staysTrue(() -> everywhere(node -> fleet().supplyOn(node) == supply + COIN));

		assertRefusedEverywhere(new Mint(wallet.address(), 10 * COIN,
			Reference.ofSpork(wallet.address(), fleet().tipHeight())), NO_MINT);
		assertThat(balances(owner), everyItem(is(fleet().holding(2))));
		assertThat(balances(wallet.address()), everyItem(is(0L)));
	}

	@Example
	public void shouldPayWhatComesToAClaimedAddressToItsOwnerAndKeepNothingOnTheAddress() {
		final LegacyWallet wallet = fleet().wallet(3);
		final AccountKey owner = account();
		final long grant = 5 * COIN / 2;

		ready();
		claim(3, owner);

		final long supply = fleet().supplyOn(0);

		fleet().publishSpork(Type.MINT_STORAGE, mints(Map.of(entry(wallet, fleet().tipHeight() + 1),
			BigDecimal.valueOf(grant, 8))));

		awaitBalance(owner, fleet().holding(3) + grant);
		assertThat(balances(wallet.address()), everyItem(is(0L)));
		assertThat(supplies(), everyItem(is(supply + grant)));
	}

	@Example
	public void shouldLeaveNothingOnAClaimedAddressToSpendByATransfer() {
		final LegacyWallet wallet = fleet().wallet(4);
		final byte[] ownerSeed = newSeed();
		final byte[] friendSeed = newSeed();
		final AccountKey owner = Ed25519.publicKey(ownerSeed);
		final AccountKey friend = Ed25519.publicKey(friendSeed);
		final AccountKey lookalike = new AccountKey(Arrays.copyOf(wallet.address().bytes(), AccountKey.SIZE));
		final long holding = fleet().holding(4);

		ready();
		claim(4, owner);

		final long supply = fleet().supplyOn(0);

		assertThat(fleet().submit(0, Transfer.signed(ownerSeed, friend, 5 * COIN, 0, 0, fleet().chainId())),
			equalTo(Optional.empty()));
		awaitBalance(friend, 5 * COIN);
		assertThat(fleet().submit(0, Transfer.signed(friendSeed, wallet.address(), 2 * COIN, 0, 0,
			fleet().chainId())), equalTo(Optional.empty()));
		awaitBalance(friend, 3 * COIN);

		awaitBalance(owner, holding - 3 * COIN);
		assertThat(balances(wallet.address()), everyItem(is(0L)));
		assertRefusedEverywhere(new Transfer(lookalike, friend, 1, 0, 0, new byte[Ed25519.SIGNATURE_SIZE]),
			NOT_COVERED);
		assertThat(supplies(), everyItem(is(supply)));
	}

	@Example
	public void shouldRefuseAClaimThatIsNotTheHoldersOnEveryNodeAndAcceptTheHoldersAfterwards() {
		final LegacyWallet wallet = fleet().wallet(5);
		final LegacyWallet neighbour = fleet().wallet(6);
		final AccountKey owner = account();
		final Claim genuine = claimOf(5, owner);
		final byte[] otherChain = fleet().chainId();
		final byte[] flipped = genuine.signature();

		ready();
		otherChain[0] ^= 1;
		flipped[flipped.length - 1] ^= 1;

		final long supply = fleet().supplyOn(0);

		assertRefusedEverywhere(new Claim(wallet.publicKey(), owner, neighbour.claim(owner, fleet().chainId())
			.signature()), BAD_SIGNATURE);
		assertRefusedEverywhere(new Claim(wallet.publicKey(), account(), genuine.signature()), BAD_SIGNATURE);
		assertRefusedEverywhere(new Claim(wallet.publicKey(), owner, flipped), BAD_SIGNATURE);
		assertRefusedEverywhere(wallet.claim(owner, otherChain), BAD_SIGNATURE);
		assertRefusedEverywhere(outsider().claim(owner, fleet().chainId()), NOTHING);
		staysAsItIs();
		assertThat(balances(owner), everyItem(is(0L)));

		claim(5, owner);

		assertThat(balances(owner), everyItem(is(fleet().holding(5))));
		assertThat(balances(neighbour.address()), everyItem(is(0L)));
		assertThat(supplies(), everyItem(is(supply)));
	}

	@Example
	public void shouldHoldBackAClaimOnANodeThatHasNoBlacklistUntilItHasOne() {
		final AccountKey owner = account();
		final Claim claim = claimOf(7, owner);

		ready();

		final GridSpork blacklist = fleet().sporkOn(0, Type.LEDGER_BLACKLIST);

		IntStream.range(0, fleet().size()).forEach(node ->
			bean(containerName(node), SporkDatabase.class).setLedgerBlacklist(null));
		IntStream.range(0, fleet().size()).forEach(node ->
			assertThat(fleet().status(node).unsetSporks(), hasItem(Type.LEDGER_BLACKLIST)));
		assertRefusedEverywhere(claim, HELD_BACK);
		staysAsItIs();

		IntStream.range(0, fleet().size()).forEach(node -> fleet().deliverSpork(node, blacklist));
		await().atMost(PATIENCE).until(() -> everywhere(node -> Objects.nonNull(fleet().sporkOn(node,
			Type.LEDGER_BLACKLIST))));
		assertThat(fleet().submit(1, claim), equalTo(Optional.empty()));
		awaitBalance(owner, fleet().holding(7));
	}

	/* The second claim is taken only if the first has not yet reached its node, and the proposer decides which of the
	   two lands, so the answers may differ and what is asserted is the end state */
	@Example
	public void shouldLetOnlyOneOfTwoClaimsOfOneAddressWinOnEveryNode() {
		final AccountKey first = account();
		final AccountKey second = account();
		final long holding = fleet().holding(8);

		ready();

		final long supply = fleet().supplyOn(0);

		final CompletableFuture<Optional<String>> early = CompletableFuture.supplyAsync(() ->
			fleet().submit(0, claimOf(8, first)));
		final CompletableFuture<Optional<String>> late = CompletableFuture.supplyAsync(() ->
			fleet().submit(3, claimOf(8, second)));

		final List<Optional<String>> answers = List.of(early.join(), late.join());

		assertThat(answers, hasItem(Optional.empty()));
		assertThat(answers, everyItem(anyOf(equalTo(Optional.empty()), equalTo(WAITING))));
		await().atMost(PATIENCE).until(() -> everywhere(node -> fleet().balanceOf(node, first) == holding
			|| fleet().balanceOf(node, second) == holding));
		fleet().awaitSameChain();

		final boolean firstWon = fleet().balanceOf(0, first) == holding;

		assertThat(balances(first), everyItem(is(firstWon ? holding : 0L)));
		assertThat(balances(second), everyItem(is(firstWon ? 0L : holding)));
		assertThat(balances(fleet().wallet(8).address()), everyItem(is(0L)));
		assertThat(supplies(), everyItem(is(supply)));
		staysAsItIs();
		assertThat(balances(firstWon ? second : first), everyItem(is(0L)));
	}

	@Example
	public void shouldRefuseTheRepeatsOnANodeThatJoinsAfterTheClaimAndSyncsIt() {
		final LegacyWallet wallet = fleet().wallet(9);
		final AccountKey owner = account();

		ready();
		claim(9, owner);

		final long supply = fleet().supplyOn(0);
		final int joined = fleet().addObserver();

		fleet().awaitHeight(joined, fleet().height(0));
		fleet().awaitSameChain();

		assertThat(fleet().balanceOf(joined, owner), is(fleet().holding(9)));
		assertThat(fleet().balanceOf(joined, wallet.address()), is(0L));
		assertThat(fleet().supplyOn(joined), is(supply));
		assertThat(fleet().submit(joined, claimOf(9, owner)), equalTo(CLAIMED));
		assertThat(fleet().submit(joined, claimOf(9, account())), equalTo(CLAIMED));
		assertThat(fleet().submit(joined, snapshotBalanceAgain(9)), equalTo(USED));
		assertThat(fleet().submit(joined, new Mint(wallet.address(), COIN,
			Reference.ofSpork(wallet.address(), fleet().tipHeight()))), equalTo(NO_MINT));
		staysAsItIs();
	}

	@Example
	public void shouldMoveFundsByAClaimMadeAfterEveryNodeHasRestarted() {
		final LegacyWallet wallet = fleet().wallet(11);
		final AccountKey owner = account();

		ready();

		final long supply = fleet().supplyOn(0);

		restartEveryNode();
		claim(11, owner);

		assertThat(balances(owner), everyItem(is(fleet().holding(11))));
		assertThat(balances(wallet.address()), everyItem(is(0L)));
		assertThat(supplies(), everyItem(is(supply)));
		assertRefusedEverywhere(claimOf(11, owner), CLAIMED);
	}

	@Example
	public void shouldStillRefuseTheRepeatsWhenEveryNodeHasRestartedOverItsFiles() {
		final LegacyWallet wallet = fleet().wallet(10);
		final AccountKey owner = account();

		ready();
		claim(10, owner);

		final long supply = fleet().supplyOn(0);

		restartEveryNode();

		assertThat(balances(owner), everyItem(is(fleet().holding(10))));
		assertThat(balances(wallet.address()), everyItem(is(0L)));
		assertThat(supplies(), everyItem(is(supply)));
		assertRefusedEverywhere(claimOf(10, owner), CLAIMED);
		assertRefusedEverywhere(claimOf(10, account()), CLAIMED);
		assertRefusedEverywhere(snapshotBalanceAgain(10), USED);
		staysAsItIs();
	}
}
