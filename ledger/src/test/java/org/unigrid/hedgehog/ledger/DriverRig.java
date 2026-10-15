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
	static final long TICK = 250;
	static final long HEARTBEAT = 4_000;
	static final long STATUS = 6_000;
	static final long RESTART_WINDOW = 10_000;
	static final DriverOptions OPTIONS = new DriverOptions(TICK, HEARTBEAT, STATUS, 30_000, RESTART_WINDOW,
		Optional.empty());

	/* A score of 2 makes a penalty: a message that costs 2 is punished at once, one that costs 1 the second time */
	static final Policy STRICT = new Policy(Policy.DEFAULT.rates(), 2, 60_000, 100);
	static final List<byte[]> SOLO = seeds(1, 1);
	static final List<byte[]> FOUR = seeds(1, 4);

	private static final class Authority implements LedgerAuthority {
		private volatile boolean broken;

		private LedgerAuthority answering() {
			if (broken) {
				throw new IllegalStateException("The authority failed");
			}

			return ANY_MINT;
		}

		@Override
		public List<Mint> due(long time) {
			return answering().due(time);
		}

		@Override
		public boolean authorizes(Mint mint, long time) {
			return answering().authorizes(mint, time);
		}

		@Override
		public boolean authorizes(Claim claim) {
			return answering().authorizes(claim);
		}

		@Override
		public boolean authorizes(Transfer transfer) {
			return answering().authorizes(transfer);
		}
	}

	private static final class FlakyStore implements SignStore {
		private final SignStore store;
		private volatile boolean failing;

		private FlakyStore(SignStore store) {
			this.store = store;
		}

		@Override
		public Optional<SignState> load() throws IOException {
			return store.load();
		}

		@Override
		public void save(SignState state, int largestProof) throws IOException {
			if (failing) {
				throw new IOException("The disk is full");
			}

			store.save(state, largestProof);
		}

		@Override
		public void close() throws IOException {
			store.close();
		}
	}

	final FileSystem fileSystem = newFileSystem();
	final AtomicLong clock = new AtomicLong(START);
	final ManualScheduler scheduler = new ManualScheduler();
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
	private BlockLog log;
	private Ledger ledger;
	private Ledger leader;
	private Block proposed;
	private IOException createFailure;
	private boolean newNodesBroken;

	DriverRig(List<byte[]> validators, byte[] seed, Policy policy, Serial serial) {
		this.validators = validators;
		this.seed = seed;
		this.limiter = new PeerLimiter(clock::get, policy);
		this.driver = new LedgerDriver(this::create, serial, scheduler, network, limiter, clock::get, OPTIONS, seed);
	}

	static DriverRig solo() {
		return solo(STRICT);
	}

	static DriverRig solo(Policy policy) {
		return new DriverRig(SOLO, seed(1), policy, new DirectSerial()).started();
	}

	/* A validator of four, which is not the proposer of the first height, so it has nothing to say before it hears */
	static DriverRig four() {
		return new DriverRig(FOUR, seed(2), STRICT, new DirectSerial()).started();
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

		if (createFailure != null) {
			throw createFailure;
		}

		if (log != null) {
			log.close();
		}

		final Authority authority = new Authority();

		authority.broken = newNodesBroken;
		authorities.add(authority);
		log = BlockLog.open(fileSystem.getPath("/node/blocks.log"));
		ledger = new Ledger(state(1000, authority, validators), log, clock::get);
		stores.add(new FlakyStore(StrictLocks.signStateOf(fileSystem.getPath("/node/sign.state"))));
		nodes.add(new LedgerNode(ledger, signing ? seed : null, Timeouts.DEFAULT, stores.get(stores.size() - 1),
			clock::get));
		return nodes.get(nodes.size() - 1);
	}

	Ledger ledger() {
		return ledger;
	}

	/* The newest node starts failing on whatever it is asked next that reaches its ledger */
	void breakNode() {
		authorities.get(authorities.size() - 1).broken = true;
	}

	/* The newest node cannot save what it signs, so the next event that signs something fails */
	void failSaves() {
		stores.get(stores.size() - 1).failing = true;
	}

	/* Every node built from now on fails on its first question, so it cannot even start */
	void breakNewNodes() {
		newNodesBroken = true;
	}

	void failCreation(IOException failure) {
		createFailure = failure;
	}

	/* Moves the clock and lets the tick that waits come */
	void tick(long advanceMillis) {
		clock.addAndGet(advanceMillis);
		scheduler.pending().stream().filter(timer -> timer.delayMillis() == TICK).findFirst().orElseThrow().fire();
	}

	/* What the engine has asked to be woken for, which is every pending timer but the tick */
	List<ManualScheduler.Timer> timers() {
		return scheduler.pending().stream().filter(timer -> timer.delayMillis() != TICK).toList();
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
