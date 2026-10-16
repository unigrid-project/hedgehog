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
import java.nio.file.FileSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import org.unigrid.hedgehog.ledger.PeerLimiter.Policy;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

/* A driver over a node of the given validators, with files in memory, a clock and a scheduler that the test moves,
   and peers that record what they are sent. A node that fails is replaced by one over the same files, as the factory
   of a real node would build it. `leader` is a ledger of its own that holds a mint and makes the blocks and messages
   that peers send. */
final class DriverRig implements AutoCloseable {
	static final long START = 10_000;
	static final long TICK = 333;
	static final long HEARTBEAT = 4_000;
	static final long STATUS = 6_000;
	static final long RESTART_WINDOW = 10_000;
	static final int QUEUE = 1_000;
	static final int PER_PEER = 100;
	static final long GRACE = 7_777;

	/* What the driver is given to order the peers that claim to be ahead, so that a test asks them in the same order each
	   time */
	static final long SALT = 17;
	static final DriverOptions OPTIONS = new DriverOptions(TICK, HEARTBEAT, STATUS, GRACE, RESTART_WINDOW, QUEUE,
		PER_PEER, Optional.empty());

	/* A score of 2 makes a penalty: a message that costs 2 is punished at once, one that costs 1 the second time */
	static final Policy STRICT = new Policy(Policy.DEFAULT.rates(), Policy.DEFAULT.global(), 2, 10, 60_000, 100);
	static final List<byte[]> SOLO = seeds(1, 1);
	static final List<byte[]> FOUR = seeds(1, 4);

	/* Answers as the open authority does until it is broken: then every question throws, or, if only its schedule is
	   broken, the question which mints are due, which the ledger asks outside the checks of what peers send */
	private static final class Authority implements LedgerAuthority {
		private volatile boolean broken;
		private volatile boolean scheduleBroken;

		private LedgerAuthority answering(boolean schedule) {
			if (broken || schedule && scheduleBroken) {
				throw new IllegalStateException("The authority failed");
			}

			return ANY_MINT;
		}

		@Override
		public List<Mint> due(long time) {
			return answering(true).due(time);
		}

		@Override
		public boolean authorizes(Mint mint, long time) {
			return answering(false).authorizes(mint, time);
		}

		@Override
		public boolean authorizes(Claim claim) {
			return answering(false).authorizes(claim);
		}

		@Override
		public boolean authorizes(Transfer transfer) {
			return answering(false).authorizes(transfer);
		}
	}

	final FileSystem fileSystem = newFileSystem();
	final AtomicLong clock;
	final ManualScheduler scheduler;
	final RecordingNetwork network = new RecordingNetwork();
	final PeerLimiter limiter;
	final LedgerDriver driver;
	final List<Boolean> built = new CopyOnWriteArrayList<>();
	final List<LedgerNode> nodes = new CopyOnWriteArrayList<>();
	final Set<String> builders = ConcurrentHashMap.newKeySet();
	private final List<Authority> authorities = new ArrayList<>();
	private final List<FlakyStore> stores = new ArrayList<>();
	private final List<byte[]> validators;
	private final byte[] seed;
	private final DriverOptions options;
	private ManualScheduler.Timer ticker;
	private BlockLog log;
	private Ledger ledger;
	private Ledger leader;
	private Block proposed;
	private IOException createFailure;
	private IOException votersFailure;
	private Boolean votes;
	private boolean newNodesBroken;
	private boolean votersBroken;

	DriverRig(List<byte[]> validators, byte[] seed, Policy policy, Serial serial) {
		this(validators, seed, policy, serial, OPTIONS);
	}

	/* The waits of a sync that a test is not about run at once, so that a sync is over when the call that started it
	   returns */
	DriverRig(List<byte[]> validators, byte[] seed, Policy policy, Serial serial, DriverOptions options) {
		this(validators, seed, policy, serial, options, new AtomicLong(START), new ManualScheduler());
		scheduler.runAtOnce(Syncer.PACE_MILLIS, Syncer.RETRY_MILLIS);
	}

	/* A rig that shares its time with others, whose scheduler keeps every wait until the time passes */
	@SuppressWarnings("checkstyle:ParameterNumber")
	DriverRig(List<byte[]> validators, byte[] seed, Policy policy, Serial serial, DriverOptions options,
		AtomicLong clock, ManualScheduler scheduler) {
		this.validators = validators;
		this.seed = seed;
		this.options = options;
		this.clock = clock;
		this.scheduler = scheduler;
		this.limiter = new PeerLimiter(clock::get, policy);
		this.driver = new LedgerDriver(this::create, serial, scheduler, network, limiter, clock::get, options, seed,
			() -> SALT);
	}

	static DriverRig solo() {
		return solo(STRICT);
	}

	static DriverRig solo(Policy policy) {
		return new DriverRig(SOLO, seed(1), policy, new DirectSerial()).started().promoted();
	}

	/* A validator of four, which is not the proposer of the first height, so it has nothing to say before it hears */
	static DriverRig four() {
		return four(STRICT);
	}

	static DriverRig four(Policy policy) {
		return four(2, policy);
	}

	static DriverRig four(int validator, Policy policy) {
		return new DriverRig(FOUR, seed(validator), policy, new DirectSerial()).started().promoted();
	}

	static DriverRig observer() {
		return new DriverRig(FOUR, null, STRICT, new DirectSerial()).started();
	}

