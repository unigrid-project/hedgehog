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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.ShrinkingMode;
import org.unigrid.hedgehog.ledger.LedgerDriver.Checkpoint;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import org.unigrid.hedgehog.ledger.MemoryNetwork.Sent;
import org.unigrid.hedgehog.ledger.MemoryNetwork.Sent;
import org.unigrid.hedgehog.ledger.MemoryNetwork.Station;
import org.unigrid.hedgehog.ledger.PeerLimiter.Kind;
import org.unigrid.hedgehog.ledger.PeerLimiter.Policy;
import org.unigrid.hedgehog.ledger.PeerLimiter.Rate;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

/* Drivers of validators, each over files of its own, on a network of virtual time that delays, loses and cuts what
   they send. The only things a test does are what a person or an attacker could do: hand a transaction to one driver,
   stop and start one, join a new one, or speak to one as a peer. */
public class LedgerDriverNetworkTest {
	private static final int VALIDATORS = 4;
	private static final long FUNDING_MILLIS = 1_000;
	private static final long GRACE_MILLIS = 3_000;
	private static final long WARM_UP_MILLIS = 5_000;
	private static final long SECOND = 1_000;
	private static final long TEN_SECONDS = 10_000;
	private static final long QUIET_MILLIS = 30_000;
	private static final DriverOptions OPTIONS = new DriverOptions(SECOND, 10_000, 10_000, GRACE_MILLIS, 10_000,
		10_000, 100, Optional.empty());

	private static final double LOSS = 0.15;
	private static final long MIN_LATENCY_MILLIS = 20;
	private static final long MAX_LATENCY_MILLIS = 300;
	private static final int PAYMENTS = 8;
	private static final long PAID = PAYMENTS * (PAYMENTS + 1) / 2;
	private static final long FAULTS_MILLIS = 20_000;
	private static final long RECOVERY_MILLIS = 60_000;

	/* The wallet needs about 8 seconds for its transfers when nothing fails. With the faults they are paid by then
	   plus the time the faults last plus a margin for the nodes that were stopped to catch up; the slowest of 300 runs
	   needed one second more than the first two, and the margin is ten. */
	private static final long FAULT_FREE_MILLIS = 10_000;
	private static final long PAID_BY_MILLIS = FAULT_FREE_MILLIS + FAULTS_MILLIS + 10_000;

	private static final long RARE_TICK_MILLIS = 60_000;

	private static final long HOSTILE_NONCE = 1_000_000;
	private static final int FLOOD_FACTOR = 100;
	private static final long FLOOD_BURST_MILLIS = 10;
	private static final long FLOOD_MILLIS = 1_000;

	/* A peer that the test speaks for: it takes what the driver sends it, and never answers a request */
	private static final class Visitor implements LedgerPeer {
		private final String id;

		private Visitor(String id) {
			this.id = id;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public void send(Object wire) {
			/* What the driver passes on to this peer is of no interest here */
		}

		@Override
		public CompletableFuture<BlockBatch> request(BlockRequest request) {
			return new CompletableFuture<>();
		}
	}

	/* Hands out transfers of the two funded accounts with the next nonce of each */
	private static final class Payer {
		private final byte[] chainId;
		private final Map<Integer, Long> nonces = new HashMap<>();

		private Payer(MemoryNetwork network) {
			this.chainId = network.chainId();
		}

		private Transfer pay(int from, int to, long amount) {
			final long nonce = nonces.merge(from, 1L, Long::sum) - 1;

			return Transfer.signed(accountSeed(from), account(to), amount, 0, nonce, chainId);
		}
	}

	private static MemoryNetwork cluster(long seed, int size, LedgerAuthority authority, DriverOptions options) {
		final MemoryNetwork network = new MemoryNetwork(seeds(1, size), authority, seed);

		for (int index = 0; index < size; index++) {
			network.add(seed(1 + index), options);
		}

		network.connectAll();
		return network;
	}

	private static MemoryNetwork cluster(long seed, int size) {
		return cluster(seed, size, ANY_MINT, OPTIONS);
	}

	/* Accounts 1 and 3 hold 1000 each in a block that every node that starts has on disk */
	private static Block funding(MemoryNetwork network) throws IOException {
		final Ledger funder = network.scratch();

		funder.submit(mint(1, 1000));
		funder.submit(mint(3, 1000));
		return nextBlock(funder, network.validators(), FUNDING_MILLIS);
	}

