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
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import org.apache.commons.lang3.SerializationUtils;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.service.ledger.FleetContainers.bean;
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
import org.unigrid.hedgehog.model.spork.SporkDatabase;

/* An entry of the mint spork mints once, and what is broken or hostile in the spork neither stops a node nor makes it
   mint what it must not, on a running network: validators and observers that dial each other over QUIC, each with its
   own spork database, its own ledger service and the one clock of the fleet. An entry mints once whatever happens to
   the node or the spork afterwards, a re-publish, a restart or an observer that joins. Entries that cannot be minted
   are skipped, thousands that are due are made in blocks while the nodes answer, a mint waits for enough validators
   to hold the spork and for a newer one that is known to come, and a spork that the network keys did not sign
   changes nothing. The fleet is shared by the examples, so each one works with accounts of its own, sets the sporks
   it needs and leaves nothing waiting for the next. */
public class LedgerMintOnceAcceptanceTest extends BaseLedgerFleetTest {
	private static final BigDecimal CEILING = BigDecimal.valueOf(1_000_000_000L);
	private static final int WALLETS = 10;
	private static final Duration PATIENCE = Duration.ofSeconds(60);
	private static final Duration QUIET = Duration.ofMillis(2_500);
	private static final Duration RESPONSIVE = Duration.ofSeconds(10);
	private static final Duration ANSWER = Duration.ofSeconds(2);

	/* A block of a thousand mints is some 70 kilobytes in each message between the nodes: the six blocks of the
	   entries that are due are decided by five nodes of one process in some ten seconds, and this leaves room for a
	   host that is busy */
	private static final Duration ALL_THE_BLOCKS = Duration.ofSeconds(120);
	private static final int DUE_ENTRIES = 5_100;
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

	/* A node that has lost its mint spork, as one does whose spork database was removed */
	private void forget(final int node) {
		bean(containerName(node), SporkDatabase.class).setMintStorage(null);
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
		staysTrue(condition, QUIET.plusSeconds(5));
	}

	/* A condition that delivers a spork to every node takes seconds each time, more with each node, so it is given time
	   to be looked at enough times to hold for the quiet period */
	private void staysTrue(final BooleanSupplier condition, final Duration budget) {
		await().during(QUIET).atMost(budget).until(condition::getAsBoolean);
	}

	private void staysEmpty(final Address who) {
		staysTrue(() -> everywhere(node -> fleet().balanceOf(node, who) == 0));
	}

	@Example
	public void shouldNeverMintAnEntryTwiceNotAfterARestartNorForAJoiningNode() {
		final LedgerFleet fleet = fleet();
		final AccountKey granted = account();
		final AccountKey other = account();
		final long seconds = secondsAgo(60);
		final Map<Location, BigDecimal> once = Map.of(entry(granted, seconds), BigDecimal.TEN);

		ready();

		final long supply = fleet.supplyOn(0);

		publishMints(once);
		awaitBalance(granted, 10 * COIN);

		final long height = fleet.height(0);

		publishMints(once);
		staysTrue(() -> everywhere(node -> fleet.height(node) == height));

		publishMints(Map.of(entry(granted, seconds), BigDecimal.TEN, entry(other, seconds), BigDecimal.ONE));
		awaitBalance(other, COIN);
		assertThat(balances(granted), everyItem(is(10 * COIN)));
		awaitSupply(supply + 11 * COIN);

		fleet.stop(2);
		fleet.restart(2);
		fleet.awaitSameChain();
		stillMinted(granted, other, supply + 11 * COIN);

		IntStream.range(0, fleet.size()).forEach(fleet::stop);
		IntStream.range(0, fleet.size()).forEach(fleet::restart);
		fleet.awaitSameChain();
		stillMinted(granted, other, supply + 11 * COIN);

		final int observer = fleet.addObserver();

		fleet.awaitHeight(observer, fleet.height(0));
		fleet.awaitSameChain();
		stillMinted(granted, other, supply + 11 * COIN);
	}

	private void stillMinted(final AccountKey granted, final AccountKey other, final long supply) {
		final long height = fleet().height(0);

		staysTrue(() -> everywhere(node -> fleet().height(node) == height && fleet().supplyOn(node) == supply
			&& fleet().balanceOf(node, granted) == 10 * COIN && fleet().balanceOf(node, other) == COIN));
	}

