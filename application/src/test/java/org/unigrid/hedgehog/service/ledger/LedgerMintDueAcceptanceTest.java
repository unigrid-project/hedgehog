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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.service.ledger.LedgerFleet.COIN;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Address;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.Mint;
import org.unigrid.hedgehog.ledger.Reference;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.MintSupply;

/* When the entries of the mint spork mint and how much, on a running network: validators and observers that dial each
   other over QUIC, each with its own spork database, its own ledger service and the one clock of the fleet. A spork
   reaches a node through the method its packet handler calls, and a mint is made due by moving the clock, never by
   waiting for it. An entry of a ledger account says a time in seconds, an entry of the old chain a height, and the
   maximum supply and what the spork grants an address bound what is paid. The fleet is shared by the examples, so each
   one works with accounts of its own, sets the sporks it needs and leaves nothing waiting for the next. */
public class LedgerMintDueAcceptanceTest extends BaseLedgerFleetTest {
	private static final BigDecimal CEILING = BigDecimal.valueOf(1_000_000_000L);
	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	private static final BigDecimal FIFTY = BigDecimal.valueOf(50);
	private static final int WALLETS = 10;

	/* The wallet of the snapshot that the example of the claimed address claims, and no other example does */
	private static final int CLAIMED_HERE = 8;
	private static final Duration PATIENCE = Duration.ofSeconds(60);
	private static final Duration QUIET = Duration.ofMillis(2_500);

	/* Shorter than the minute between two heights of the old chain, so that a mint a height late is seen to be late */
	private static final Duration NOT_A_MINUTE = Duration.ofSeconds(40);

	private static final long MINUTE = 60_000;
	private static final long ONE_AND_A_HALF = 150_000_000L;
	private static final Optional<String> REFUSED = Optional.of("Mint is not authorized");
	private static final AtomicInteger NEXT = new AtomicInteger(10_000);

	private static AccountKey account() {
		return Ed25519.publicKey(LedgerTestChain.seed(NEXT.incrementAndGet()));
	}

	private static LegacyWallet wallet() {
		return new LegacyWallet(NEXT.incrementAndGet(), true);
	}

	private static Location entry(final String address, final long number) {
		return new Location(new org.unigrid.hedgehog.model.Address(address), (int) number);
	}

	private static Location entry(final AccountKey account, final long seconds) {
		return entry(account.toString(), seconds);
	}

