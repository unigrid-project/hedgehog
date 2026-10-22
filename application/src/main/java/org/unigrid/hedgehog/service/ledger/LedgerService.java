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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.command.option.LedgerOptions;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Address;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.Ledger;
import org.unigrid.hedgehog.ledger.LedgerAuthority;
import org.unigrid.hedgehog.ledger.LedgerDriver;
import org.unigrid.hedgehog.ledger.LedgerDriver.Checkpoint;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import org.unigrid.hedgehog.ledger.LedgerGenesis;
import org.unigrid.hedgehog.ledger.LedgerState;
import org.unigrid.hedgehog.ledger.PeerLimiter;
import org.unigrid.hedgehog.ledger.PeerLimiter.Policy;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.model.bootstrap.BootstrapSnapshot;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;

/* The ledger as a part of the daemon, when the daemon is told to run one (--ledger). The daemon is whole without it:
   whatever stops the ledger from starting, a genesis that cannot be read, a snapshot that is not the one the genesis
   commits to, a key file that others can read, is logged with the name of the file and leaves the node running as it
   was, and the ledger inactive (status()). A seed is never logged.

   The service is built on the thread of the container, which only reads the genesis, and does not wait for anything:
   the bootstrap snapshot may still be on its way, so a timer checks for it, and the ledger starts only once it is
   there and is the one of the genesis, as the ledger replays its block log through the snapshot and a validator
   must never judge a claim without it. The check runs on the timer thread, the replay of the log on the thread
   of the events of the driver, and the container thread is back by then. The threads belong to the thread that built
   the service (LedgerRuntime). Whoever reads the ledger, from any thread, reads the newest one the driver has built
   and gets nothing before there is one. */
@Slf4j
@Eager
@ApplicationScoped
public class LedgerService {
	private static final String THREADS = "ledger";
	private static final String LEDGER_DIRECTORY = "ledger";
	private static final int CHAIN_DIRECTORY_CHARACTERS = 16;
	private static final List<Type> POLICY = List.of(Type.MINT_STORAGE, Type.MINT_SUPPLY, Type.LEDGER_BLACKLIST,
		Type.LEDGER_FEE);
	private static final HexFormat HEX = HexFormat.of();
	private static final long POLL_MILLIS = 5_000;
	private static final long REPORT_MILLIS = 60_000;
	private static final long STOP_MILLIS = 10_000;
	private static final long TICK_MILLIS = 1_000;
	private static final long GRACE_MILLIS = 30_000;
	private static final int TRANSACTIONS_PER_SECOND = 50;
	private static final int MAX_RESTARTS = 3;
	private static final long RESTART_POLLS = 12;
	private static final long RESTART_WINDOW_MILLIS = 6 * 60 * 60 * 1_000;

	/* Where the service finds its files and how it runs, read from the options of the daemon by setup(), which is
	   what a test of it replaces. `pollMillis` is how often it looks for the snapshot, and for a halt of the driver. */
	record Setup(Path directory, Optional<Path> keyFile, Optional<Path> genesisFile, LongSupplier clock,
		DriverOptions options, long pollMillis) {
	}

	/* What an operator wants to know of the ledger of the node. Before there is a ledger the height is 0 and the tip
	   hash empty; the reason is empty unless the driver has halted. A driver that has halted serves the blocks it has,
	   the node goes on, and the ledger starts again a few times before it stays halted. While the ledger runs,
	   `unsetSporks` and `laggingSporks` name the sporks that it depends on and that are not set here, or of which a
	   newer version is known and is not here yet. Nothing that depends on such a spork is minted, claimed or paid for,
	   which is why a chain on a running node may stand still. */
	public record Report(boolean running, boolean halted, String haltReason, boolean signing, long height,
		String tipHash, long droppedInbound, List<Type> unsetSporks, List<Type> laggingSporks) {
	}

	@Inject private LedgerGateway gateway;
	@Inject private BootstrapSnapshot snapshot;
	@Inject private SporkDatabase sporks;
	@Inject private SporkLag lag;
	@Inject private ApplicationDirectory directory;

	private volatile LedgerRuntime runtime;
	private volatile LedgerNodes nodes;
	private volatile LedgerDriver driver;
	private volatile boolean stopped;
	private volatile Optional<AccountKey> key = Optional.empty();
	private final Deque<Long> restarts = new ArrayDeque<>();
	private LongSupplier clock;
	private Runnable reattach;
	private long pollMillis;
	private long waits;

