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
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.service.ledger.LedgerFleet.COIN;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.MintSupply;

/* The fleet is started once, and every example works on it. The examples therefore do not depend on each other: each
   one claims from a wallet of its own, and a policy it needs it publishes itself, as a spork is replaced by the next. */
public class LedgerFleetTest extends BaseLedgerFleetTest {
	private static final int VALIDATORS = 4;

	private static AccountKey account(final int n) {
		return Ed25519.publicKey(LedgerTestChain.seed(500 + n));
	}

	private void allowClaims() {
		fleet().publishSpork(Type.LEDGER_BLACKLIST, new LedgerBlacklist.SporkData());
	}

	/* The wallet claims its funds onto the account at a validator, and the claim is in a block that every node that
	   runs holds when this returns */
	private void claimed(final int wallet, final AccountKey onto) {
		final LedgerFleet fleet = fleet();
		final long before = fleet.height(0);

		assertThat(fleet.submit(wallet % VALIDATORS, fleet.wallet(wallet).claim(onto, fleet.chainId())),
			equalTo(Optional.empty()));
		fleet.awaitHeight(before + 1);
		fleet.awaitSameChain();
	}

	@Example
	public void shouldStartEveryNodeOnTheLedgerProtocolWithTheSnapshotOfTheGenesis() {
		final LedgerFleet fleet = fleet();

		assertThat(fleet.size(), greaterThanOrEqualTo(VALIDATORS + 1));
		assertThat(fleet.validators(), is(VALIDATORS));

		IntStream.range(0, fleet.size()).forEach(node -> {
			assertThat(fleet.isRunning(node), is(true));
			assertThat(fleet.status(node).running(), is(true));
			assertThat(fleet.status(node).signing(), is(node < VALIDATORS));
			assertThat(fleet.supplyOn(node), greaterThanOrEqualTo(fleet.snapshotTotal()));
			assertThat(fleet.ledgerPeers(node), hasSize(fleet.size() - 1));
		});
	}

	@Example
	public void shouldKeepTheFilesOfEveryNodeInADirectoryOfItsOwn() {
		final LedgerFleet fleet = fleet();

		assertThat(IntStream.range(0, fleet.size()).mapToObj(fleet::directoryOf).distinct().count(),
			is((long) fleet.size()));

		IntStream.range(0, fleet.size()).forEach(node ->
			assertThat(Files.exists(fleet.directoryOf(node).resolve(LedgerNodes.BLOCK_LOG)), is(true)));
	}

	@Example
	public void shouldProduceABlockOnEveryNodeAfterATransaction() {
		final LedgerFleet fleet = fleet();
		final AccountKey owner = account(1);
		final long supply = fleet.supplyOn(0);

		allowClaims();
		claimed(0, owner);

		IntStream.range(0, fleet.size()).forEach(node -> {
			assertThat(fleet.balanceOf(node, owner), is(fleet.holding(0)));
			assertThat(fleet.balanceOf(node, fleet.wallet(0).address()), is(0L));
			assertThat(fleet.supplyOn(node), is(supply));
		});
	}

	@Example
	public void shouldDeliverASporkToEveryNodeThroughTheReceivePath() {
		final LedgerFleet fleet = fleet();
		final LedgerFee.SporkData fee = new LedgerFee.SporkData();

		fee.setMinimumTransferFee(new BigDecimal("0.0001"));

		final GridSpork published = fleet.publishSpork(Type.LEDGER_FEE, fee);

		IntStream.range(0, fleet.size()).forEach(node -> {
			assertThat(fleet.sporkOn(node, Type.LEDGER_FEE).getTimeStamp(), equalTo(published.getTimeStamp()));
			assertThat(fleet.sporkOn(node, Type.LEDGER_FEE).<LedgerFee.SporkData>getData().units(), is(10_000L));
		});
	}

	@Example
	public void shouldTakeNoSporkThatTwoNetworkKeysHaveNotSigned() {
		final LedgerFleet fleet = fleet();
		final GridSpork before = fleet.publishSpork(Type.LEDGER_FEE, new LedgerFee.SporkData());
		final LedgerFee.SporkData dearer = new LedgerFee.SporkData();

		dearer.setMinimumTransferFee(BigDecimal.ONE);
		fleet.deliverSpork(1, fleet.proposal(Type.LEDGER_FEE, dearer));

		assertThat(fleet.sporkOn(1, Type.LEDGER_FEE).getTimeStamp(), equalTo(before.getTimeStamp()));
		assertThat(fleet.sporkOn(1, Type.LEDGER_FEE).<LedgerFee.SporkData>getData().units(), is(0L));
	}