	private static void start(MemoryNetwork network, Block funding, int... indexes) throws IOException {
		for (final int index : indexes) {
			network.station(index).preload(funding);
			network.station(index).start();
		}
	}

	/* Every validator runs, has been connected to the others and has passed its grace period */
	private static MemoryNetwork started(long seed, int size, LedgerAuthority authority, DriverOptions options)
		throws IOException {
		final MemoryNetwork network = cluster(seed, size, authority, options);

		start(network, funding(network), IntStream.range(0, size).toArray());
		network.run(network.now() + WARM_UP_MILLIS);
		return network;
	}

	private static MemoryNetwork started(long seed, int size, LedgerAuthority authority) throws IOException {
		return started(seed, size, authority, OPTIONS);
	}

	private static MemoryNetwork started(long seed, int size) throws IOException {
		return started(seed, size, ANY_MINT, OPTIONS);
	}

	private static boolean everyHeightIs(MemoryNetwork network, long height) {
		return network.stations().stream().allMatch(station -> station.ledger().height() == height);
	}

	private static void assertSameChain(MemoryNetwork network) {
		final Ledger first = network.station(0).ledger();

		network.stations().forEach(station -> {
			assertThat(station.id(), station.ledger().height(), equalTo(first.height()));
			assertThat(station.id(), station.ledger().tipHash(), equalTo(first.tipHash()));
			assertThat(station.id(), station.ledger().stateRoot(), equalTo(first.stateRoot()));
		});
	}

	/* No validator said two things for one view, however often it was stopped and started: what each station sent of
	   what it had signed itself, by what it was signed for */
	private static void assertNoEquivocation(MemoryNetwork network) {
		final Map<List<Object>, Set<Message>> said = new HashMap<>();

		for (final Sent sent : network.sent()) {
			if (sent.wire() instanceof Ballot ballot && signedBy(network, sent, ballot.validator())) {
				said.computeIfAbsent(List.of(ballot.validator(), ballot.type(), ballot.height(),
					ballot.view()), slot -> new HashSet<>()).add(ballot);
			} else if (sent.wire() instanceof Proposal proposal && signedBy(network, sent, proposal.sender())) {
				said.computeIfAbsent(List.of(proposal.sender(), "proposal", proposal.height(),
					proposal.view()), slot -> new HashSet<>()).add(proposal);
			}
		}

		assertThat(said.values().stream().filter(messages -> messages.size() > 1).count(), equalTo(0L));
	}

	/* Honest nodes never hold one another to account, whatever the network does to what they send */
	private static void assertNobodyPenalised(MemoryNetwork network) {
		for (final Station judge : network.stations()) {
			for (final Station peer : network.stations()) {
				if (judge.up() && judge.limiter().isPenalised(peer.id())) {
					throw new AssertionError(judge.id() + " penalised " + peer.id() + " at "
						+ network.now());
				}
			}
		}
	}

	private static boolean signedBy(MemoryNetwork network, Sent sent, AccountKey signer) {
		return sent.from() < network.validators().size()
			&& Ed25519.publicKey(network.validators().get(sent.from())).equals(signer);
	}

	private static Set<Class<?>> kindsSince(MemoryNetwork network, int from) {
		return network.sent().subList(from, network.sent().size()).stream().map(sent -> sent.wire().getClass())
			.collect(Collectors.toSet());
	}

	/* A transfer to one of the stations at a time, in turn, and the network runs until all of the running ones have
	   the block of it, so that every transfer makes a block of its own */
	private static void produce(MemoryNetwork network, Payer payer, int blocks, List<Station> running) {
		for (int n = 0; n < blocks; n++) {
			final long expected = running.get(0).ledger().height() + 1;
			final Transfer transfer = payer.pay(1 + 2 * (n % 2), 2, 1);

			assertThat(running.get(n % running.size()).driver().submit(transfer).join(),
				equalTo(Optional.empty()));
			assertThat("Block " + expected, network.runUntil(
				() -> running.stream().allMatch(station -> station.ledger().height() == expected),
				network.now() + 2 * TEN_SECONDS), is(true));
		}
	}