	public LedgerService() {
		/* For the container, which fills in what is injected */
	}

	LedgerService(final LedgerGateway gateway, final BootstrapSnapshot snapshot, final SporkDatabase sporks,
		final SporkLag lag, final ApplicationDirectory directory) {

		this.gateway = gateway;
		this.snapshot = snapshot;
		this.sporks = sporks;
		this.lag = lag;
		this.directory = directory;
	}

	/* For the tests of this package: the threads of the ledger, which are null until the service has begun */
	LedgerRuntime runtime() {
		return runtime;
	}

	@PostConstruct
	void start() {
		if (LedgerOptions.isLedger()) {
			begin(this::setup);
		}
	}

	Setup setup() {
		return new Setup(directory.getUserDataDir().resolve(LEDGER_DIRECTORY),
			Optional.ofNullable(LedgerOptions.getLedgerKeyFile()),
			Optional.ofNullable(LedgerOptions.getLedgerGenesisFile()), System::currentTimeMillis,
			driverOptions(LedgerSetup.trusted()), POLL_MILLIS);
	}

	/* A sign state says what a validator has signed on one chain and a block log holds that chain: the files of
	   another chain, which a changed genesis makes, must never meet them. Each chain has a directory of its own below
	   the one of the ledger, named by the start of its chain id; those of chains that are left stay as they were. */
	static String chainDirectoryOf(final LedgerGenesis genesis) {
		return GenesisFile.chainIdHex(genesis).substring(0, CHAIN_DIRECTORY_CHARACTERS);
	}

	private static DriverOptions driverOptions(final Optional<Checkpoint> trusted) {
		final DriverOptions standard = DriverOptions.DEFAULT;

		return new DriverOptions(TICK_MILLIS, standard.heartbeatMillis(), standard.statusMillis(), GRACE_MILLIS,
			standard.restartWindowMillis(), standard.maxQueuedInbound(), standard.maxQueuedPerPeer(), trusted);
	}

	void begin(final Supplier<Setup> wanted) {
		attempt(() -> launch(wanted.get()));
	}

	private void launch(final Setup setup) {
		final LedgerGenesis genesis = GenesisFile.load(setup.genesisFile()).orElseThrow(() ->
			new IllegalArgumentException("The node has no ledger genesis of its own and none is given with "
				+ "--ledger-genesis"));

		log.atInfo().log("The ledger is of the chain {}", GenesisFile.chainIdHex(genesis));
		pollMillis = setup.pollMillis();
		runtime = new LedgerRuntime(THREADS);
		later(0, () -> poll(setup, genesis));
	}

	private void poll(final Setup setup, final LedgerGenesis genesis) {
		attempt(() -> {
			if (!stopped) {
				snapshot.getReader().ifPresentOrElse(reader -> verified(setup, genesis, reader, true),
					() -> waiting(setup, genesis));
			}
		});
	}

	private void waiting(final Setup setup, final LedgerGenesis genesis) {
		if (waits++ % Math.max(1, REPORT_MILLIS / pollMillis) == 0) {
			log.atInfo().addArgument(snapshot.getPath())
				.log("Waiting for the bootstrap snapshot {} before the ledger starts");
		}

		later(pollMillis, () -> poll(setup, genesis));
	}

	/* The hash is made of the file at the path and the total of the file the reader maps, which prove one snapshot
	   only if they are one file: the check counts only if the snapshot still hands out the reader it began with,
	   else it is made once more on the new one, and a snapshot that has changed again is not trusted */
	private void verified(final Setup setup, final LedgerGenesis genesis, final SnapshotReader reader,
		final boolean mayRepeat) {

		final Optional<String> mismatch = SnapshotCheck.verify(snapshot.getPath(), reader, genesis);

		if (snapshot.getReader().filter(current -> current == reader).isEmpty()) {
			repeated(setup, genesis, mayRepeat);
		} else if (mismatch.isPresent()) {
			refuse(mismatch.get());
		} else {
			attach(setup, genesis, reader);
		}
	}

	private void repeated(final Setup setup, final LedgerGenesis genesis, final boolean mayRepeat) {
		if (mayRepeat) {
			snapshot.getReader().ifPresentOrElse(reader -> verified(setup, genesis, reader, false),
				() -> waiting(setup, genesis));
		} else {
			refuse("The snapshot " + snapshot.getPath() + " changed while it was checked, and again when it was "
				+ "checked once more");
		}
	}