	DriverRig started() {
		driver.start();
		return this;
	}

	private LedgerNode create(boolean signing) throws IOException {
		builders.add(Thread.currentThread().getName());
		built.add(signing);

		if (createFailure != null || signing && votersFailure != null) {
			throw createFailure != null ? createFailure : votersFailure;
		}

		if (log != null) {
			log.close();
		}

		final Authority authority = new Authority();

		authority.broken = newNodesBroken || signing && votersBroken;
		authorities.add(authority);
		log = BlockLog.open(fileSystem.getPath("/node/blocks.log"));
		ledger = new Ledger(state(1000, authority, validators), log, clock::get);
		stores.add(new FlakyStore(StrictLocks.signStateOf(fileSystem.getPath("/node/sign.state"))));
		nodes.add(new LedgerNode(ledger, (votes != null ? votes : signing) ? seed : null, Timeouts.DEFAULT,
			stores.get(stores.size() - 1), clock::get));
		return nodes.get(nodes.size() - 1);
	}

	/* From now on every node is built to vote, or not to, whatever the driver asks for: a factory that does not do
	   what it is asked */
	void buildAlways(boolean vote) {
		votes = vote;
	}

	Ledger ledger() {
		return ledger;
	}

	/* The newest node starts failing on whatever it is asked next that reaches its ledger */
	void breakNode() {
		authorities.get(authorities.size() - 1).broken = true;
	}

	/* The newest node's schedule of due mints fails: not what checks a transaction or a block, but what looks for
	   work */
	void breakSchedule() {
		authorities.get(authorities.size() - 1).scheduleBroken = true;
	}

	/* The newest node cannot save what it signs, so the next event that signs something fails */
	void failSaves() {
		failSaves(new IOException("The disk is full"));
	}

	void failSaves(Throwable failure) {
		stores.get(stores.size() - 1).failWith(failure);
	}

	/* Every node built from now on fails on its first question, so it cannot even start */
	void breakNewNodes() {
		newNodesBroken = true;
	}

	void failCreation(IOException failure) {
		createFailure = failure;
	}

	/* A node to follow can be built, and a node to vote cannot, as when the key is in use elsewhere */
	void failCreationOfVoters(IOException failure) {
		votersFailure = failure;
	}

	/* A node to vote can be built, and cannot start */
	void breakVoters() {
		votersBroken = true;
	}

	void mendVoters() {
		votersFailure = null;
		votersBroken = false;
	}

	/* Moves the clock and lets the tick that waits come */
	void tick(long advanceMillis) {
		final int before = scheduler.scheduled().size();

		clock.addAndGet(advanceMillis);
		ticker().fire();

		if (scheduler.scheduled().size() > before) {
			ticker = scheduler.scheduled().get(before);
		}
	}

	/* A status from a peer that is connected, as the application only passes on what a connection says */
	void hears(LedgerPeer peer, Status status) {
		if (!network.has(peer.id())) {
			network.add(peer);
		}

		driver.onStatus(peer, status);
	}

	/* The grace period of a driver ends: the timer that was set for it fires */
	void passGrace() {
		scheduler.pending().stream().filter(timer -> timer.delayMillis() == options.graceMillis())
			.forEach(ManualScheduler.Timer::fire);
	}

	/* A driver with a key starts as an observer and, with no peer to wait for, takes the key into use when its grace
	   period ends. The observer is then forgotten, so that the nodes the rig lists are those of the validator and of
	   what replaces it. For a serial executor that runs a task at once. */
	DriverRig promoted() {
		passGrace();
		built.remove(0);
		nodes.remove(0);
		authorities.remove(0);
		stores.remove(0);
		return this;
	}

	/* The tick that waits: the first timer the driver asked for, and after it the first that each tick asks for */
	ManualScheduler.Timer ticker() {
		if (ticker == null) {
			ticker = scheduler.scheduled().get(0);
		}

		return ticker;
	}

	/* What the driver waits on besides the tick: the timers the engine asked for, and a node that is to be rebuilt */
	List<ManualScheduler.Timer> timers() {
		return scheduler.pending().stream().filter(timer -> timer != ticker()).toList();
	}

	private Ledger leader() throws IOException {
		if (leader == null) {
			final BlockLog leaderLog = BlockLog.open(fileSystem.getPath("/leader/blocks.log"));

			leader = new Ledger(state(1000, ANY_MINT, validators), leaderLog, clock::get);
			leader.submit(mint(1, 500));
		}

		return leader;
	}

	byte[] chainId() throws IOException {
		return leader().chainId();
	}

	/* The block of the first height, as its proposer makes it */
	Block proposed() throws IOException {
		if (proposed == null) {
			proposed = LedgerFixtures.proposed(leader(), START);
		}

		return proposed;
	}

	/* The same block with the precommits of a quorum, which is what a decision carries */
	Block certified() throws IOException {
		return nextBlock(leader(), validators, START);
	}

	Block thinlyCertified() throws IOException {
		return nextBlock(leader(), validators, 2, START);
	}

	Proposal proposal() throws IOException {
		return Proposal.signed(seed(1), chainId(), 1, 0, -1, proposed());
	}

	Ballot ballot(Ballot.Type type, int validator) throws IOException {
		return Ballot.signed(type, seed(validator), chainId(), 1, 0, proposed().hash());
	}

	@Override
	public void close() throws IOException {
		driver.close();

		if (log != null) {
			log.close();
		}

		fileSystem.close();
	}
}