	private static boolean signedAfter(Ledger ledger, long height, AccountKey validator) {
		return ledger.blocksFrom(height + 1, BlockBatch.MAX_BLOCKS).stream()
			.flatMap(block -> block.getEndorsements().stream())
			.anyMatch(endorsement -> endorsement.signer().equals(validator));
	}

	/* A certified block of the height the ledger has reached, that is not the block it holds there: more than two
	   thirds of the validators signed it, which is to say that more than a third of them signed two blocks */
	private static Block conflictingTip(MemoryNetwork network, Ledger held) throws IOException {
		final Ledger rival = network.scratch();

		held.blocksFrom(1, (int) held.height() - 1).forEach(block -> commit(rival, block));
		rival.submit(mint(77, 5));
		return nextBlock(rival, network.validators(), network.now());
	}

	private static void commit(Ledger ledger, Block block) {
		try {
			ledger.commit(block);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/* The item is made as many times in each burst as a peer may send in that time, times the factor by which the flood
	   outruns the rate that peers are given */
	private static void hammer(MemoryNetwork network, Kind kind, long millis, IntConsumer item) {
		final int perBurst = (int) (Policy.DEFAULT.rates().get(kind).refillPerSecond() * FLOOD_FACTOR
			* FLOOD_BURST_MILLIS / SECOND);

		for (long at = 0; at < millis; at += FLOOD_BURST_MILLIS) {
			network.after(at, () -> IntStream.range(0, perBurst).forEach(item));
		}
	}

	/* Once for each peer that it was sent to, and no more often */
	private static void assertSentOncePerPeer(MemoryNetwork network, Object wire, int size) {
		final Map<List<Integer>, Long> sends = network.sent().stream().filter(sent -> sent.wire().equals(wire))
			.collect(Collectors.groupingBy(sent -> List.of(sent.from(), sent.to()), Collectors.counting()));

		assertThat(sends.values().stream().max(Long::compare).orElse(0L), lessThanOrEqualTo(1L));
		assertThat(sends.size(), lessThanOrEqualTo(size * (size - 1)));
		assertThat(sends.size(), greaterThanOrEqualTo(size - 1));
	}

	private static DriverOptions trusting(Checkpoint checkpoint) {
		return new DriverOptions(SECOND, 10_000, 10_000, GRACE_MILLIS, 10_000, 10_000, 100, Optional.of(checkpoint));
	}

	@Example
	public void shouldGossipATransactionAndBuildABlockOnEveryNode() throws IOException {
		try (MemoryNetwork network = started(1, VALIDATORS)) {
			final Station origin = network.station(2);

			assertThat(network.stations().stream().allMatch(station -> station.driver().signing()), is(true));
			assertThat(origin.driver().submit(new Payer(network).pay(1, 2, 100)).join(),
				equalTo(Optional.empty()));
			assertThat(network.runUntil(() -> everyHeightIs(network, 2), network.now() + TEN_SECONDS), is(true));

			assertSameChain(network);
			network.stations().forEach(station -> {
				assertThat(station.ledger().balanceOf(account(1)), equalTo(900L));
				assertThat(station.ledger().balanceOf(account(2)), equalTo(100L));
			});
			assertThat(network.sent(Transaction.class).size(), lessThanOrEqualTo(VALIDATORS * (VALIDATORS - 1)));
			assertThat(network.sent(Transaction.class).size(), greaterThanOrEqualTo(VALIDATORS - 1));

			network.run(network.now() + TEN_SECONDS);

			final int settled = network.sent().size();

			network.run(network.now() + QUIET_MILLIS);

			assertThat(kindsSince(network, settled), equalTo(Set.<Class<?>>of(Status.class, Heartbeat.class)));
			assertSameChain(network);
		}
	}

	@Example
	public void shouldSyncALateJoiner() throws IOException {
		try (MemoryNetwork network = cluster(2, VALIDATORS)) {
			final Payer payer = new Payer(network);
			final Station joiner = network.station(3);
			final List<Station> three = network.stations().subList(0, 3);
			final AccountKey joinerKey = Ed25519.publicKey(seed(4));

			start(network, funding(network), 0, 1, 2);
			network.run(network.now() + WARM_UP_MILLIS);
			produce(network, payer, 40, three);

			final Ledger tip = network.station(0).ledger();
			final long tipHeight = tip.height();
			final long joinedAt = network.now();

			network.observe(() -> {
				assertThat("A joiner votes only at the tip it joined at", !joiner.up() || !joiner.driver()
					.signing() || joiner.ledger().height() >= tipHeight, is(true));
				assertThat("A joiner votes only after the grace period", !joiner.up() || !joiner.driver()
					.signing() || network.now() >= joinedAt + GRACE_MILLIS, is(true));
				assertNobodyPenalised(network);
			});
			joiner.start();

			assertThat(network.runUntil(() -> joiner.ledger().height() == tipHeight,
				network.now() + TEN_SECONDS), is(true));
			assertThat(joiner.ledger().tipHash(), equalTo(tip.tipHash()));
			assertThat(joiner.ledger().stateRoot(), equalTo(tip.stateRoot()));
			assertThat(network.runUntil(() -> joiner.driver().signing(), network.now() + TEN_SECONDS), is(true));

			final long promotedAt = joiner.ledger().height();

			produce(network, payer, 8, network.stations());

			assertThat(joiner.built(), equalTo(List.of(false, true)));
			assertThat(signedAfter(network.station(0).ledger(), promotedAt, joinerKey), is(true));
			assertSameChain(network);
			assertNoEquivocation(network);
		}
	}

	/* Transfers one after the other, each into a block of its own: how long that takes when nothing fails */
	@Example
	public void shouldPayEveryTransferInABlockOfItsOwnWhenNothingFails() throws IOException {
		assertThat(payThroughFaults(0, false), lessThanOrEqualTo(FAULT_FREE_MILLIS));
	}

	/* 15% of what is sent is lost, messages take from 20 to 300 ms on a link, two nodes are cut off from the other two
	   for a while, and two nodes stop and start again over their files, while a wallet pays one transfer after the
	   other, each to one driver. The same transfers are all in a block of their own a bounded time later than
	   without the faults, every node is on the same chain, and all four validators vote and make blocks again. */
	@Property(tries = 10, shrinking = ShrinkingMode.OFF)
	public void shouldSurviveLossAndRestarts(@ForAll long seed) throws IOException {
		assertThat(payThroughFaults(seed, true), lessThanOrEqualTo(PAID_BY_MILLIS));
	}

	/* The time that the wallet took to pay, once the chain is shown to be whole and to go on */
	private static long payThroughFaults(long seed, boolean faults) throws IOException {
		final Random random = new Random(seed);

		try (MemoryNetwork network = started(seed, VALIDATORS)) {
			final Payer payer = new Payer(network);
			final Wallet wallet = new Wallet(network, random, IntStream.range(0, PAYMENTS)
				.mapToObj(n -> payer.pay(1 + 2 * (n % 2), 2, 1 + n)).toList());
			final long start = network.now();

			network.observe(() -> assertNobodyPenalised(network));

			if (faults) {
				network.dropRate(LOSS);
				scheduleLinks(network, random);
				schedulePartition(network, random);
				scheduleRestarts(network, random);
			}

			network.run(start + FAULTS_MILLIS);
			network.dropRate(0);
			network.heal();

			assertThat("Recovered", network.runUntil(() -> wallet.done() && network.stations().stream()
				.allMatch(station -> station.up() && station.driver().signing())
				&& everyHeightIs(network, network.station(0).ledger().height()),
				start + FAULTS_MILLIS + RECOVERY_MILLIS), is(true));

			assertSameChain(network);
			assertThat(network.station(0).ledger().height(), equalTo(1L + PAYMENTS));
			network.stations().forEach(station -> assertThat(station.id(),
				station.ledger().balanceOf(account(2)), equalTo(PAID)));
			assertThat(network.dropped() > 0, is(faults));
			assertThat(network.stations().stream().mapToInt(Station::starts).sum(),
				equalTo(VALIDATORS + (faults ? 2 : 0)));

			produce(network, payer, 3, network.stations());
			assertSameChain(network);
			assertNoEquivocation(network);
			return wallet.millis();
		}
	}

	/* The tick is so slow that only the call that follows an accepted transaction can wake the proposer in time */
	@Example
	public void shouldBuildABlockAtOnceAndNotAtTheNextTick() throws IOException {
		final DriverOptions rare = new DriverOptions(RARE_TICK_MILLIS, 10_000, 10_000, GRACE_MILLIS, 10_000, 10_000,
			100, Optional.empty());

		try (MemoryNetwork network = started(11, VALIDATORS, ANY_MINT, rare)) {
			assertThat(network.station(2).driver().submit(new Payer(network).pay(1, 2, 100)).join(),
				equalTo(Optional.empty()));
			assertThat(network.runUntil(() -> everyHeightIs(network, 2), network.now() + TEN_SECONDS), is(true));
			assertSameChain(network);
		}
	}

	/* One node fails on a question to its authority. It is replaced by a node over the same files, never used again,
	   that goes on voting where the failed one left off, and no node says two things for a view. */
	@Example
	public void shouldReplaceANodeThatFailsAndKeepVoting() throws IOException {
		final AtomicBoolean failing = new AtomicBoolean();
		final LedgerAuthority breaking = TestAuthority.open().withSchedule(time -> {
			if (failing.getAndSet(false)) {
				throw new IllegalStateException("The authority failed");
			}

			return List.of();
		});

		try (MemoryNetwork network = started(12, VALIDATORS, breaking)) {
			final Payer payer = new Payer(network);

			produce(network, payer, 2, network.stations());
			failing.set(true);
			network.run(network.now() + 2 * SECOND);

			final List<Station> replaced = network.stations().stream()
				.filter(station -> station.built().size() > 2).toList();

			assertThat(replaced.size(), equalTo(1));
			assertThat(replaced.get(0).built(), equalTo(List.of(false, true, true)));

			produce(network, payer, 3, network.stations());

			assertThat(network.stations().stream().noneMatch(station -> station.driver().halted()), is(true));
			assertThat(network.stations().stream().allMatch(station -> station.driver().signing()), is(true));
			assertSameChain(network);
			assertNoEquivocation(network);
		}
	}

	/* The whole network stops and starts again over its files: every node waits for the grace period and then votes,
	   and the chain goes on from the block it stopped at */
	@Example
	public void shouldResumeWhenEveryNodeRestarts() throws IOException {
		try (MemoryNetwork network = started(9, VALIDATORS)) {
			final Payer payer = new Payer(network);

			produce(network, payer, 3, network.stations());
			network.stations().forEach(Station::stop);
			network.run(network.now() + 2 * SECOND);
			network.stations().forEach(Station::start);

			assertThat(network.runUntil(() -> network.stations().stream().allMatch(station -> station.driver()
				.signing()), network.now() + 2 * GRACE_MILLIS), is(true));
			produce(network, payer, 3, network.stations());

			assertThat(network.station(0).ledger().height(), equalTo(7L));
			assertSameChain(network);
			assertNoEquivocation(network);
		}
	}

	/* The way an operator upgrades four nodes: one at a time, with the other three making blocks meanwhile */
	@Example
	public void shouldKeepMakingBlocksWhileEachNodeInTurnRestarts() throws IOException {
		try (MemoryNetwork network = started(10, VALIDATORS)) {
			final Payer payer = new Payer(network);

			for (final Station station : network.stations()) {
				station.stop();
				produce(network, payer, 2, network.stations().stream().filter(Station::up).toList());
				station.start();

				assertThat(network.runUntil(() -> station.driver().signing() && station.ledger().height()
					== network.station((station.index() + 1) % VALIDATORS).ledger().height(),
					network.now() + 2 * GRACE_MILLIS), is(true));
			}

			produce(network, payer, 2, network.stations());

			assertThat(network.station(0).ledger().height(), equalTo(11L));
			assertSameChain(network);
			assertNoEquivocation(network);
		}
	}

	private static void scheduleLinks(MemoryNetwork network, Random random) {
		for (int one = 0; one < VALIDATORS; one++) {
			for (int other = one + 1; other < VALIDATORS; other++) {
				network.latency(one, other, MIN_LATENCY_MILLIS, MIN_LATENCY_MILLIS
					+ random.nextInt((int) (MAX_LATENCY_MILLIS - MIN_LATENCY_MILLIS) + 1));
			}
		}
	}

	private static void schedulePartition(MemoryNetwork network, Random random) {
		final int first = random.nextInt(VALIDATORS);
		final int second = (first + 1 + random.nextInt(VALIDATORS - 1)) % VALIDATORS;
		final long cutAt = 3 * SECOND + random.nextInt((int) (5 * SECOND));

		network.after(cutAt, () -> network.partition(Set.of(first, second)));
		network.after(cutAt + 3 * SECOND + random.nextInt((int) (3 * SECOND)), network::heal);
	}

	/* Each of the two stops at some moment of its own stretch of the faults and starts again a few seconds later */
	private static void scheduleRestarts(MemoryNetwork network, Random random) {
		for (int n = 0; n < 2; n++) {
			final Station station = network.station(random.nextInt(VALIDATORS));
			final long stopAt = 3 * SECOND + n * 8 * SECOND + random.nextInt((int) (5 * SECOND));

			network.after(stopAt, station::stop);
			network.after(stopAt + SECOND + random.nextInt((int) (2 * SECOND)), station::start);
		}
	}

	@Example
	public void shouldHaltOnAForkAndKeepServing() throws IOException {
		try (MemoryNetwork network = started(4, VALIDATORS)) {
			final Payer payer = new Payer(network);
			final Station forked = network.station(3);
			final List<Station> others = network.stations().subList(0, 3);

			produce(network, payer, 3, network.stations());

			final long height = forked.ledger().height();
			final Block rival = conflictingTip(network, forked.ledger());

			forked.driver().onMessage(new Visitor("X"), new Decision(rival), DriverRig.FRAME);

			assertThat(forked.driver().halted(), is(true));
			assertThat(forked.driver().haltReason().orElse(""),
				containsString("Two certified blocks at height " + height));

			final int haltedAt = network.sent().size();

			produce(network, payer, 3, others);

			assertThat(forked.ledger().height(), equalTo(height));
			assertThat(others.get(0).ledger().height(), equalTo(height + 3));
			assertThat(network.sent().subList(haltedAt, network.sent().size()).stream()
				.noneMatch(sent -> sent.from() == forked.index()), is(true));
			assertThat(others.stream().noneMatch(station -> station.driver().halted()), is(true));

			final Station joiner = network.add(null, OPTIONS);

			network.connect(joiner.index(), forked.index());
			joiner.start();
			joiner.driver().onStatus(joiner.peerOf(forked.index()), forked.ledger().status(), DriverRig.FRAME);

			assertThat(network.runUntil(() -> joiner.ledger().height() == height, network.now() + TEN_SECONDS),
				is(true));
			assertThat(joiner.ledger().tipHash(), equalTo(forked.ledger().tipHash()));
			assertThat(joiner.driver().halted(), is(false));
			assertThat(forked.limiter().isPenalised(joiner.id()), is(false));
		}
	}

	@Example
	public void shouldRefuseAWrongCheckpoint() throws IOException {
		try (MemoryNetwork network = started(5, VALIDATORS)) {
			produce(network, new Payer(network), 12, network.stations());

			final byte[] right = network.station(0).ledger().hashAt(8).orElseThrow();
			final byte[] wrong = Digests.sha512("not the block".getBytes(StandardCharsets.US_ASCII));
			final Station refusing = network.add(null, trusting(new Checkpoint(8, wrong)));
			final Station agreeing = network.add(null, trusting(new Checkpoint(8, right)));

			network.connectAll();
			refusing.start();
			agreeing.start();

			assertThat(network.runUntil(() -> agreeing.ledger().height() == 13 && refusing.driver().halted(),
				network.now() + TEN_SECONDS), is(true));
			assertThat(agreeing.driver().halted(), is(false));
			assertThat(agreeing.ledger().tipHash(), equalTo(network.station(0).ledger().tipHash()));
			assertThat(refusing.driver().halted(), is(true));
			assertThat(refusing.driver().haltReason().orElse(""), containsString("Block 8 differs"));
			assertThat(refusing.ledger().height(), equalTo(7L));
		}
	}

	/* The person who pays, one transfer at a time: the next goes to one running driver when the one before it is in a
	   block, and goes to every running driver when it is in none after a while. A driver that took a transfer in and
	   lost the way to the others keeps it to itself, and one that was stopped has lost its pool, so only a
	   transfer that is handed again to a node that lacks it spreads. Blocks are read from the longest chain. */
	private static final class Wallet {
		private static final long PATIENCE_MILLIS = 8_000;
		private static final long PAUSE_MILLIS = 1_000;
		private static final long LOOK_MILLIS = 100;

		private final MemoryNetwork network;
		private final Random random;
		private final List<Transfer> payments;
		private final Set<String> included = new HashSet<>();
		private final long beganAt;
		private long scanned;
		private int next;
		private long handedAt = -1;
		private long pausedUntil;
		private long finishedAt = -1;

		private Wallet(MemoryNetwork network, Random random, List<Transfer> payments) {
			this.network = network;
			this.random = random;
			this.payments = payments;
			this.beganAt = network.now();
			network.after(LOOK_MILLIS, this::look);
		}

		private void look() {
			scan();

			while (next < payments.size() && included.contains(Bytes.hex(payments.get(next).id()))) {
				next++;
				handedAt = -1;
				pausedUntil = network.now() + PAUSE_MILLIS;
			}

			if (next == payments.size()) {
				finishedAt = network.now();
				return;
			}

			if (network.now() >= pausedUntil && handedAt < 0) {
				hand(payments.get(next), false);
			} else if (handedAt >= 0 && network.now() - handedAt >= PATIENCE_MILLIS) {
				hand(payments.get(next), true);
			}

			network.after(LOOK_MILLIS, this::look);
		}

		private void hand(Transfer transfer, boolean toEveryone) {
			final List<Station> running = network.stations().stream().filter(Station::up).toList();

			handedAt = network.now();

			if (toEveryone) {
				running.forEach(station -> station.driver().submit(transfer).join());
			} else if (!running.isEmpty()) {
				running.get(random.nextInt(running.size())).driver().submit(transfer).join();
			}
		}

		private void scan() {
			final Ledger longest = network.stations().stream().map(Station::ledger).filter(Objects::nonNull)
				.max(Comparator.comparingLong(Ledger::height)).orElseThrow();

			for (List<Block> blocks = longest.blocksFrom(scanned + 1, BlockBatch.MAX_BLOCKS); !blocks.isEmpty();
				blocks = longest.blocksFrom(scanned + 1, BlockBatch.MAX_BLOCKS)) {
				blocks.forEach(block -> block.getTransactions().forEach(transaction ->
					included.add(Bytes.hex(transaction.id()))));
				scanned += blocks.size();
			}
		}

		private boolean done() {
			return finishedAt >= 0;
		}

		/* How long it took from the start of the wallet to the block that holds the last transfer */
		private long millis() {
			return finishedAt - beganAt;
		}
	}

	/* Three peers send as much as a hundred honest ones would, each of a kind of its own, so that a limit that a peer
	   runs into is its own. The node does the work that the first tokens pay for and no more, the three are ignored
	   for the whole penalty and let in again after it, and the chain goes on at its pace throughout. */
	@Example
	public void shouldNotLetAFloodingPeerStarveTheNode() throws IOException {
		final AtomicInteger worked = new AtomicInteger();
		final AtomicInteger served = new AtomicInteger();
		final LedgerAuthority counting = TestAuthority.open().withTransfers(transfer -> {
			if (transfer.nonce() >= HOSTILE_NONCE) {
				worked.incrementAndGet();
			}

			return true;
		});

		try (MemoryNetwork network = started(6, VALIDATORS, counting)) {
			final Station victim = network.station(2);
			final Visitor spammer = new Visitor("T");
			final Visitor chatterer = new Visitor("C");
			final Visitor asker = new Visitor("R");
			final Set<Visitor> hostile = Set.of(spammer, chatterer, asker);
			final Payer payer = new Payer(network);
			final List<Transfer> farAhead = IntStream.range(0, 100)
				.mapToObj(n -> Transfer.signed(accountSeed(1), account(2), 1, 0, HOSTILE_NONCE + n,
					network.chainId())).toList();
			final Ballot chatter = Ballot.signed(Ballot.Type.PREVOTE, seed(2), network.chainId(), 50, 0, null);
			final Rate transactions = Policy.DEFAULT.rates().get(Kind.TRANSACTION);
			final Rate requests = Policy.DEFAULT.rates().get(Kind.BLOCK_REQUEST);
			final long floodStart = network.now();

			hostile.forEach(victim::attach);
			hammer(network, Kind.TRANSACTION, FLOOD_MILLIS,
				n -> victim.driver().onTransaction(spammer, farAhead.get(n % farAhead.size()), DriverRig.FRAME));
			hammer(network, Kind.CONSENSUS, FLOOD_MILLIS, n -> victim.driver().onMessage(chatterer, chatter, DriverRig.FRAME));
			hammer(network, Kind.BLOCK_REQUEST, FLOOD_MILLIS, n -> answered(victim, asker, served));

			for (int n = 0; n < 3; n++) {
				network.run(floodStart + SECOND / 5 + n * 3 * SECOND / 10);

				final long expected = victim.ledger().height() + 1;
				final Station origin = network.station(n % 2 * 2);

				assertThat(origin.driver().submit(payer.pay(1, 2, 1)).join(), equalTo(Optional.empty()));
				assertThat("Block " + expected, network.runUntil(() -> everyHeightIs(network, expected),
					network.now() + 2 * SECOND), is(true));
			}

			network.run(floodStart + FLOOD_MILLIS);

			assertThat(worked.get(), greaterThanOrEqualTo(transactions.capacity()));
			assertThat(worked.get(), lessThanOrEqualTo(allowedIn(transactions)));
			assertThat(served.get(), greaterThanOrEqualTo(requests.capacity()));
			assertThat(served.get(), lessThanOrEqualTo(allowedIn(requests)));
			assertThat(hostile.stream().allMatch(peer -> victim.limiter().isPenalised(peer.id())), is(true));
			assertThat(network.stations().stream().noneMatch(station -> victim.limiter()
				.isPenalised(station.id())), is(true));

			final int workedBefore = worked.get();
			final int servedBefore = served.get();

			network.run(floodStart + 30 * SECOND);
			hammer(network, Kind.TRANSACTION, SECOND / 5,
				n -> victim.driver().onTransaction(spammer, farAhead.get(n % farAhead.size()), DriverRig.FRAME));
			hammer(network, Kind.BLOCK_REQUEST, SECOND / 5, n -> answered(victim, asker, served));
			network.run(floodStart + 59 * SECOND);

			assertThat(worked.get(), equalTo(workedBefore));
			assertThat(served.get(), equalTo(servedBefore));
			assertThat(hostile.stream().allMatch(peer -> victim.limiter().isPenalised(peer.id())), is(true));

			network.run(floodStart + 61 * SECOND);
			answered(victim, asker, served);

			assertThat(hostile.stream().noneMatch(peer -> victim.limiter().isPenalised(peer.id())), is(true));
			assertThat(served.get(), equalTo(servedBefore + 1));
			assertThat(victim.built(), equalTo(List.of(false, true)));
			assertSameChain(network);
		}
	}

	private static void answered(Station victim, Visitor asker, AtomicInteger served) {
		final CompletableFuture<BlockBatch> reply = victim.driver().onBlockRequest(asker, new BlockRequest(1, 16),
			DriverRig.FRAME);

		if (reply.isDone() && !reply.isCompletedExceptionally()) {
			served.incrementAndGet();
		}
	}

	/* What a peer may have had of a kind during the flood: its tokens at the start and what they were refilled by */
	private static int allowedIn(Rate rate) {
		return (int) (rate.capacity() + rate.refillPerSecond() * (FLOOD_MILLIS / SECOND));
	}

	/* One valid transaction and one valid decision, each handed to one of eight drivers, reach every driver and are
	   sent by each of them to each of its peers once at most: what comes round again is no longer new */
	@Example
	public void shouldNotAmplify() throws IOException {
		final int size = 8;

		try (MemoryNetwork network = started(7, size)) {
			final Ledger rival = network.scratch();

			commit(rival, network.station(0).ledger().blocksFrom(1, 1).get(0));
			rival.submit(mint(9, 5));

			final Decision decision = new Decision(nextBlock(rival, network.validators(), network.now()));

			network.station(0).driver().onMessage(new Visitor("X"), decision, DriverRig.FRAME);

			assertThat(network.runUntil(() -> everyHeightIs(network, 2), network.now() + TEN_SECONDS), is(true));

			final Transfer transfer = new Payer(network).pay(1, 2, 10);

			assertThat(network.station(3).driver().submit(transfer).join(), equalTo(Optional.empty()));
			assertThat(network.runUntil(() -> everyHeightIs(network, 3), network.now() + TEN_SECONDS), is(true));
			network.run(network.now() + TEN_SECONDS);

			assertSentOncePerPeer(network, decision, size);
			assertSentOncePerPeer(network, transfer, size);
			assertSameChain(network);
		}
	}
}
