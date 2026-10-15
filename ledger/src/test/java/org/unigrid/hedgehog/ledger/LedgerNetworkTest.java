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

package org.unigrid.hedgehog.ledger;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.IntToLongFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.unigrid.hedgehog.ledger.Action.Broadcast;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

/* Four validators, each a ledger node over its own files, on the deterministic network of the simulator: its virtual
   time is the clock of every node, and the test plays the sender of the transactions, who hands each to every node
   and hands them again to a node that came back from its files, as the pool of waiting transactions is not kept on
   disk. */
public class LedgerNetworkTest {
	private static final int VALIDATORS = 4;
	private static final List<byte[]> SEEDS = seeds(1, VALIDATORS);
	private static final long GENESIS_MILLIS = 1000;
	private static final long FUNDED = 2000;
	private static final int FIRST = 0;
	private static final long TEN_SECONDS = 10_000;
	private static final long FINISHED_BY_MILLIS = 3 * TEN_SECONDS;

	/* Agreement and the evidence are looked at this often; the evidence goes at the end of a height, and a height of
	   a chain that works is over in a fraction of a second, so it is looked at all along and not at the end */
	private static final long CHECK_MILLIS = 50;
	private static final long WALLET_MILLIS = 1000;
	private static final long QUIET_MILLIS = 20_000;

	/* Far beyond what any of the scenarios needs on links that work, which only ends a run that is stuck */
	private static final long RECOVERY_MILLIS = 300_000;
	private static final long NETWORK_SEED_MASK = 0x5DEECE66DL;

	private static final long DUE_AT_MILLIS = 20_500;
	private static final int DUE_ACCOUNT = 5;
	private static final Mint DUE = mint(DUE_ACCOUNT, 700);
	private static final String AHEAD_OF_THE_CLOCK = "ahead of this node's clock";

	private static final LegacyWallet HELD = new LegacyWallet(1, true);
	private static final LegacyWallet CLEAN = new LegacyWallet(2, true);
	private static final Map<LegacyAddress, Long> SNAPSHOT = Map.of(HELD.address(), 500L, CLEAN.address(), 300L);
	private static final long SNAPSHOT_TOTAL = 800;

	private static final long BAD_NETWORK_MIN_MILLIS = 10_000;
	private static final long BAD_NETWORK_SPREAD_MILLIS = 10_000;
	private static final long RESTART_WINDOW_MILLIS = 500;
	private static final int RESTART_ODDS = 4;
	private static final int MOST_EXTRA_LATENCY_MILLIS = 600;
	private static final double MOST_LOSS = 0.4;
	private static final int FEWEST_TRANSFERS = 4;
	private static final int MORE_TRANSFERS = 4;
	private static final int PAYER_ONE = 1;
	private static final int PAYER_THREE = 3;
	private static final List<Integer> PAYEES = List.of(2, 4, 5);

	/* The chain a node starts from, which may differ in what the node knows: its authority can be asked the clock */
	@FunctionalInterface
	private interface ChainFactory {
		Supplier<LedgerState> chain(int index, LongSupplier clock);
	}

	private static final class Cluster implements AutoCloseable {
		private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());
		private final List<LedgerProcess> processes = new ArrayList<>();
		private final List<Transaction> sent = new ArrayList<>();
		private final int[] refilled = new int[VALIDATORS];
		private final SimulatedNetwork net;

		Cluster(long networkSeed, Supplier<LedgerState> chain) {
			this(networkSeed, (index, clock) -> chain, index -> 0);
		}

		Cluster(long networkSeed, Supplier<LedgerState> chain, IntToLongFunction lagMillis) {
			this(networkSeed, (index, clock) -> chain, lagMillis);
		}

		Cluster(long networkSeed, ChainFactory chains) {
			this(networkSeed, chains, index -> 0);
		}

		/* A node whose clock runs behind the virtual time of the network by the lag of its index */
		Cluster(long networkSeed, ChainFactory chains, IntToLongFunction lagMillis) {
			net = new SimulatedNetwork(VALIDATORS, networkSeed, (index, validators, clock) -> {
				final LongSupplier own = () -> clock.getAsLong() - lagMillis.applyAsLong(index);
				final LedgerProcess process = new LedgerProcess(fileSystem.getPath("/n" + index),
					chains.chain(index, own), seed(index + 1), Timeouts.DEFAULT, own);

				processes.add(process);
				return new SimNode(index, process);
			});
		}