	/* The driver is attached to the gateway and started under the lock, so that a stop either comes before it, and
	   nothing starts, or after it, and closes it */
	private synchronized void attach(final Setup setup, final LedgerGenesis genesis, final SnapshotReader reader) {
		if (stopped) {
			return;
		}

		final byte[] seed = setup.keyFile().map(LedgerKeyFile::load).orElse(null);

		key = Optional.ofNullable(seed).map(Ed25519::publicKey);
		clock = setup.clock();
		reattach = () -> attach(setup, genesis, reader);

		final LedgerSnapshot ledgerSnapshot = new LedgerSnapshot(reader);
		final LedgerAuthority authority = new SporkLedgerAuthority(() -> sporks, lag,
			ledgerSnapshot.mintSchedule(), this::committedSupply, this::committedMinted);
		final Policy policy = Policy.forNetwork(genesis.getValidators().size(), TRANSACTIONS_PER_SECOND);

		final Path files = setup.directory().resolve(chainDirectoryOf(genesis));

		log.atInfo().addArgument(files).log("The ledger keeps its block log and its sign state in {}");
		nodes = new LedgerNodes(files, () -> new LedgerState(genesis, authority, ledgerSnapshot), seed,
			setup.clock());
		driver = new LedgerDriver(nodes, runtime, runtime, gateway.network(),
			new PeerLimiter(setup.clock(), policy), setup.clock(), setup.options(), seed);
		gateway.attach(driver);
		driver.start();
		later(pollMillis, this::watch);
	}

	private long committedSupply() {
		return supply().orElse(0);
	}

	private long committedMinted(final Address recipient) {
		return readLong(held -> held.mintedTo(recipient)).orElse(0);
	}

	/* The halt is the driver's to log as it happens; this is the service saying what becomes of the ledger, however
	   long after */
	private void watch() {
		attempt(() -> {
			if (driver.halted()) {
				halted(driver.haltReason().orElse(""));
			} else {
				later(pollMillis, this::watch);
			}
		});
	}

	/* What halted a driver may be gone a while later, a peer that sent something the node could not take or a disk
	   that was full, so the ledger starts again over its files, each time after twice the wait of the time before.
	   A ledger that halts again and again in a few hours has something wrong that a start does not mend: it stays
	   halted, serves the blocks it has, and the operator is told. */
	private void halted(final String reason) {
		final long now = clock.getAsLong();

		restarts.removeIf(time -> now - time > RESTART_WINDOW_MILLIS);

		if (restarts.size() >= MAX_RESTARTS) {
			log.atError().addArgument(restarts.size()).addArgument(reason)
				.log("The ledger halted after {} starts again, it stays halted and serves the blocks it "
					+ "has, the node goes on: {}");
			return;
		}

		final long delayMillis = pollMillis * RESTART_POLLS << restarts.size();

		restarts.add(now);
		log.atError().addArgument(delayMillis).addArgument(reason)
			.log("The ledger halted, it starts again in {} ms: {}");
		later(delayMillis, this::restart);
	}

	private synchronized void restart() {
		attempt(() -> {
			if (!stopped) {
				closeDriver(driver);
				nodes.close();
				reattach.run();
			}
		});
	}

	/* A task of a timer that throws is lost without a word, so nothing is let out of one */
	private void attempt(final Runnable work) {
		try {
			work.run();
		} catch (Throwable ex) {
			log.atDebug().setCause(ex).log("The ledger failed");
			refuse(reasonOf(ex));
		}
	}

	private static String reasonOf(final Throwable cause) {
		return cause instanceof IllegalArgumentException ? cause.getMessage() : String.valueOf(cause);
	}

	/* Also for the tests of this package: the service gives up as it does on a failure of its timer thread */
	void refuse(final String reason) {
		if (!stopped) {
			log.atError().log("The ledger is not running, the node goes on without it: {}", reason);
			release(false);
		}
	}

	private void later(final long delayMillis, final Runnable task) {
		try {
			runtime.schedule(task, delayMillis);
		} catch (RejectedExecutionException ex) {
			log.atDebug().log("The ledger stopped before its next check");
		}
	}