	@Example
	public void shouldMintTwoEntriesOfOneAddressAsTwoGrantsAndSkipWhatCanNeverBeMinted() {
		final LedgerFleet fleet = fleet();
		final AccountKey control = account();
		final AccountKey twice = account();
		final List<AccountKey> never = IntStream.range(0, 5).mapToObj(n -> account()).toList();
		final String upperCase = HexFormat.of().withUpperCase().formatHex(never.get(0).bytes());
		final Map<Location, BigDecimal> entries = new HashMap<>();
		final long seconds = secondsAgo(60);

		ready();

		final long supply = fleet.supplyOn(0);

		assertThat(upperCase, not(equalTo(never.get(0).toString())));
		entries.put(entry(control, seconds), BigDecimal.ONE);
		entries.put(entry(twice, seconds), new BigDecimal("3"));
		entries.put(entry(twice, seconds - 1), new BigDecimal("4"));
		entries.put(entry(never.get(0), seconds), new BigDecimal("-0.00000001"));
		entries.put(entry(never.get(1), seconds), BigDecimal.ZERO);
		entries.put(entry(never.get(2), seconds), BigDecimal.valueOf(-1));
		entries.put(entry(never.get(3), seconds), new BigDecimal("92233720368.54775808"));
		entries.put(entry(never.get(4), seconds), new BigDecimal("999999999999"));
		entries.put(entry(upperCase, seconds), BigDecimal.TEN);
		entries.put(entry("not an address", seconds), BigDecimal.TEN);
		entries.put(entry("", seconds), BigDecimal.TEN);
		entries.put(entry(wallet(), 1), BigDecimal.TEN);
		entries.put(entry(wallet(), fleet.tipHeight()), BigDecimal.TEN);
		publishMints(entries);

		awaitBalance(control, COIN);
		awaitBalance(twice, 7 * COIN);
		fleet.clock().advance(Duration.ofDays(30));
		staysTrue(() -> everywhere(node -> fleet.supplyOn(node) == supply + 8 * COIN));

		never.forEach(key -> assertThat(balances(key), everyItem(is(0L))));
		IntStream.range(0, fleet.size()).forEach(node -> assertThat(fleet.status(node).running(), is(true)));
		IntStream.range(0, fleet.size()).forEach(node -> assertThat(fleet.status(node).halted(), is(false)));
	}

	@Example
	public void shouldStayResponsiveAndMintEveryEntryWhileFiveThousandAreDue() {
		final LedgerFleet fleet = fleet();
		final Map<Location, BigDecimal> entries = new HashMap<>();
		final long seconds = secondsAgo(60);
		final long unit = COIN / 100;

		ready();

		final long supply = fleet.supplyOn(0);

		IntStream.range(0, DUE_ENTRIES).forEach(n -> entries.put(entry(account(), seconds), new BigDecimal("0.01")));

		try {
			publishMints(entries);

			await().during(RESPONSIVE).atMost(RESPONSIVE.multipliedBy(3)).until(() ->
				everywhere(node -> answersWithin(node, ANSWER)));
			await().atMost(ALL_THE_BLOCKS).until(() -> everywhere(node ->
				fleet.supplyOn(node) >= supply + DUE_ENTRIES * unit));
			IntStream.range(0, fleet.size()).forEach(node -> assertThat(fleet.status(node).running(), is(true)));

			awaitOneChain();
			assertThat(IntStream.range(0, fleet.size()).mapToObj(fleet::supplyOn).toList(),
				everyItem(is(supply + DUE_ENTRIES * unit)));
		} finally {
			publishMints(Map.of());
		}
	}

	/* A node that is busy with its mints holds on to its ledger and cannot be asked how far it has come */
	private boolean answersWithin(final int node, final Duration limit) {
		final long started = System.nanoTime();

		fleet().height(node);
		return System.nanoTime() - started < limit.toNanos();
	}

	private void awaitOneChain() {
		await().atMost(PATIENCE).until(() -> IntStream.range(0, fleet().size()).filter(fleet()::isRunning)
			.mapToObj(node -> fleet().status(node).height() + ":" + fleet().status(node).tipHash()).distinct()
			.count() == 1);
	}

