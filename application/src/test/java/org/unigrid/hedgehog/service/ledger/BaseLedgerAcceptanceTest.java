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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Address;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.Mint;
import org.unigrid.hedgehog.ledger.Reference;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.MintSupply;

/* What the acceptance tests of the mint, the claim and the blacklist on a running network have in common: the waits
   for a balance or a quiet chain on the nodes that run, the accounts and entries each example makes for itself, the
   sporks that a node needs before it acts, and a claim put to a node. */
public abstract class BaseLedgerAcceptanceTest extends BaseLedgerFleetTest {
	protected static final BigDecimal CEILING = BigDecimal.valueOf(1_000_000_000L);
	protected static final Duration PATIENCE = Duration.ofSeconds(60);
	protected static final Duration QUIET = Duration.ofMillis(2_500);
	protected static final Optional<String> HELD_BACK = Optional.of("Claim is not authorized");
	protected static final Optional<String> REFUSED = Optional.of("Mint is not authorized");
	private static final AtomicInteger NEXT = new AtomicInteger(10_000);

	protected static byte[] newSeed() {
		return LedgerTestChain.seed(NEXT.incrementAndGet());
	}

	protected static AccountKey account() {
		return Ed25519.publicKey(newSeed());
	}

	/* A wallet of the old chain that the snapshot does not hold */
	protected static LegacyWallet outsider() {
		return new LegacyWallet(NEXT.incrementAndGet(), true);
	}

	protected static Location entry(final String address, final long number) {
		return new Location(new org.unigrid.hedgehog.model.Address(address), (int) number);
	}

	protected static Location entry(final AccountKey account, final long seconds) {
		return entry(account.toString(), seconds);
	}

	protected static Location entry(final LegacyWallet wallet, final long height) {
		return entry(wallet.base58(), height);
	}

	protected static MintStorage.SporkData mints(final Map<Location, BigDecimal> entries) {
		final MintStorage.SporkData data = new MintStorage.SporkData();

		data.setMints(new HashMap<>(entries));
		return data;
	}

	protected static MintSupply.SporkData ceilingOf(final BigDecimal coins) {
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(coins);
		return data;
	}

	protected static Mint mintOf(final Address recipient, final long units, final long number) {
		return new Mint(recipient, units, Reference.ofSpork(recipient, number));
	}

	protected long secondsAgo(final long seconds) {
		return fleet().clock().now() / 1000 - seconds;
	}

	/* Every spork that a node needs before it acts on a mint, a claim or a transfer is set, an unset one meaning no */
	protected void ready() {
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

	protected boolean isCappedAt(final BigDecimal maximum) {
		return Optional.ofNullable(fleet().sporkOn(0, Type.MINT_SUPPLY))
			.map(spork -> spork.<MintSupply.SporkData>getData().getMaxSupply())
			.filter(held -> held.compareTo(maximum) == 0).isPresent();
	}

	protected GridSpork publishMints(final Map<Location, BigDecimal> entries) {
		return fleet().publishSpork(Type.MINT_STORAGE, mints(entries));
	}

	protected void awaitSigning() {
		await().atMost(PATIENCE).until(() -> IntStream.range(0, fleet().validators()).filter(fleet()::isRunning)
			.allMatch(node -> fleet().status(node).signing()));
	}

	/* A client does not wait until the validators sign again, and a transaction taken before they do is made a block */
	protected void restartEveryNodeAtOnce() {
		IntStream.range(0, fleet().size()).forEach(fleet()::stop);
		IntStream.range(0, fleet().size()).forEach(fleet()::restart);
		fleet().awaitSameChain();
	}

	protected boolean everywhere(final IntPredicate test) {
		return IntStream.range(0, fleet().size()).filter(fleet()::isRunning).allMatch(test);
	}

	protected List<Long> balances(final Address who) {
		return IntStream.range(0, fleet().size()).filter(fleet()::isRunning)
			.mapToObj(node -> fleet().balanceOf(node, who)).toList();
	}

	protected void awaitBalance(final Address who, final long units) {
		await().atMost(PATIENCE).until(() -> everywhere(node -> fleet().balanceOf(node, who) == units));
	}

	protected void awaitSupply(final long units) {
		await().atMost(PATIENCE).until(() -> everywhere(node -> fleet().supplyOn(node) == units));
	}

	protected void staysTrue(final BooleanSupplier condition) {
		staysTrue(condition, QUIET.plusSeconds(5));
	}

	/* A condition that delivers a spork to every node takes seconds each time, more with each node, so it is given time
	   to be looked at enough times to hold for the quiet period */
	protected void staysTrue(final BooleanSupplier condition, final Duration budget) {
		await().during(QUIET).atMost(budget).until(condition::getAsBoolean);
	}

	protected void staysEmpty(final Address who) {
		staysTrue(() -> everywhere(node -> fleet().balanceOf(node, who) == 0));
	}

	protected void staysAsItIs() {
		fleet().awaitSameChain();

		final long height = fleet().height(0);
		final long supply = fleet().supplyOn(0);

		staysTrue(() -> everywhere(node -> fleet().height(node) == height && fleet().supplyOn(node) == supply));
	}

	protected Claim claimOf(final int wallet, final AccountKey owner) {
		return fleet().wallet(wallet).claim(owner, fleet().chainId());
	}

	/* The wallet claims onto the account at a node, and the account has the snapshot balance on every node */
	protected void claim(final int at, final int wallet, final AccountKey owner) {
		assertThat(fleet().submit(at, claimOf(wallet, owner)), equalTo(Optional.empty()));
		awaitBalance(owner, fleet().holding(wallet));
		fleet().awaitSameChain();
	}

	/* A refusal is the same on every node, whichever it is put to, and makes no block */
	protected void assertRefusedEverywhere(final Transaction transaction, final Optional<String> reason) {
		IntStream.range(0, fleet().size()).filter(fleet()::isRunning).forEach(node ->
			assertThat("node " + node, fleet().submit(node, transaction), equalTo(reason)));
	}
}