	@Example
	public void shouldCatchUpAStoppedNodeThatRestartsOverItsFiles() {
		final LedgerFleet fleet = fleet();
		final AccountKey keptBlock = account(2);
		final AccountKey missedBlock = account(6);

		allowClaims();
		claimed(1, keptBlock);

		final long held = fleet.height(3);

		fleet.stop(3);
		assertThat(fleet.isRunning(3), is(false));

		claimed(4, missedBlock);
		fleet.restart(3);
		fleet.awaitHeight(3, held + 1);
		fleet.awaitSameChain();
		await().until(() -> fleet.status(3).signing());

		assertThat(fleet.isRunning(3), is(true));
		assertThat(fleet.balanceOf(3, keptBlock), is(fleet.holding(1)));
		assertThat(fleet.balanceOf(3, missedBlock), is(fleet.holding(4)));
	}

	@Example
	public void shouldKeepTheChainWhenEveryNodeStopsAndRestartsOverItsFiles() {
		final LedgerFleet fleet = fleet();
		final AccountKey owner = account(7);

		allowClaims();
		claimed(5, owner);

		final LedgerService.Report before = fleet.status(0);

		IntStream.range(0, fleet.size()).forEach(fleet::stop);
		fleet.restart(3);

		assertThat(fleet.status(3).height(), is(before.height()));
		assertThat(fleet.status(3).tipHash(), equalTo(before.tipHash()));
		assertThat(fleet.balanceOf(3, owner), is(fleet.holding(5)));

		IntStream.range(0, fleet.size()).filter(node -> node != 3).forEach(fleet::restart);
		fleet.awaitSameChain();
		await().until(() -> IntStream.range(0, VALIDATORS).allMatch(node -> fleet.status(node).signing()));

		claimed(6, account(8));
		assertThat(fleet.balanceOf(0, account(8)), is(fleet.holding(6)));
	}

	@Example
	public void shouldSyncAnObserverThatJoinsAfterTheChainHasGrown() {
		final LedgerFleet fleet = fleet();
		final AccountKey owner = account(3);

		allowClaims();
		claimed(2, owner);

		final int observer = fleet.addObserver();

		fleet.awaitHeight(observer, fleet.height(0));
		fleet.awaitSameChain();

		assertThat(fleet.balanceOf(observer, owner), is(fleet.holding(2)));
		assertThat(fleet.supplyOn(observer), is(fleet.supplyOn(0)));
		assertThat(fleet.status(observer).signing(), is(false));
		assertThat(fleet.ledgerPeers(observer), hasSize(fleet.size() - 1));
	}

	@Example
	public void shouldMakeAMintDueByTheClockWithoutWaitingForIt() {
		final LedgerFleet fleet = fleet();
		final AccountKey recipient = account(4);
		final long due = fleet.clock().now() + Duration.ofHours(2).toMillis();
		final long supply = fleet.supplyOn(0);
		final MintSupply.SporkData ceiling = new MintSupply.SporkData();
		final MintStorage.SporkData mints = new MintStorage.SporkData();

		ceiling.setMaxSupply(BigDecimal.valueOf(1_000_000_000L));
		mints.setMints(Map.of(new Location(new Address(recipient.toString()), (int) (due / 1000)), BigDecimal.TEN));
		fleet.publishSpork(Type.MINT_SUPPLY, ceiling);
		fleet.publishSpork(Type.MINT_STORAGE, mints);

		await().during(Duration.ofSeconds(3)).until(() -> fleet.balanceOf(0, recipient) == 0);

		fleet.clock().advance(Duration.ofHours(3));
		await().until(() -> IntStream.range(0, fleet.size()).allMatch(node ->
			fleet.balanceOf(node, recipient) == 10 * COIN));

		IntStream.range(0, fleet.size()).forEach(node -> assertThat(fleet.supplyOn(node), is(supply + 10 * COIN)));
		assertThat(fleet.status(0).running(), is(true));
	}
}