	@Example
	public void shouldMintNothingUntilEnoughValidatorsHoldTheSporkAndTurnDownWhatTheEntryDoesNotSay() {
		final LedgerFleet fleet = fleet();
		final AccountKey recipient = account();
		final long seconds = secondsAgo(60);

		ready();

		final long supply = fleet.supplyOn(0);
		final Mint exact = mintOf(recipient, 10 * COIN, seconds);
		final GridSpork newest = fleet.signed(Type.MINT_STORAGE,
			mints(Map.of(entry(recipient, seconds), BigDecimal.TEN)));

		forget(2);
		forget(3);
		fleet.deliverSpork(0, newest);
		fleet.deliverSpork(1, newest);

		assertThat(fleet.status(2).unsetSporks(), hasItem(Type.MINT_STORAGE));
		assertThat(fleet.submit(0, exact), equalTo(Optional.empty()));
		assertThat(fleet.submit(0, mintOf(recipient, 10 * COIN, seconds - 1)), equalTo(REFUSED));
		assertThat(fleet.submit(0, new Mint(recipient, 10 * COIN + 1, exact.reference())), equalTo(REFUSED));
		assertThat(fleet.submit(0, new Mint(account(), 10 * COIN, exact.reference())), equalTo(REFUSED));
		assertThat(fleet.submit(2, exact), equalTo(REFUSED));
		assertThat(fleet.submit(3, exact), equalTo(REFUSED));
		staysEmpty(recipient);

		fleet.deliverSpork(2, newest);
		awaitBalance(recipient, 10 * COIN);
		awaitSupply(supply + 10 * COIN);

		assertThat(fleet.sporkOn(3, Type.MINT_STORAGE), is(nullValue()));
		IntStream.range(3, fleet.size()).forEach(node -> fleet.deliverSpork(node, newest));
		staysTrue(() -> everywhere(node -> fleet.balanceOf(node, recipient) == 10 * COIN
			&& fleet.supplyOn(node) == supply + 10 * COIN));
	}

	@Example
	public void shouldMintNothingWhileASporkIsKnownToBeReplacedAndMintWhenItArrives() {
		final LedgerFleet fleet = fleet();
		final AccountKey recipient = account();
		final long seconds = secondsAgo(60);

		ready();

		final long supply = fleet.supplyOn(0);
		final GridSpork coming = fleet.proposal(Type.MINT_SUPPLY, ceilingOf(CEILING));

		await().atMost(PATIENCE).until(() -> {
			IntStream.range(0, fleet.size()).forEach(node -> fleet.deliverSpork(node, coming));
			return everywhere(node -> fleet.status(node).laggingSporks().contains(Type.MINT_SUPPLY));
		});

		publishMints(Map.of(entry(recipient, seconds), BigDecimal.TEN));
		assertThat(fleet.submit(0, mintOf(recipient, 10 * COIN, seconds)), equalTo(REFUSED));
		staysEmpty(recipient);
		assertThat(fleet.status(1).laggingSporks(), contains(Type.MINT_SUPPLY));
		awaitSupply(supply);

		fleet.publishSpork(Type.MINT_SUPPLY, ceilingOf(CEILING));
		awaitBalance(recipient, 10 * COIN);
		assertThat(fleet.status(1).laggingSporks(), empty());
		awaitSupply(supply + 10 * COIN);
	}

	@Example
	public void shouldNotLetASporkThatTheNetworkKeysDidNotSignStopAMint() {
		final LedgerFleet fleet = fleet();
		final AccountKey recipient = account();
		final long seconds = secondsAgo(60);

		ready();

		final long supply = fleet.supplyOn(0);
		final GridSpork genuine = fleet.signed(Type.MINT_STORAGE,
			mints(Map.of(entry(recipient, seconds), BigDecimal.TEN)));
		final GridSpork tampered = SerializationUtils.clone(genuine);
		final GridSpork unsigned = fleet.proposal(Type.MINT_SUPPLY, ceilingOf(BigDecimal.ONE));

		tampered.setData(mints(Map.of(entry(recipient, seconds), BigDecimal.ONE)));
		unsigned.setSignature(null);

		staysTrue(() -> {
			IntStream.range(0, fleet.size()).forEach(node -> {
				fleet.deliverSpork(node, tampered);
				fleet.deliverSpork(node, unsigned);
			});

			return everywhere(node -> fleet.status(node).laggingSporks().isEmpty());
		}, PATIENCE);

		fleet.publishSpork(genuine);
		awaitBalance(recipient, 10 * COIN);
		awaitSupply(supply + 10 * COIN);
	}
}