	@PreDestroy
	void stop() {
		release(true);
	}

	/* The gateway lets go of the driver first, so that nothing new reaches a driver that is closing; then the driver
	   closes its node and the files are free; then the threads end, and last the block log. A thread of the runtime
	   cannot wait for itself to end, so one that stops the service from there does not wait. */
	private synchronized void release(final boolean awaitThreads) {
		if (stopped) {
			return;
		}

		stopped = true;
		Optional.ofNullable(driver).ifPresent(this::closeDriver);
		Optional.ofNullable(runtime).ifPresent(held -> closeRuntime(held, awaitThreads));
		Optional.ofNullable(nodes).ifPresent(LedgerNodes::close);
	}

	private void closeDriver(final LedgerDriver closing) {
		gateway.detach();
		closing.close();

		try {
			closing.closed().get(STOP_MILLIS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException ex) {
			log.atWarn().log("The ledger did not close within {} ms", STOP_MILLIS);
		} catch (ExecutionException ex) {
			log.atWarn().setCause(ex).log("The ledger closed with a failure");
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void closeRuntime(final LedgerRuntime closing, final boolean awaitThreads) {
		closing.close();

		if (awaitThreads && !closing.awaitStopped(STOP_MILLIS)) {
			log.atWarn().log("The threads of the ledger did not end within {} ms", STOP_MILLIS);
		}
	}

	/* The answer of the ledger as it gives it: empty when the transaction went in, else why not */
	public CompletableFuture<Optional<String>> submit(final Transaction transaction) {
		final LedgerDriver started = driver;

		try {
			return started == null || stopped ? refused() : started.submit(transaction);
		} catch (RejectedExecutionException ex) {
			return refused();
		}
	}

	private static CompletableFuture<Optional<String>> refused() {
		return CompletableFuture.completedFuture(Optional.of(LedgerDriver.NOT_RUNNING));
	}

	public Report status() {
		final Optional<LedgerDriver> started = Optional.ofNullable(driver);
		final boolean halted = started.map(LedgerDriver::halted).orElse(false);
		final boolean running = started.isPresent() && !stopped && !halted;

		return new Report(running, halted, started.flatMap(LedgerDriver::haltReason).orElse(""),
			running && signs(), height().orElse(0), tipHash().map(HEX::formatHex).orElse(""),
			started.map(LedgerDriver::droppedInbound).orElse(0L), sporksThat(running, this::isUnset),
			sporksThat(running, type -> lag.isBehind(type, sporks)));
	}

	/* A node signs when it holds a key and the key is one of the validators now, of the genesis or voted in since:
	   another key gives a node that follows the chain and is heard by no one */
	private boolean signs() {
		return nodes.signing() && key.flatMap(held -> nodes.ledger()
			.map(ledger -> ledger.validators().contains(held))).orElse(false);
	}

	private boolean isUnset(final Type type) {
		return Objects.isNull(sporks.get(type));
	}

	/* Of the sporks that decide what the node mints, takes and demands: while one is unset, or a newer one is known and
	   not yet here, the node answers no and nothing is made of what depends on it */
	private static List<Type> sporksThat(final boolean running, final Predicate<Type> test) {
		return running ? POLICY.stream().filter(test).toList() : List.of();
	}

	/* Empty until the driver has built its first ledger, and the newest ledger after, also once the node is
	   halted or stopped */
	public OptionalLong height() {
		return readLong(Ledger::height);
	}

	public OptionalLong supply() {
		return readLong(Ledger::supply);
	}

	public OptionalLong balanceOf(final Address address) {
		return readLong(held -> held.balanceOf(address));
	}

	public Optional<byte[]> tipHash() {
		return read(Ledger::tipHash);
	}

	/* For reads only: the function is applied to the newest ledger on the thread of the caller, which is safe for
	   what the ledger gives out to any thread, and empty while there is none. A transaction goes through submit,
	   which hands it to the thread of the driver and gossips it; one put into the ledger from here would do neither. */
	public <T> Optional<T> read(final Function<Ledger, T> reading) {
		return Optional.ofNullable(nodes).flatMap(LedgerNodes::ledger).map(reading);
	}

	private OptionalLong readLong(final ToLongFunction<Ledger> reading) {
		return read(reading::applyAsLong).map(OptionalLong::of).orElseGet(OptionalLong::empty);
	}
}