		LedgerProcess process(int index) {
			return processes.get(index);
		}

		LedgerNode node(int index) {
			return process(index).node();
		}

		Ledger ledger(int index) {
			return process(index).ledger();
		}

		SimNode slot(int index) {
			return net.nodes.get(index);
		}

		IntStream indexes() {
			return IntStream.range(0, VALIDATORS);
		}

		/* Accounts 1 and 3 hold 1000 each before the network begins: a block that every ledger has taken in */
		void fund() throws IOException {
			final Ledger[] others = processes.stream().skip(1).map(LedgerProcess::ledger).toArray(Ledger[]::new);

			fundFirstAndThirdAccount(SEEDS, GENESIS_MILLIS, ledger(FIRST), others);
		}

		void begin() {
			net.begin();
			net.after(WALLET_MILLIS, this::refillRestarted);
		}

		Transfer transfer(int from, int to, long amount, long nonce) {
			return transfer(from, to, amount, 0, nonce);
		}

		Transfer transfer(int from, int to, long amount, long fee, long nonce) {
			return Transfer.signed(accountSeed(from), account(to), amount, fee, nonce, ledger(FIRST).chainId());
		}

		void sendAt(long time, int origin, Transaction transaction) {
			net.after(time - net.now(), () -> gossip(origin, transaction));
		}

		/* The node that got it first has it first, and every node that is up has it before anything else happens */
		void gossip(int origin, Transaction transaction) {
			sent.add(transaction);
			indexes().map(offset -> (origin + offset) % VALIDATORS).forEach(index -> offer(index, transaction));
		}

		/* To one node only, which is what a sender that reaches a single node does */
		void handTo(int index, Transaction transaction) {
			offer(index, transaction);
		}

		private void offer(int index, Transaction transaction) {
			if (!slot(index).crashed && node(index).submit(transaction).isEmpty()) {
				net.work(index);
			}
		}

		void restartFromDisk(int index) {
			net.restartFromDisk(index);
			refill(index);
		}

		/* Whatever is already in a chain is refused, so the node takes in only what it lacks */
		void refill(int index) {
			refilled[index] = process(index).restarts();
			sent.forEach(transaction -> offer(index, transaction));
		}

		private void refillRestarted() {
			indexes().filter(index -> process(index).restarts() != refilled[index] && !slot(index).crashed)
				.forEach(this::refill);
			net.after(WALLET_MILLIS, this::refillRestarted);
		}

		void runChecking(long untilMillis, BooleanSupplier done) {
			while (net.now() < untilMillis && !done.getAsBoolean()) {
				net.run(Math.min(untilMillis, net.now() + CHECK_MILLIS));
				assertNoEquivocation();
			}
		}

		/* A millisecond at a time, for the instant after something was said */
		void runUntil(BooleanSupplier reached, long limitMillis) {
			while (!reached.getAsBoolean()) {
				assertThat("Not reached by " + limitMillis + " ms", net.now() < limitMillis, is(true));
				net.run(net.now() + 1);

				if (net.now() % CHECK_MILLIS == 0) {
					assertNoEquivocation();
				}
			}
		}

		/* No node signed two things for a view or went back to an earlier one, none sent what it had not written
		   down first, none saw a validator say two things, and the blocks the nodes took in agree */
		void assertNoEquivocation() {
			assertThat(net.agreement(), is(true));
			assertThat(net.contradictions(), equalTo(0));
			assertThat(net.viewRegressions(), equalTo(0));
			assertThat(net.unsavedSends(), equalTo(0));
			processes.forEach(process -> assertThat(process.node().evidence(), is(empty())));
		}

		void assertSameChain() {
			final Ledger first = ledger(FIRST);

			assertNoEquivocation();
			processes.forEach(process -> {
				assertThat(process.ledger().height(), equalTo(first.height()));
				assertThat(process.ledger().tipHash(), equalTo(first.tipHash()));
				assertThat(process.ledger().stateRoot(), equalTo(first.stateRoot()));
			});
		}

		void assertBalances(Map<Integer, Long> expected) {
			expected.forEach((number, balance) -> processes.forEach(process -> assertThat("account " + number,
				process.ledger().balanceOf(account(number)), equalTo(balance))));
		}

		boolean everyBalanceIs(Map<Integer, Long> expected) {
			return processes.stream().allMatch(process -> expected.entrySet().stream()
				.allMatch(entry -> process.ledger().balanceOf(account(entry.getKey())) == entry.getValue()));
		}