	private static Location entry(final LegacyWallet wallet, final long height) {
		return entry(wallet.base58(), height);
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

	private static BigDecimal coins(final long units) {
		return BigDecimal.valueOf(units, 8);
	}

	private static Mint mintOf(final Address recipient, final long units, final long number) {
		return new Mint(recipient, units, Reference.ofSpork(recipient, number));
	}

	@Override
	protected LedgerFleet.Settings settings() {
		return LedgerFleet.Settings.builder().balances(Collections.nCopies(WALLETS, 50 * COIN)).build();
	}

	private long secondsAgo(final long seconds) {
		return fleet().clock().now() / 1000 - seconds;
	}

	/* Every spork that a node needs before it acts on a mint, a claim or a transfer is set, an unset one meaning no */
	private void ready() {
		if (!isCappedAt(CEILING)) {
			fleet().publishSpork(Type.MINT_SUPPLY, ceilingOf(CEILING));
		}

		if (Objects.isNull(fleet().sporkOn(0, Type.LEDGER_FEE))) {
			fleet().publishSpork(Type.LEDGER_FEE, new LedgerFee.SporkData());
		}

		if (Objects.isNull(fleet().sporkOn(0, Type.LEDGER_BLACKLIST))) {
			fleet().publishSpork(Type.LEDGER_BLACKLIST, new LedgerBlacklist.SporkData());
		}
	}

	private boolean isCappedAt(final BigDecimal maximum) {
		return Optional.ofNullable(fleet().sporkOn(0, Type.MINT_SUPPLY))
			.map(spork -> spork.<MintSupply.SporkData>getData().getMaxSupply())
			.filter(held -> held.compareTo(maximum) == 0).isPresent();
	}

	private GridSpork publishMints(final Map<Location, BigDecimal> entries) {
		return fleet().publishSpork(Type.MINT_STORAGE, mints(entries));
	}

	private boolean everywhere(final IntPredicate test) {
		return IntStream.range(0, fleet().size()).filter(fleet()::isRunning).allMatch(test);
	}

	private List<Long> balances(final Address who) {
		return IntStream.range(0, fleet().size()).filter(fleet()::isRunning)
			.mapToObj(node -> fleet().balanceOf(node, who)).toList();
	}

	private void awaitBalance(final Address who, final long units) {
		await().atMost(PATIENCE).until(() -> everywhere(node -> fleet().balanceOf(node, who) == units));
	}

	private void awaitSupply(final long units) {
		await().atMost(PATIENCE).until(() -> everywhere(node -> fleet().supplyOn(node) == units));
	}

	private void staysTrue(final BooleanSupplier condition) {
		await().during(QUIET).atMost(QUIET.plusSeconds(5)).until(condition::getAsBoolean);
	}

	private void staysEmpty(final Address who) {
		staysTrue(() -> everywhere(node -> fleet().balanceOf(node, who) == 0));
	}

	private void advanceTo(final long millis) {
		fleet().clock().advance(Duration.ofMillis(millis - fleet().clock().now()));
	}

	@Example
	public void shouldMintTheEntryOfANewAddressOnEveryNodeOnceItIsDue() {
		final LedgerFleet fleet = fleet();
		final AccountKey later = account();
		final AccountKey earlier = account();
		final long now = secondsAgo(0);

		ready();

		final long supply = fleet.supplyOn(0);

		publishMints(Map.of(entry(later, now + 300), BigDecimal.TEN, entry(earlier, now - 3_600),
			new BigDecimal("1.5")));

		awaitBalance(earlier, ONE_AND_A_HALF);
		staysEmpty(later);
		fleet.clock().advance(Duration.ofSeconds(250));
		staysEmpty(later);
		fleet.clock().advance(Duration.ofSeconds(100));
		awaitBalance(later, 10 * COIN);

		assertThat(balances(earlier), everyItem(is(ONE_AND_A_HALF)));
		awaitSupply(supply + 10 * COIN + ONE_AND_A_HALF);
	}

	@Example
	public void shouldMintTheEntryOfAnOldAddressAboveTheTipAtTheTipTimePlusAMinutePerHeight() {
		final LedgerFleet fleet = fleet();
		final LegacyWallet wallet = wallet();
		final LegacyWallet immediate = wallet();
		final AccountKey owner = account();

		ready();

		final long supply = fleet.supplyOn(0);
		final long ahead = fleet.clock().now() + 5 * MINUTE;
		final int height = fleet.tipHeight() + (int) Math.ceilDiv(ahead - fleet.tipTimeMillis(), MINUTE);
		final long due = fleet.tipTimeMillis() + (height - fleet.tipHeight()) * MINUTE;

		publishMints(Map.of(entry(wallet, height), new BigDecimal("7"), entry(immediate, fleet.tipHeight() + 1),
			new BigDecimal("1.5")));

		awaitBalance(immediate.address(), ONE_AND_A_HALF);
		advanceTo(due - MINUTE / 2);
		staysEmpty(wallet.address());
		advanceTo(due + MINUTE / 4);
		await().atMost(NOT_A_MINUTE).until(() ->
			everywhere(node -> fleet.balanceOf(node, wallet.address()) == 7 * COIN));

		assertThat(fleet.submit(1, wallet.claim(owner, fleet.chainId())), equalTo(Optional.empty()));
		awaitBalance(owner, 7 * COIN);
		assertThat(balances(wallet.address()), everyItem(is(0L)));
		awaitSupply(supply + 7 * COIN + ONE_AND_A_HALF);
	}

	@Example
	public void shouldNotMintTheEntryOfTheOldChainAtOrBelowTheTip() {
		final LedgerFleet fleet = fleet();
		final AccountKey control = account();
		final AccountKey owner = account();
		final LegacyWallet fresh = wallet();
		final LegacyWallet atTip = fleet.wallet(6);
		final LegacyWallet belowTip = fleet.wallet(7);
		final Map<Location, BigDecimal> entries = new HashMap<>();

		ready();

		final long supply = fleet.supplyOn(0);

		entries.put(entry(control, secondsAgo(60)), BigDecimal.ONE);
		entries.put(entry(atTip, fleet.tipHeight()), BigDecimal.TEN);
		entries.put(entry(belowTip, fleet.tipHeight() - 10), BigDecimal.TEN);
		entries.put(entry(fresh, fleet.tipHeight()), BigDecimal.TEN);
		entries.put(entry(wallet(), 0), BigDecimal.TEN);
		publishMints(entries);

		awaitBalance(control, COIN);
		fleet.clock().advance(Duration.ofDays(1));
		staysTrue(() -> everywhere(node -> fleet.supplyOn(node) == supply + COIN));

		assertThat(balances(fresh.address()), everyItem(is(0L)));
		assertThat(balances(atTip.address()), everyItem(is(0L)));
		assertThat(fleet.submit(0, belowTip.claim(owner, fleet.chainId())), equalTo(Optional.empty()));
		awaitBalance(owner, fleet.holding(7));
		awaitSupply(supply + COIN);
	}

	@Example
	public void shouldStopAtTheMaximumSupplyInTheMiddleOfABlock() {
		final LedgerFleet fleet = fleet();
		final AccountKey first = account();
		final AccountKey second = account();

		ready();

		final long supply = fleet.supplyOn(0);
		final long seconds = secondsAgo(60);

		try {
			fleet.publishSpork(Type.MINT_SUPPLY, ceilingOf(coins(supply + 15 * COIN)));
			publishMints(Map.of(entry(first, seconds), BigDecimal.TEN, entry(second, seconds), BigDecimal.TEN));

			awaitSupply(supply + 10 * COIN);
			staysTrue(() -> everywhere(node -> fleet.supplyOn(node) == supply + 10 * COIN));
			assertThat(fleet.balanceOf(0, first) + fleet.balanceOf(0, second), is(10 * COIN));

			fleet.publishSpork(Type.MINT_SUPPLY, ceilingOf(coins(supply + 20 * COIN)));
			awaitSupply(supply + 20 * COIN);
			assertThat(balances(first), everyItem(is(10 * COIN)));
			assertThat(balances(second), everyItem(is(10 * COIN)));
		} finally {
			fleet.publishSpork(Type.MINT_SUPPLY, ceilingOf(CEILING));
		}
	}

	@Example
	public void shouldMintUpToTheUnitAtTheMaximumSupplyAndNotOneUnitMore() {
		final LedgerFleet fleet = fleet();
		final AccountKey whole = account();
		final AccountKey unit = account();

		ready();

		final long supply = fleet.supplyOn(0);
		final long seconds = secondsAgo(60);
		final Map<Location, BigDecimal> entries = new HashMap<>(Map.of(entry(whole, seconds), BigDecimal.TEN));

		try {
			fleet.publishSpork(Type.MINT_SUPPLY, ceilingOf(coins(supply + 10 * COIN - 1)));
			publishMints(entries);
			staysEmpty(whole);

			fleet.publishSpork(Type.MINT_SUPPLY, ceilingOf(coins(supply + 10 * COIN)));
			awaitBalance(whole, 10 * COIN);
			awaitSupply(supply + 10 * COIN);

			entries.put(entry(unit, seconds), coins(1));
			publishMints(entries);
			staysEmpty(unit);

			fleet.publishSpork(Type.MINT_SUPPLY, ceilingOf(coins(supply + 10 * COIN + 1)));
			awaitBalance(unit, 1);
			awaitSupply(supply + 10 * COIN + 1);
		} finally {
			fleet.publishSpork(Type.MINT_SUPPLY, ceilingOf(CEILING));
		}
	}

	/* An entry that a later version moves to another time has another reference, and without the cap the address is
	   paid again for what the spork has granted it once. A ledger account and a claimed legacy address are granted
	   the same: what is minted to the claimed address goes to its owner and still counts against the address, so
	   the owner is not paid again by a moved entry, and gets the rest of what the spork grants the address. The
	   cap holds after every node has restarted and on a node that joins and syncs. */
	@Example
	public void shouldPayAnAddressWhatTheSporkGrantsItAndNotAgainWhenALaterSporkMovesTheEntry() {
		final LedgerFleet fleet = fleet();
		final AccountKey granted = account();
		final LegacyWallet claimed = fleet.wallet(CLAIMED_HERE);
		final AccountKey owner = account();
		final AccountKey control = account();
		final AccountKey afterwards = account();
		final long first = secondsAgo(180);
		final long second = secondsAgo(120);
		final long third = secondsAgo(60);
		final int firstHeight = fleet.tipHeight() + 1;
		final long holding = fleet.holding(CLAIMED_HERE);

		ready();

		final long supply = fleet.supplyOn(0);

		try {
			assertThat(fleet.submit(1, claimed.claim(owner, fleet.chainId())), equalTo(Optional.empty()));
			awaitBalance(owner, holding);

			publishMints(Map.of(entry(granted, first), HUNDRED, entry(claimed, firstHeight), HUNDRED));
			awaitBalance(granted, 100 * COIN);
			awaitBalance(owner, holding + 100 * COIN);

			publishMints(Map.of(entry(granted, second), HUNDRED, entry(claimed, firstHeight + 1), HUNDRED,
				entry(control, second), BigDecimal.ONE));
			awaitBalance(control, COIN);
			refusedEverywhere(mintOf(granted, 100 * COIN, second), mintOf(claimed.address(), 100 * COIN,
				firstHeight + 1));
			stillAt(supply + 201 * COIN, Map.of(granted, 100 * COIN, owner, holding + 100 * COIN));

			publishMints(Map.of(entry(granted, first), HUNDRED, entry(granted, second), FIFTY,
				entry(claimed, firstHeight), HUNDRED, entry(claimed, firstHeight + 1), FIFTY,
				entry(control, second), BigDecimal.ONE));
			awaitBalance(granted, 150 * COIN);
			awaitBalance(owner, holding + 150 * COIN);
			awaitSupply(supply + 301 * COIN);
			assertThat(balances(claimed.address()), everyItem(is(0L)));

			restartEveryNode();
			publishMints(Map.of(entry(granted, third), FIFTY, entry(claimed, firstHeight + 2), FIFTY,
				entry(afterwards, third), BigDecimal.ONE));
			awaitBalance(afterwards, COIN);
			refusedEverywhere(mintOf(granted, 50 * COIN, third), mintOf(claimed.address(), 50 * COIN,
				firstHeight + 2));
			stillAt(supply + 302 * COIN, Map.of(granted, 150 * COIN, owner, holding + 150 * COIN));

			final int observer = fleet.addObserver();

			fleet.awaitHeight(observer, fleet.height(0));
			fleet.awaitSameChain();
			fleet.deliverSpork(observer, fleet.sporkOn(0, Type.MINT_SUPPLY));
			fleet.deliverSpork(observer, fleet.sporkOn(0, Type.MINT_STORAGE));

			assertThat(fleet.balanceOf(observer, granted), is(150 * COIN));
			assertThat(fleet.balanceOf(observer, owner), is(holding + 150 * COIN));
			refusedEverywhere(mintOf(granted, 50 * COIN, third), mintOf(claimed.address(), 50 * COIN,
				firstHeight + 2));
			stillAt(supply + 302 * COIN, Map.of(granted, 150 * COIN, owner, holding + 150 * COIN));
		} finally {
			publishMints(Map.of());
		}
	}

	private void refusedEverywhere(final Mint... mints) {
		Arrays.stream(mints).forEach(mint -> IntStream.range(0, fleet().size()).filter(fleet()::isRunning)
			.forEach(node -> assertThat(fleet().submit(node, mint), equalTo(REFUSED))));
	}

	private void stillAt(final long supply, final Map<Address, Long> held) {
		final long height = fleet().height(0);

		staysTrue(() -> everywhere(node -> fleet().height(node) == height && fleet().supplyOn(node) == supply
			&& held.entrySet().stream().allMatch(who -> fleet().balanceOf(node, who.getKey()) == who.getValue())));
	}

	private void restartEveryNode() {
		IntStream.range(0, fleet().size()).forEach(fleet()::stop);
		IntStream.range(0, fleet().size()).forEach(fleet()::restart);
		fleet().awaitSameChain();
	}
}