		boolean everyHeightIs(long height) {
			return processes.stream().allMatch(process -> process.ledger().height() == height);
		}

		int armed() {
			return net.nodes.stream().mapToInt(node -> node.armed).sum();
		}

		/* Nobody says anything and no timer is armed, though the nodes tick and the sender has nothing to resend */
		void assertQuietFor(long millis) {
			final long said = net.sent();
			final int timers = armed();

			runChecking(net.now() + millis, () -> false);
			assertThat(net.sent(), equalTo(said));
			assertThat(armed(), equalTo(timers));
		}

		@Override
		public void close() throws IOException {
			fileSystem.close();
		}
	}

	private static Supplier<LedgerState> chainOf(LedgerAuthority authority) {
		return () -> state(1000, authority, SEEDS);
	}

	private static Supplier<LedgerState> snapshotChain(LedgerAuthority authority) {
		return () -> stateWithSnapshot(authority, SEEDS, SNAPSHOT_TOTAL, SNAPSHOT);
	}

	private static Supplier<LedgerState> blacklistingChain() {
		return snapshotChain(blacklisting(Set.of(HELD.address())));
	}

	/* The mint is listed from the time on and taken from then on, and nothing else is minted but the funds the
	   accounts start with */
	private static LedgerAuthority mintDueAt(long time) {
		return TestAuthority.open().withSchedule(now -> now >= time ? List.of(DUE) : List.of())
			.withMints((mint, now) -> !mint.equals(DUE) || now >= time);
	}

	private static Map<Integer, Long> afterTheFourTransfers() {
		return Map.of(1, 880L, 2, 110L, 3, 940L, 4, 70L);
	}

	private static void sendTheFourTransfers(Cluster cluster) {
		cluster.sendAt(0, 0, cluster.transfer(1, 2, 100, 0));
		cluster.sendAt(3_000, 1, cluster.transfer(3, 4, 50, 0));
		cluster.sendAt(7_000, 2, cluster.transfer(1, 4, 20, 1));
		cluster.sendAt(12_000, 3, cluster.transfer(3, 2, 10, 1));
	}

	private static List<Block> blocksOf(Cluster cluster, int index) {
		return cluster.slot(index).decided;
	}

	private static List<Message> signedIn(List<Action> actions) {
		return actions.stream().filter(Broadcast.class::isInstance).map(action -> ((Broadcast) action).message())
			.filter(message -> message instanceof Ballot || message instanceof Proposal).toList();
	}

	private static boolean signsSomething(List<Action> actions) {
		return !signedIn(actions).isEmpty();
	}

	private static boolean votedForABlock(LedgerNode node) {
		return node.outbox().stream().anyMatch(message -> message instanceof Ballot ballot && !ballot.isNil());
	}

	private static boolean isProposal(Message message) {
		return message instanceof Proposal;
	}

	private static boolean isPrevote(Message message) {
		return message instanceof Ballot ballot && ballot.type() == Ballot.Type.PREVOTE;
	}

	private static boolean isPrecommit(Message message) {
		return message instanceof Ballot ballot && ballot.type() == Ballot.Type.PRECOMMIT;
	}

	/* The node comes back from its files in the instant after it said what the moment is about: the simulator performs
	   an event as a whole, so what the event signed is on its way when control is back here. Whatever it had signed
	   is still in what it says again, as it signed it. */
	private static void restartAfterSaying(Cluster cluster, Set<Integer> remaining, Predicate<Message> moment) {
		final Predicate<Integer> saidIt = index -> cluster.node(index).outbox().stream().anyMatch(moment);

		cluster.runUntil(() -> remaining.stream().anyMatch(saidIt), cluster.net.now() + FINISHED_BY_MILLIS);

		final int index = remaining.stream().filter(saidIt).findFirst().orElseThrow();
		final List<Message> signed = cluster.node(index).outbox();

		remaining.remove(index);
		cluster.restartFromDisk(index);
		assertThat(cluster.node(index).outbox().containsAll(signed), is(true));
	}

	/* The node dies at the end of the next event of it that signs, once the sign state of it was written and before
	   anything of the event happened, and comes back from its files, which have what the lost event signed */
	private static void loseTheNextSigningOfOne(Cluster cluster, Set<Integer> remaining) {
		final int index = remaining.iterator().next();

		remaining.remove(index);
		cluster.net.loseNextActionsOf(index, LedgerNetworkTest::signsSomething);
		cluster.runUntil(() -> cluster.slot(index).loses == null, cluster.net.now() + FINISHED_BY_MILLIS);
		assertThat(signsSomething(cluster.slot(index).lost), is(true));
		assertThat(cluster.node(index).outbox().containsAll(signedIn(cluster.slot(index).lost)), is(true));
		cluster.refill(index);
	}

	@Example
	public void shouldBuildAChainOfTransfers() throws IOException {
		try (Cluster cluster = new Cluster(1, chainOf(ANY_MINT))) {
			cluster.fund();
			cluster.begin();
			sendTheFourTransfers(cluster);
			cluster.runChecking(FINISHED_BY_MILLIS, () -> false);

			cluster.assertSameChain();
			assertThat(cluster.ledger(FIRST).height(), greaterThanOrEqualTo(3L));
			assertThat(blocksOf(cluster, FIRST).stream().allMatch(block -> !block.getTransactions().isEmpty()),
				is(true));
			cluster.assertBalances(afterTheFourTransfers());
			cluster.processes.forEach(process -> assertThat(process.ledger().supply(), equalTo(FUNDED)));
			cluster.assertQuietFor(QUIET_MILLIS);
		}
	}

	@Example
	public void shouldMintWhenTheSporkComesDue() throws IOException {
		try (Cluster cluster = new Cluster(2, chainOf(mintDueAt(DUE_AT_MILLIS)))) {
			cluster.begin();
			cluster.runChecking(DUE_AT_MILLIS - 1, () -> false);

			assertThat(cluster.net.sent(), equalTo(0L));
			assertThat(cluster.armed(), equalTo(0));
			assertThat(cluster.everyHeightIs(0), is(true));

			cluster.runChecking(DUE_AT_MILLIS + TEN_SECONDS, () -> false);

			cluster.assertSameChain();
			assertThat(cluster.everyHeightIs(1), is(true));
			assertThat(blocksOf(cluster, FIRST).get(0).getTransactions(), equalTo(List.of(DUE)));
			cluster.assertBalances(Map.of(5, 700L));
			cluster.processes.forEach(process -> assertThat(process.ledger().supply(), equalTo(700L)));

			cluster.runChecking(DUE_AT_MILLIS + 6 * TEN_SECONDS, () -> false);

			cluster.assertSameChain();
			assertThat(cluster.everyHeightIs(1), is(true));
			cluster.processes.forEach(process -> assertThat(process.ledger().supply(), equalTo(700L)));
			cluster.assertQuietFor(QUIET_MILLIS);
		}
	}

	/* Each node comes back from its files once, in the instant after it proposed, after it prevoted, after it
	   precommitted and after an event of it was lost whole, and the chain does not notice */
	@Example
	public void shouldSurviveRestartsFromDisk() throws IOException {
		try (Cluster cluster = new Cluster(3, chainOf(ANY_MINT))) {
			final Set<Integer> remaining = new TreeSet<>(cluster.indexes().boxed().toList());

			cluster.fund();
			cluster.begin();
			sendTheFourTransfers(cluster);

			restartAfterSaying(cluster, remaining, LedgerNetworkTest::isProposal);
			cluster.runChecking(3_000, () -> false);
			restartAfterSaying(cluster, remaining, LedgerNetworkTest::isPrevote);
			cluster.runChecking(7_000, () -> false);
			loseTheNextSigningOfOne(cluster, remaining);
			cluster.runChecking(12_000, () -> false);
			restartAfterSaying(cluster, remaining, LedgerNetworkTest::isPrecommit);
			cluster.runChecking(FINISHED_BY_MILLIS, () -> false);

			assertThat(remaining, is(empty()));
			cluster.processes.forEach(process -> assertThat(process.restarts(), equalTo(1)));
			cluster.assertSameChain();
			cluster.assertBalances(afterTheFourTransfers());
			cluster.processes.forEach(process -> assertThat(process.ledger().supply(), equalTo(FUNDED)));
			cluster.assertQuietFor(QUIET_MILLIS);
		}
	}

	@Example
	public void shouldCatchUpANodeThatWasCrashed() throws IOException {
		try (Cluster cluster = new Cluster(4, chainOf(ANY_MINT))) {
			final int crashed = 2;
			final List<Integer> others = cluster.indexes().filter(index -> index != crashed).boxed().toList();

			cluster.fund();
			cluster.net.crash(crashed);
			cluster.begin();
			cluster.sendAt(0, 0, cluster.transfer(1, 2, 100, 0));
			cluster.sendAt(3_000, 1, cluster.transfer(3, 4, 50, 0));
			cluster.runUntil(() -> others.stream().allMatch(index -> cluster.ledger(index).height() == 3),
				FINISHED_BY_MILLIS);

			assertThat(cluster.ledger(crashed).height(), equalTo(1L));
			assertThat(cluster.slot(crashed).received, equalTo(0));

			cluster.restartFromDisk(crashed);
			cluster.sendAt(cluster.net.now() + 2_000, 3, cluster.transfer(1, 4, 20, 1));
			cluster.runChecking(RECOVERY_MILLIS, () -> cluster.everyHeightIs(4));

			cluster.assertSameChain();
			assertThat(cluster.everyHeightIs(4), is(true));
			cluster.assertBalances(Map.of(1, 880L, 2, 100L, 3, 950L, 4, 70L));
			assertThat(cluster.process(crashed).restarts(), equalTo(1));
			cluster.assertQuietFor(QUIET_MILLIS);
		}
	}

	/* What a node that learns of the mint at its own time knows: until then it neither lists the mint as due nor takes
	   it, though the time of the block is long past, as the node has not been told */
	private static LedgerAuthority knowingTheMintFrom(long learnedAt, LongSupplier clock) {
		final BooleanSupplier told = () -> clock.getAsLong() >= learnedAt;

		return TestAuthority.open()
			.withSchedule(now -> told.getAsBoolean() && now >= DUE_AT_MILLIS ? List.of(DUE) : List.of())
			.withMints((mint, now) -> !mint.equals(DUE) || told.getAsBoolean() && now >= DUE_AT_MILLIS);
	}

	/* Two nodes know the mint when it is due and the other two only twenty seconds later, so the first two propose
	   it in view after view and the others vote against it, until they know. A refusal that was made for what the node
	   did not know yet is not held against the block, or the chain would never get past it. */
	@Example
	public void shouldDecideTheMintOnceTheNodesThatDidNotKnowAboutItHaveBeenTold() throws IOException {
		final long told = DUE_AT_MILLIS + 2 * TEN_SECONDS;
		final ChainFactory chains = (index, clock) -> chainOf(
			knowingTheMintFrom(index < 2 ? DUE_AT_MILLIS : told, clock));

		try (Cluster cluster = new Cluster(11, chains)) {
			cluster.begin();
			cluster.runChecking(told - 1, () -> false);

			assertThat(cluster.everyHeightIs(0), is(true));

			cluster.runChecking(told + RECOVERY_MILLIS, () -> cluster.everyHeightIs(1));

			cluster.assertSameChain();
			assertThat(cluster.everyHeightIs(1), is(true));
			cluster.assertBalances(Map.of(5, 700L));
			cluster.assertQuietFor(QUIET_MILLIS);
		}
	}

	/* The nodes that have nothing to do rest through the views that pass before the turn of the node that has the
	   transaction comes, wake to what it says, and rest again when its block is in */
	@Example
	public void shouldGetATransactionThatOnlyOneNodeHoldsIntoABlockAndGoQuiet() throws IOException {
		try (Cluster cluster = new Cluster(9, chainOf(ANY_MINT))) {
			final Transfer transfer = cluster.transfer(1, 2, 100, 0);

			cluster.fund();
			cluster.begin();
			cluster.net.after(0, () -> cluster.handTo(FIRST, transfer));
			cluster.runChecking(RECOVERY_MILLIS, () -> cluster.everyHeightIs(2));

			cluster.assertSameChain();
			assertThat(cluster.everyHeightIs(2), is(true));
			cluster.assertBalances(Map.of(1, 900L, 2, 100L));
			cluster.assertQuietFor(QUIET_MILLIS);
		}
	}

	/* Two transfers that spend the same nonce reach the nodes in two orders, so the proposers differ in what they put
	   forward; one of them is decided, and the one that lost is not made again */
	@Example
	public void shouldDecideOneOfTwoTransfersThatSpendTheSameNonce() throws IOException {
		try (Cluster cluster = new Cluster(10, chainOf(ANY_MINT))) {
			final Transfer first = cluster.transfer(1, 2, 600, 0);
			final Transfer second = cluster.transfer(1, 4, 700, 0);

			cluster.fund();
			cluster.begin();
			cluster.net.after(0, () -> {
				cluster.handTo(0, first);
				cluster.handTo(1, first);
				cluster.handTo(2, second);
				cluster.handTo(3, second);
				cluster.indexes().forEach(index -> cluster.handTo(index, index < 2 ? second : first));
			});
			cluster.runChecking(RECOVERY_MILLIS, () -> cluster.everyHeightIs(2));

			final Ledger ledger = cluster.ledger(FIRST);
			final List<Long> outcome = List.of(ledger.balanceOf(account(1)), ledger.balanceOf(account(2)),
				ledger.balanceOf(account(4)));

			cluster.assertSameChain();
			assertThat(cluster.everyHeightIs(2), is(true));
			assertThat(List.of(List.of(400L, 600L, 0L), List.of(300L, 0L, 700L)), hasItem(outcome));
			cluster.processes.forEach(process -> assertThat(process.ledger().pending(), equalTo(0)));
			cluster.assertQuietFor(QUIET_MILLIS);
		}
	}

	/* The fee waits in a pool that a block pays out, and a block with nothing in it is made for that and for nothing
	   else, so the chain makes one more block and is quiet again */
	@Example
	public void shouldPayTheFeesOutInOneMoreBlockAndGoQuiet() throws IOException {
		try (Cluster cluster = new Cluster(7, chainOf(ANY_MINT))) {
			cluster.fund();
			cluster.begin();
			cluster.sendAt(0, 0, cluster.transfer(1, 2, 100, 10, 0));
			cluster.runChecking(FINISHED_BY_MILLIS, () -> false);

			cluster.assertSameChain();
			assertThat(cluster.everyHeightIs(3), is(true));
			assertThat(blocksOf(cluster, FIRST).get(2).getTransactions(), is(empty()));
			cluster.assertBalances(Map.of(1, 890L, 2, 100L));
			cluster.processes.forEach(process -> assertThat(process.ledger().validators().keys().stream()
				.mapToLong(process.ledger()::balanceOf).sum(), equalTo(10L)));
			cluster.processes.forEach(process -> assertThat(process.ledger().supply(), equalTo(FUNDED)));
			cluster.assertQuietFor(QUIET_MILLIS);
		}
	}

	/* A node whose clock is so far behind that every block looks dated ahead votes for no block, not even its own,
	   and the others do not wait for it; it takes in what they decide */
	@Example
	public void shouldFollowTheChainWhenTheClockOfOneNodeRunsFarBehind() throws IOException {
		final int slow = 1;
		final long lag = 2 * Ledger.MAX_CLOCK_SKEW_MILLIS;

		try (Cluster cluster = new Cluster(8, chainOf(ANY_MINT), index -> index == slow ? lag : 0)) {
			cluster.fund();
			cluster.begin();
			sendTheFourTransfers(cluster);

			for (long at = 1; at <= FINISHED_BY_MILLIS; at++) {
				cluster.net.run(at);
				assertThat(votedForABlock(cluster.node(slow)), is(false));
			}

			final Block last = blocksOf(cluster, FIRST).get(blocksOf(cluster, FIRST).size() - 1);

			cluster.assertSameChain();
			assertThat(cluster.ledger(slow).validate(last).orElse(""), containsString(AHEAD_OF_THE_CLOCK));
			assertThat(cluster.ledger(FIRST).validate(last).orElse(""), not(containsString(AHEAD_OF_THE_CLOCK)));
			cluster.assertBalances(afterTheFourTransfers());
			cluster.assertQuietFor(QUIET_MILLIS);
		}
	}

	@Example
	public void shouldRefuseABlacklistedClaimAtEveryNodeAndMakeNoBlockForIt() throws IOException {
		try (Cluster cluster = new Cluster(5, blacklistingChain())) {
			final Claim held = HELD.claim(account(1), cluster.ledger(FIRST).chainId());

			cluster.begin();
			cluster.indexes().forEach(index -> assertThat(cluster.node(index).submit(held).orElse(""),
				containsString("not authorized")));
			cluster.runChecking(FINISHED_BY_MILLIS, () -> false);

			assertThat(cluster.net.sent(), equalTo(0L));
			assertThat(cluster.armed(), equalTo(0));
			assertThat(cluster.everyHeightIs(0), is(true));
			cluster.processes.forEach(process -> assertThat(process.ledger().pending(), equalTo(0)));

			cluster.gossip(1, CLEAN.claim(account(2), cluster.ledger(FIRST).chainId()));
			cluster.runChecking(cluster.net.now() + FINISHED_BY_MILLIS, () -> cluster.everyHeightIs(1));

			cluster.assertSameChain();
			assertThat(cluster.everyHeightIs(1), is(true));
			cluster.assertBalances(Map.of(1, 0L, 2, 300L));
			cluster.assertQuietFor(QUIET_MILLIS);
		}
	}

	private static Proposal proposalOfView0By(Cluster cluster, int proposer, Block block) {
		return Proposal.signed(SEEDS.get(proposer), cluster.ledger(FIRST).chainId(), 1, 0, -1, block);
	}

	private static boolean votedNilIn(List<Message> said) {
		return said.stream().anyMatch(message -> message instanceof Ballot ballot && ballot.view() == 0
			&& ballot.type() == Ballot.Type.PREVOTE && ballot.isNil())
			&& said.stream().noneMatch(message -> message instanceof Ballot ballot && !ballot.isNil());
	}

	/* The proposer of the first view lies, with a block that holds a claim of a listed address, and the others do not
	   vote for it. The validators then certify the same block, made when the claim was allowed, and every node takes
	   it in, the proposer too once it is up again: what they signed stands, whatever a node's own list says. */
	@Example
	public void shouldVoteNilForABlacklistedClaimAndStillApplyTheBlockOnceItIsCertified() throws IOException {
		try (Cluster cluster = new Cluster(6, blacklistingChain());
			FileSystem lenientDisk = Jimfs.newFileSystem(Configuration.unix())) {
			final int liar = cluster.net.validators.keys().indexOf(cluster.net.validators.proposerAt(1, 0));
			final List<Integer> honest = cluster.indexes().filter(index -> index != liar).boxed().toList();
			final Ledger lenient = new Ledger(snapshotChain(ANY_MINT).get(),
				BlockLog.open(lenientDisk.getPath("/lenient/blocks.log")), () -> 0);

			lenient.submit(HELD.claim(account(1), lenient.chainId()));

			final Block made = proposed(lenient, 2000);
			final Block certified = certify(made, lenient, SEEDS.subList(0, lenient.validators().quorum()));

			cluster.net.crash(liar);
			cluster.begin();
			cluster.net.after(50, () -> honest.forEach(to -> cluster.net.send(liar, to,
				proposalOfView0By(cluster, liar, made))));
			cluster.runChecking(5_000, () -> false);

			honest.forEach(index -> assertThat(cluster.ledger(index).validate(made).orElse(""),
				containsString("not authorized")));
			honest.forEach(index -> assertThat(votedNilIn(cluster.node(index).outbox()), is(true)));
			assertThat(honest.stream().allMatch(index -> cluster.ledger(index).height() == 0), is(true));

			cluster.runChecking(cluster.net.now() + TEN_SECONDS, () -> false);
			cluster.assertQuietFor(QUIET_MILLIS);

			honest.forEach(to -> cluster.net.send(liar, to, new Decision(certified)));
			cluster.runChecking(cluster.net.now() + FINISHED_BY_MILLIS,
				() -> honest.stream().allMatch(index -> cluster.ledger(index).height() == 1));
			lenient.commit(certified);

			honest.forEach(index -> assertThat(cluster.ledger(index).balanceOf(account(1)), equalTo(500L)));
			honest.forEach(index -> assertThat(cluster.ledger(index).stateRoot(), equalTo(lenient.stateRoot())));

			cluster.restartFromDisk(liar);
			cluster.runChecking(cluster.net.now() + FINISHED_BY_MILLIS, () -> cluster.everyHeightIs(1));

			cluster.assertSameChain();
			assertThat(cluster.ledger(liar).stateRoot(), equalTo(lenient.stateRoot()));
			cluster.assertQuietFor(QUIET_MILLIS);
		}
	}

	private record Payment(long at, int origin, int from, int to, long amount, long nonce) {
	}

	/* Transfers of the two funded accounts, in order of nonce for each, at times of the bad stretch and a little after
	   it, to accounts that have nothing to pay with */
	private static List<Payment> randomPayments(Random random, long badMillis) {
		final int count = FEWEST_TRANSFERS + random.nextInt(MORE_TRANSFERS + 1);
		final Map<Integer, Long> nonces = new TreeMap<>(Map.of(PAYER_ONE, 0L, PAYER_THREE, 0L));
		final List<Long> times = random.longs(count, 0, badMillis + RESTART_WINDOW_MILLIS).sorted().boxed().toList();
		final List<Payment> payments = new ArrayList<>();

		for (final long at : times) {
			final int from = random.nextBoolean() ? PAYER_ONE : PAYER_THREE;
			final int to = PAYEES.get(random.nextInt(PAYEES.size()));

			payments.add(new Payment(at, random.nextInt(VALIDATORS), from, to, 1 + random.nextInt(50),
				nonces.merge(from, 1L, Long::sum) - 1));
		}

		return payments;
	}

	private static Map<Integer, Long> balancesAfter(List<Payment> payments) {
		final Map<Integer, Long> balances = new TreeMap<>(Map.of(PAYER_ONE, 1000L, PAYER_THREE, 1000L));

		payments.forEach(payment -> {
			balances.merge(payment.from(), -payment.amount(), Long::sum);
			balances.merge(payment.to(), payment.amount(), Long::sum);
		});
		return balances;
	}

	/* In each window of half a second a node restarts with a chance of one in RESTART_ODDS, at a moment of the
	   window and in one of the two ways */
	private static void scheduleRestarts(Cluster cluster, Random random, long untilMillis) {
		for (long window = 0; window < untilMillis; window += RESTART_WINDOW_MILLIS) {
			final long start = window;

			cluster.indexes().filter(index -> random.nextInt(RESTART_ODDS) == 0).forEach(index -> {
				final long at = start + random.nextInt((int) RESTART_WINDOW_MILLIS);
				final boolean loseTheEvent = random.nextBoolean();

				cluster.net.after(at, () -> restartOrLose(cluster, index, loseTheEvent));
			});
		}
	}

	private static void restartOrLose(Cluster cluster, int index, boolean loseTheEvent) {
		if (loseTheEvent) {
			cluster.net.loseNextActionsOf(index);
		} else {
			cluster.restartFromDisk(index);
		}
	}

	private static Set<Integer> randomGroup(Random random) {
		final Set<Integer> group = new TreeSet<>();
		final int size = 1 + random.nextInt(VALIDATORS - 1);

		while (group.size() < size) {
			group.add(random.nextInt(VALIDATORS));
		}

		return group;
	}

	/* A stretch of delays, losses, a split of the network and restarts of nodes from their files, and then a network
	   that works. The transfers are handed to every node all along, so that the work is the same everywhere, and every
	   node ends on the same chain with all of them in and the mint, which fell due in the stretch, made once. */
	@Property(tries = 15)
	public void shouldAgreeUnderRandomFaults(@ForAll long seed) throws IOException {
		final Random random = new Random(seed);
		final long badMillis = BAD_NETWORK_MIN_MILLIS + random.nextInt((int) BAD_NETWORK_SPREAD_MILLIS);
		final LedgerAuthority authority = mintDueAt(random.nextInt((int) badMillis));

		try (Cluster cluster = new Cluster(seed ^ NETWORK_SEED_MASK, chainOf(authority))) {
			final List<Payment> payments = randomPayments(random, badMillis);
			final Map<Integer, Long> finalBalances = balancesAfter(payments);

			finalBalances.merge(DUE_ACCOUNT, DUE.amount(), Long::sum);
			cluster.fund();
			cluster.net.latency(5, 20 + random.nextInt(MOST_EXTRA_LATENCY_MILLIS));
			cluster.net.dropRate(random.nextDouble() * MOST_LOSS);

			if (random.nextBoolean()) {
				cluster.net.partition(randomGroup(random));
			}

			scheduleRestarts(cluster, random, badMillis);
			payments.forEach(payment -> cluster.sendAt(payment.at(), payment.origin(),
				cluster.transfer(payment.from(), payment.to(), payment.amount(), payment.nonce())));
			cluster.begin();
			cluster.runChecking(badMillis, () -> false);

			cluster.net.latency(5, 50);
			cluster.net.dropRate(0);
			cluster.net.heal();
			cluster.runChecking(badMillis + RECOVERY_MILLIS, () -> cluster.everyBalanceIs(finalBalances));

			cluster.assertSameChain();
			cluster.assertBalances(finalBalances);
			cluster.processes.forEach(process -> assertThat(process.ledger().supply(),
				equalTo(FUNDED + DUE.amount())));
		}
	}
}
