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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.PropagationMode;
import org.awaitility.Awaitility;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.ledger.Block;
import org.unigrid.hedgehog.ledger.BlockLog;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.LedgerDriver;
import org.unigrid.hedgehog.ledger.LedgerDriver.Checkpoint;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import org.unigrid.hedgehog.ledger.LedgerGenesis;
import org.unigrid.hedgehog.ledger.LedgerNetwork;
import org.unigrid.hedgehog.ledger.SignState;
import org.unigrid.hedgehog.ledger.SignStateFile;
import org.unigrid.hedgehog.ledger.ValidatorSet;
import org.unigrid.hedgehog.ledger.Vote;
import org.unigrid.hedgehog.model.bootstrap.AddressSnapshots;
import org.unigrid.hedgehog.model.bootstrap.BootstrapSnapshot;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDigest;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;
import org.unigrid.hedgehog.service.ledger.LedgerService.Report;
import org.unigrid.hedgehog.service.ledger.LedgerService.Setup;
import picocli.CommandLine;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerServiceTest {
	private static final List<String> DAEMON = List.of("daemon");
	private static final byte[] SEED = LedgerTestChain.seed(7);
	private static final String SEED_HEX = HexFormat.of().formatHex(SEED);
	private static final Duration PATIENCE = Duration.ofSeconds(30);
	private static final long POLL = 50;
	private static final String DIRECTORY = "/data/ledger";
	private static final String EVENTS = "ledger-events";
	private static final String TIMERS = "ledger-timers";
	private static final Report INACTIVE = new Report(false, false, "", false, 0, "", 0);

	/* A tick every 100 ms and a grace period of 300 ms, so that a node with a key votes within moments */
	private static final DriverOptions FAST = new DriverOptions(100, 1_000, 1_000, 300, 10_000, 10_000, 100,
		Optional.empty());

	private static final InheritableThreadLocal<String> CONTEXT = new InheritableThreadLocal<>();

	/* The doubles are made once, as a fake that is made again over the one before it breaks the invocations it
	   passes on, and each example gives them its rig */
	private static volatile Rig current;

	@BeforeContainer
	private static void fakeWhatTheServiceTouches() {
		new MockUp<LedgerGateway>() {
			@Mock
			public LedgerNetwork network() {
				return List::of;
			}

			@Mock
			public void attach(final LedgerDriver driver) {
				current.events.add("attach");
				current.attached = driver;
			}

			@Mock
			public void detach() {
				current.events.add("detach");
			}
		};

		new MockUp<ApplicationDirectory>() {
			@Mock
			public Path getUserDataDir() {
				return current.files.getPath("/data");
			}
		};

		new MockUp<LedgerDriver>() {
			@Mock
			public void close(final Invocation invocation) {
				current.events.add("driver.close");
				invocation.proceed();
			}
		};

		new MockUp<LedgerRuntime>() {
			@Mock
			public void close(final Invocation invocation) {
				final LedgerDriver driver = current.attached;

				current.events.add("runtime.close, driver closed: " + (driver != null && driver.closed().isDone()));
				invocation.proceed();
			}
		};

		new MockUp<BootstrapSnapshot>() {
			@Mock
			public Optional<SnapshotReader> getReader(final Invocation invocation) {
				final Supplier<Optional<SnapshotReader>> scripted = current.readers;

				return scripted == null ? invocation.proceed() : scripted.get();
			}
		};

		new MockUp<BlockLog>() {
			@Mock
			public BlockLog open(final Invocation invocation, final Path file) throws IOException {
				current.builders.add(Thread.currentThread().getName());
				return invocation.proceed(file);
			}

			@Mock
			public void close(final Invocation invocation) throws IOException {
				current.events.add("block log closed");
				invocation.proceed();
			}
		};
	}

	/* A service with its files in memory and its snapshot in a real file (the reader maps it), and the doubles
	   around it that tell what it did: the gateway it attaches to, and the order in which it lets go of its parts */
	private static final class Rig implements AutoCloseable {
		private final FileSystem files = LedgerKeyFixtures.posixFileSystem();
		private final List<String> events = new CopyOnWriteArrayList<>();
		private final List<String> builders = new CopyOnWriteArrayList<>();
		private final List<LedgerService> services = new ArrayList<>();
		private final SporkDatabase sporks = SporkDatabase.builder().build();
		private final SporkLag lag = new SporkLag();
		private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		private final Path written;
		private final Path watched;
		private final BootstrapSnapshot snapshot;
		private final LedgerGenesis genesis;
		private final Path genesisFile;
		private final Path keyFile;
		private volatile LedgerDriver attached;

		/* What the snapshot answers when asked for its reader, when a test says so instead of the file */
		private volatile Supplier<Optional<SnapshotReader>> readers;

		@SneakyThrows
		Rig(final boolean snapshotThere, final int hashFlip, final long totalOffset) {
			written = AddressSnapshots.write(Map.of("11".repeat(20), 5_000L));
			watched = Files.createTempDirectory("hhg-ledger-").resolve("bootstrap.dat");
			watched.getParent().toFile().deleteOnExit();
			watched.toFile().deleteOnExit();

			final SnapshotReader reader = SnapshotReader.open(written);
			final byte[] hash = SnapshotDigest.of(written);

			hash[0] ^= (byte) hashFlip;
			genesis = new LedgerGenesis(System.currentTimeMillis() - 60_000, 1_000,
				new ValidatorSet(List.of(Ed25519.publicKey(SEED))), hash, reader.totalBalance() + totalOffset);
			genesisFile = files.getPath("/genesis/ledger-genesis.json");
			Files.createDirectories(genesisFile.getParent());
			GenesisFile.write(genesisFile, genesis);
			keyFile = LedgerKeyFixtures.writeSeed(files, SEED);
			snapshot = new BootstrapSnapshot(watched);

			if (snapshotThere) {
				install();
			}

			appender.start();
			((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(appender);
			current = this;
		}

		static Rig ready() {
			return new Rig(true, 0, 0);
		}

		@SneakyThrows
		void install() {
			Files.move(written, watched);
		}

		/* Where the files of the chain of the genesis are */
		Path chainDirectory() {
			return chainDirectory(genesis);
		}

		Path chainDirectory(final LedgerGenesis chain) {
			return files.getPath(DIRECTORY).resolve(LedgerService.chainDirectoryOf(chain));
		}

		/* Another chain, over the same snapshot: only the time of its start differs */
		LedgerGenesis otherGenesis() {
			return new LedgerGenesis(genesis.getTime() + 1, genesis.getRoundLength(), genesis.getValidators(),
				genesis.getSnapshotHash(), genesis.getSnapshotTotal());
		}

		@SneakyThrows
		Path write(final LedgerGenesis chain) {
			final Path file = files.getPath("/genesis/" + LedgerService.chainDirectoryOf(chain) + ".json");

			GenesisFile.write(file, chain);
			return file;
		}

		Setup setup(final boolean keyed, final Optional<Checkpoint> trusted) {
			return setup(keyed, trusted, genesisFile);
		}

		Setup setup(final boolean keyed, final Optional<Checkpoint> trusted, final Path genesisToUse) {
			return new Setup(files.getPath(DIRECTORY), keyed ? Optional.of(keyFile) : Optional.empty(),
				Optional.of(genesisToUse), System::currentTimeMillis,
				new DriverOptions(FAST.tickMillis(), FAST.heartbeatMillis(), FAST.statusMillis(), FAST.graceMillis(),
					FAST.restartWindowMillis(), FAST.maxQueuedInbound(), FAST.maxQueuedPerPeer(), trusted),
				POLL);
		}

		LedgerService service() {
			final LedgerService service = new LedgerService(new LedgerGateway(), snapshot, sporks, lag,
				new ApplicationDirectory("author", "name"));

			services.add(service);
			return service;
		}

		LedgerService started(final boolean keyed) {
			return started(keyed, Optional.empty());
		}

		LedgerService started(final boolean keyed, final Optional<Checkpoint> trusted) {
			final LedgerService service = service();

			service.begin(() -> setup(keyed, trusted));
			return service;
		}

		LedgerService startedOn(final LedgerGenesis chain) {
			final LedgerService service = service();
			final Path file = write(chain);

			service.begin(() -> setup(true, Optional.empty(), file));
			return service;
		}

		List<String> logged(final Level level) {
			return appender.list.stream().filter(entry -> entry.getLevel().equals(level))
				.map(ILoggingEvent::getFormattedMessage).toList();
		}

		List<String> allLogged() {
			return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		}

		Vote vote(final int candidate) {
			return Vote.signed(SEED, Ed25519.publicKey(LedgerTestChain.seed(100 + candidate)), Vote.Action.ADD, 0,
				genesis.chainId());
		}

		@Override
		@SneakyThrows
		public void close() {
			services.forEach(LedgerService::stop);
			((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
			files.close();
		}
	}

	private static void eventually(final Callable<Boolean> condition) {
		Awaitility.await().atMost(PATIENCE).until(condition);
	}

	private static List<Thread> ledgerThreads() {
		return Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.isAlive()
			&& (thread.getName().equals(EVENTS) || thread.getName().equals(TIMERS))).toList();
	}

	@SneakyThrows
	private static Optional<String> submitted(final LedgerService service, final Vote vote) {
		return service.submit(vote).get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
	}

	private static byte[] flipped(final byte[] hash) {
		final byte[] other = hash.clone();

		other[0] ^= 1;
		return other;
	}

	/* Everything is there to run a ledger, only the node was not told to */
	@Example
	public void shouldDoNothingAtAllWhileTheLedgerIsOff() {
		try (Rig rig = Rig.ready()) {
			final LedgerService service = rig.service();

			parse(rig.files, "--ledger-genesis=" + rig.genesisFile, "--ledger-key=" + rig.keyFile);
			service.start();

			assertThat(ledgerThreads(), empty());
			assertThat(rig.events, empty());
			assertThat(rig.logged(Level.ERROR), empty());
			assertThat(service.status(), equalTo(INACTIVE));
			assertThat(submitted(service, rig.vote(1)), equalTo(Optional.of("The ledger is not running")));

			service.stop();
			assertThat(rig.events, empty());
		}
	}

	@Example
	public void shouldRunWithWhatTheOptionsOfTheDaemonSay() {
		try (Rig rig = Rig.ready()) {
			final LedgerService service = rig.service();

			parse(rig.files, "--ledger", "--ledger-genesis=" + rig.genesisFile);
			service.start();
			eventually(() -> service.status().running());

			assertThat(rig.events, contains("attach"));
			assertThat(ledgerThreads(), hasSize(2));
			assertThat(rig.logged(Level.INFO).stream().anyMatch(line -> line.equals("The ledger is of the chain "
				+ GenesisFile.chainIdHex(rig.genesis))), is(true));
		}
	}

	@Example
	public void shouldReadItsSetupFromTheOptionsOfTheDaemon() {
		try (Rig rig = Rig.ready()) {
			parse(rig.files, "--ledger", "--ledger-key=/keys/ledger.key", "--ledger-genesis=/genesis/g.json",
				"--ledger-trusted=12:" + "ab".repeat(64));

			final Setup setup = rig.service().setup();

			assertThat(setup.directory(), equalTo(rig.files.getPath("/data/ledger")));
			assertThat(setup.keyFile(), equalTo(Optional.of(rig.files.getPath("/keys/ledger.key"))));
			assertThat(setup.genesisFile(), equalTo(Optional.of(rig.files.getPath("/genesis/g.json"))));
			assertThat(setup.options().trusted(), equalTo(Optional.of(new Checkpoint(12,
				HexFormat.of().parseHex("ab".repeat(64))))));
			assertThat(setup.options().graceMillis(), equalTo(30_000L));
			assertThat(setup.options().tickMillis(), equalTo(1_000L));
			assertThat(setup.pollMillis(), equalTo(5_000L));
		}
	}

	@Example
	public void shouldTakeNoKeyAndNoCheckpointWhenNoneIsGiven() {
		try (Rig rig = Rig.ready()) {
			parse(rig.files, "--ledger");

			final Setup setup = rig.service().setup();

			assertThat(setup.keyFile(), equalTo(Optional.empty()));
			assertThat(setup.genesisFile(), equalTo(Optional.empty()));
			assertThat(setup.options().trusted(), equalTo(Optional.empty()));
		}
	}

	private static void parse(final FileSystem files, final String... options) {
		new CommandLine(Hedgehog.class).registerConverter(Path.class, files::getPath).parseArgs(
			Stream.concat(DAEMON.stream(), Stream.of(options)).toArray(String[]::new));
	}

	@Example
	public void shouldFollowTheChainAsAnObserverThatTakesATransactionAndNeverSigns() {
		try (Rig rig = Rig.ready()) {
			final LedgerService service = rig.started(false);

			eventually(() -> service.status().running() && service.height().isPresent());

			assertThat(rig.events, contains("attach"));
			assertThat(service.status().signing(), is(false));
			assertThat(service.height(), equalTo(OptionalLong.of(0)));
			assertThat(service.supply(), equalTo(OptionalLong.of(5_000)));
			assertThat(submitted(service, rig.vote(1)), equalTo(Optional.empty()));
			assertThat(service.status().signing(), is(false));
		}
	}

	@Example
	public void shouldMakeABlockOfATransactionWhenItHoldsTheKeyOfTheOnlyValidator() {
		try (Rig rig = Rig.ready()) {
			final LedgerService service = rig.started(true);

			eventually(() -> service.status().signing());
			assertThat(submitted(service, rig.vote(1)), equalTo(Optional.empty()));
			eventually(() -> service.height().equals(OptionalLong.of(1)));

			final Report report = service.status();

			assertThat(report.running(), is(true));
			assertThat(report.halted(), is(false));
			assertThat(report.haltReason(), equalTo(""));
			assertThat(report.signing(), is(true));
			assertThat(report.height(), is(1L));
			assertThat(report.tipHash(), equalTo(HexFormat.of().formatHex(service.tipHash().orElseThrow())));
			assertThat(report.tipHash().length(), is(128));
			assertThat(report.droppedInbound(), is(0L));
			assertThat(service.balanceOf(Ed25519.publicKey(SEED)), equalTo(OptionalLong.of(0)));
			assertThat(rig.builders, contains(EVENTS, EVENTS));
		}
	}

	@Example
	public void shouldStartTheDriverOnlyWhenTheSnapshotAppearsAndSayOnceThatItWaits() {
		try (Rig rig = new Rig(false, 0, 0)) {
			final LedgerService service = rig.started(false);

			Awaitility.await().during(Duration.ofMillis(400)).atMost(PATIENCE)
				.until(() -> !service.status().running());

			assertThat(rig.events, empty());
			assertThat(service.status(), equalTo(INACTIVE));
			assertThat(ledgerThreads(), hasSize(2));
			assertThat(rig.logged(Level.INFO).stream().filter(line -> line.contains("Waiting for the bootstrap "
				+ "snapshot " + rig.watched)).toList(), hasSize(1));

			rig.install();
			eventually(() -> service.status().running());

			assertThat(rig.events, contains("attach"));
		}
	}

	@Example
	public void shouldStayInactiveAndNameTheSnapshotWhenItsHashIsNotTheOneOfTheGenesis() {
		try (Rig rig = new Rig(true, 1, 0)) {
			final LedgerService service = rig.started(true);

			eventually(() -> !rig.logged(Level.ERROR).isEmpty() && ledgerThreads().isEmpty());

			assertThat(rig.logged(Level.ERROR), contains(allOf(containsString("The ledger is not running"),
				containsString(rig.watched.toString()), containsString("content hash"))));
			assertThat(rig.events, not(hasItem("attach")));
			assertThat(service.status(), equalTo(INACTIVE));
		}
	}

	@Example
	public void shouldStayInactiveAndNameTheSnapshotWhenItsTotalIsNotTheOneOfTheGenesis() {
		try (Rig rig = new Rig(true, 0, 1)) {
			final LedgerService service = rig.started(true);

			eventually(() -> !rig.logged(Level.ERROR).isEmpty() && ledgerThreads().isEmpty());

			assertThat(rig.logged(Level.ERROR), contains(allOf(containsString("The ledger is not running"),
				containsString(rig.watched.toString()), containsString("adds up to"))));
			assertThat(rig.events, not(hasItem("attach")));
			assertThat(service.status(), equalTo(INACTIVE));
		}
	}

	/* The hash is of the file that is read and the total of the file that is mapped, so they have to be one file */
	@Example
	@SneakyThrows
	public void shouldCheckTheSnapshotAgainWhenItWasReplacedWhileItWasChecked() {
		try (Rig rig = Rig.ready()) {
			final SnapshotReader first = SnapshotReader.open(rig.watched);
			final SnapshotReader second = SnapshotReader.open(rig.watched);
			final AtomicInteger asked = new AtomicInteger();

			rig.readers = () -> Optional.of(asked.getAndIncrement() == 0 ? first : second);

			final LedgerService service = rig.started(false);

			eventually(() -> service.status().running());

			assertThat(rig.events, contains("attach"));
			assertThat(asked.get(), is(4));
		}
	}

	@Example
	@SneakyThrows
	public void shouldStayInactiveAndNameTheSnapshotThatKeepsChangingWhileItIsChecked() {
		try (Rig rig = Rig.ready()) {
			rig.readers = () -> Optional.of(openedAgain(rig));

			final LedgerService service = rig.started(false);

			eventually(() -> !rig.logged(Level.ERROR).isEmpty() && ledgerThreads().isEmpty());

			assertThat(rig.logged(Level.ERROR), contains(allOf(containsString("The ledger is not running"),
				containsString(rig.watched.toString()), containsString("changed while it was checked"))));
			assertThat(rig.events, not(hasItem("attach")));
			assertThat(service.status(), equalTo(INACTIVE));
		}
	}

	@SneakyThrows
	private static SnapshotReader openedAgain(final Rig rig) {
		return SnapshotReader.open(rig.watched);
	}

	@Example
	public void shouldStayInactiveAndNameTheGenesisThatIsMissing() {
		try (Rig rig = Rig.ready()) {
			final Path missing = rig.files.getPath("/genesis/absent.json");
			final LedgerService service = rig.service();

			service.begin(() -> new Setup(rig.files.getPath(DIRECTORY), Optional.empty(), Optional.of(missing),
				System::currentTimeMillis, FAST, POLL));

			assertThat(rig.logged(Level.ERROR), contains(allOf(containsString("The ledger is not running"),
				containsString(missing.toString()))));
			assertThat(ledgerThreads(), empty());
			assertThat(rig.events, empty());
			assertThat(service.status(), equalTo(INACTIVE));
		}
	}

	@Example
	@SneakyThrows
	public void shouldStayInactiveAndNameTheGenesisThatCannotBeRead() {
		try (Rig rig = Rig.ready()) {
			Files.writeString(rig.genesisFile, "{ not a genesis");

			final LedgerService service = rig.started(false);

			assertThat(rig.logged(Level.ERROR), contains(allOf(containsString("The ledger is not running"),
				containsString(rig.genesisFile.toString()))));
			assertThat(ledgerThreads(), empty());
			assertThat(service.status(), equalTo(INACTIVE));
		}
	}

	@Example
	public void shouldStayInactiveAndSayWhatIsMissingWhenThereIsNoGenesisAtAll() {
		try (Rig rig = Rig.ready()) {
			final LedgerService service = rig.service();

			service.begin(() -> new Setup(rig.files.getPath(DIRECTORY), Optional.empty(), Optional.empty(),
				System::currentTimeMillis, FAST, POLL));

			assertThat(rig.logged(Level.ERROR), contains(allOf(containsString("The ledger is not running"),
				containsString("genesis"), containsString("--ledger-genesis"))));
			assertThat(ledgerThreads(), empty());
			assertThat(service.status(), equalTo(INACTIVE));
		}
	}

	@Example
	@SneakyThrows
	public void shouldStayInactiveAndNeverPrintTheSeedWhenTheKeyFileIsOneOthersCanRead() {
		try (Rig rig = Rig.ready()) {
			Files.setPosixFilePermissions(rig.keyFile, PosixFilePermissions.fromString("rw-r--r--"));

			final LedgerService service = rig.started(true);

			eventually(() -> !rig.logged(Level.ERROR).isEmpty() && ledgerThreads().isEmpty());

			assertThat(rig.logged(Level.ERROR), contains(allOf(containsString("The ledger is not running"),
				containsString(rig.keyFile.toString()), containsString("owner only"))));
			assertThat(rig.events, not(hasItem("attach")));
			assertThat(service.status(), equalTo(INACTIVE));
			assertThat(rig.allLogged().stream().filter(line -> line.contains(SEED_HEX)).toList(), empty());
		}
	}

	@Example
	public void shouldNeverPrintTheSeedOfARunningValidator() {
		try (Rig rig = Rig.ready()) {
			final LedgerService service = rig.started(true);

			eventually(() -> service.status().signing());
			service.stop();

			assertThat(rig.allLogged(), not(empty()));
			assertThat(rig.allLogged().stream().filter(line -> line.contains(SEED_HEX)).toList(), empty());
		}
	}

	@Example
	public void shouldLetGoOfItsPartsInTheOrderOfTheShutdownAndWaitForTheDriverToClose() {
		final CountDownLatch busy = new CountDownLatch(1);

		try (Rig rig = Rig.ready()) {
			final LedgerService service = rig.started(true);

			eventually(() -> service.status().signing());
			rig.events.clear();
			service.runtime().execute(() -> awaitQuietly(busy));

			final CompletableFuture<Void> stopping = CompletableFuture.runAsync(service::stop);

			eventually(() -> rig.events.contains("driver.close"));
			Awaitility.await().during(Duration.ofMillis(300)).atMost(PATIENCE)
				.until(() -> !stopping.isDone() && rig.events.size() == 2);

			assertThat(rig.events, contains("detach", "driver.close"));

			busy.countDown();
			stopping.join();

			assertThat(rig.events, contains("detach", "driver.close", "runtime.close, driver closed: true",
				"block log closed"));
		}
	}

	@Example
	public void shouldStopOnlyOnce() {
		try (Rig rig = Rig.ready()) {
			final LedgerService service = rig.started(false);

			eventually(() -> service.status().running());
			service.stop();
			service.stop();

			assertThat(rig.events, contains("attach", "detach", "driver.close", "runtime.close, driver closed: true",
				"block log closed"));
			assertThat(service.status().running(), is(false));
		}
	}

	@Example
	public void shouldLeaveNoThreadOfItsOwnOnceStopped() {
		try (Rig rig = Rig.ready()) {
			final LedgerService service = rig.started(true);

			eventually(() -> service.status().signing());
			assertThat(ledgerThreads(), hasSize(2));

			service.stop();

			assertThat(ledgerThreads(), empty());
		}
	}

	@Example
	public void shouldStopWhileItStillWaitsForTheSnapshotWithoutTouchingTheGateway() {
		try (Rig rig = new Rig(false, 0, 0)) {
			final LedgerService service = rig.started(true);

			Awaitility.await().during(Duration.ofMillis(200)).atMost(PATIENCE)
				.until(() -> !service.status().running());
			service.stop();

			assertThat(ledgerThreads(), empty());
			assertThat(rig.events, contains("runtime.close, driver closed: false"));
		}
	}

	@Example
	public void shouldTakeNoTransactionOnceStopped() {
		try (Rig rig = Rig.ready()) {
			final LedgerService service = rig.started(false);

			eventually(() -> service.status().running());
			service.stop();

			assertThat(submitted(service, rig.vote(1)), equalTo(Optional.of("The ledger is not running")));
			assertThat(service.status().running(), is(false));
			assertThat(service.height(), equalTo(OptionalLong.of(0)));
		}
	}

	@Example
	public void shouldReplayTheBlockLogOnTheEventThreadAndNotOnTheThreadThatBuiltIt() {
		try (Rig rig = Rig.ready()) {
			final LedgerService service = rig.started(false);

			eventually(() -> service.height().isPresent());

			assertThat(rig.builders, contains(EVENTS));
			assertThat(rig.builders, not(contains(Thread.currentThread().getName())));
		}
	}

	/* A test container names the threads of its node by what they inherit from the thread that makes them */
	@Example
	@SneakyThrows
	public void shouldMakeItsThreadsInTheContextOfTheThreadThatBuildsTheService() {
		final AtomicReference<String> onEvents = new AtomicReference<>();
		final AtomicReference<String> onTimers = new AtomicReference<>();
		final CountDownLatch both = new CountDownLatch(2);

		try (Rig rig = new Rig(false, 0, 0)) {
			final LedgerService service = rig.service();

			CONTEXT.set("the node that built it");
			service.begin(() -> rig.setup(false, Optional.empty()));
			CONTEXT.remove();
			service.runtime().execute(() -> {
				onEvents.set(CONTEXT.get());
				both.countDown();
			});
			service.runtime().schedule(() -> {
				onTimers.set(CONTEXT.get());
				both.countDown();
			}, 1);

			assertThat(both.await(PATIENCE.toSeconds(), TimeUnit.SECONDS), is(true));
		} finally {
			CONTEXT.remove();
		}

		assertThat(onEvents.get(), equalTo("the node that built it"));
		assertThat(onTimers.get(), equalTo("the node that built it"));
	}

	@Example
	public void shouldResumeTheHeightAndTheSignStateOfANodeThatWasStopped() {
		try (Rig rig = Rig.ready()) {
			final LedgerService first = rig.started(true);

			eventually(() -> first.status().signing());
			assertThat(submitted(first, rig.vote(1)), equalTo(Optional.empty()));
			eventually(() -> first.height().equals(OptionalLong.of(1)));

			final byte[] tip = first.tipHash().orElseThrow();

			first.stop();
			assertThat(signStateOf(rig).orElseThrow().height(), is(2L));

			final LedgerService second = rig.started(true);

			eventually(() -> second.height().isPresent());
			assertThat(second.height(), equalTo(OptionalLong.of(1)));
			assertThat(second.tipHash().orElseThrow(), equalTo(tip));

			eventually(() -> second.status().signing());
			assertThat(submitted(second, rig.vote(2)), equalTo(Optional.empty()));
			eventually(() -> second.height().equals(OptionalLong.of(2)));
			second.stop();

			assertThat(signStateOf(rig).orElseThrow().height(), is(3L));
			assertThat(blocksOf(rig), hasSize(2));
			assertThat(blocksOf(rig).get(0).hash(), equalTo(tip));
		}
	}

	/* A changed genesis is another chain, and its blocks and the votes of the validator must never meet those of the
	   chain before: each chain has a directory of its own, named by the start of its chain id */
	@Example
	@SneakyThrows
	public void shouldKeepTheFilesOfEveryChainApartAndTakeUpTheFilesOfAChainAgain() {
		try (Rig rig = Rig.ready()) {
			final LedgerService first = rig.started(true);

			eventually(() -> first.status().signing());
			submitted(first, rig.vote(1));
			eventually(() -> first.height().equals(OptionalLong.of(1)));

			final byte[] tip = first.tipHash().orElseThrow();

			first.stop();

			final Path log = rig.chainDirectory().resolve(LedgerNodes.BLOCK_LOG);
			final long logBytes = Files.size(log);
			final LedgerGenesis other = rig.otherGenesis();
			final LedgerService second = rig.startedOn(other);

			eventually(() -> second.status().signing());
			assertThat(second.height(), equalTo(OptionalLong.of(0)));
			assertThat(second.tipHash().orElseThrow(), not(equalTo(tip)));
			second.stop();

			assertThat(Files.size(log), is(logBytes));
			assertThat(signStateOf(rig).orElseThrow().height(), is(2L));
			assertThat(Files.exists(rig.chainDirectory(other).resolve(LedgerNodes.BLOCK_LOG)), is(true));
			assertThat(Files.exists(rig.files.getPath(DIRECTORY, LedgerNodes.BLOCK_LOG)), is(false));
			assertThat(Files.exists(rig.files.getPath(DIRECTORY, LedgerNodes.SIGN_STATE)), is(false));

			final LedgerService again = rig.started(true);

			eventually(() -> again.height().isPresent());
			assertThat(again.height(), equalTo(OptionalLong.of(1)));
			assertThat(again.tipHash().orElseThrow(), equalTo(tip));
		}
	}

	@Example
	public void shouldNameTheDirectoryOfAChainByTheFirstSixteenCharactersOfItsId() {
		try (Rig rig = Rig.ready()) {
			final String name = LedgerService.chainDirectoryOf(rig.genesis);

			assertThat(name, equalTo(GenesisFile.chainIdHex(rig.genesis).substring(0, 16)));
			assertThat(name, matchesPattern("[0-9a-f]{16}"));
			assertThat(LedgerService.chainDirectoryOf(rig.otherGenesis()), not(equalTo(name)));
		}
	}

	/* The sign state is what keeps a validator from signing twice at a height, so a node whose block log is behind
	   it follows the chain and does not sign, until an operator has restored the log */
	@Example
	@SneakyThrows
	public void shouldFollowWithoutSigningWhenTheSignStateIsAheadOfTheBlockLog() {
		try (Rig rig = Rig.ready()) {
			final LedgerService first = rig.started(true);

			eventually(() -> first.status().signing());
			submitted(first, rig.vote(1));
			eventually(() -> first.height().equals(OptionalLong.of(1)));
			first.stop();

			try (SignStateFile ahead = new SignStateFile(rig.chainDirectory().resolve(LedgerNodes.SIGN_STATE))) {
				ahead.save(SignState.empty(5), 1);
			}

			final LedgerService second = rig.started(true);

			eventually(() -> second.height().isPresent());
			eventually(() -> rig.logged(Level.WARN).stream().anyMatch(line -> line.contains("A node to vote could "
				+ "not be started")));
			Awaitility.await().during(Duration.ofMillis(500)).atMost(PATIENCE)
				.until(() -> second.status().running() && !second.status().signing());

			assertThat(second.height(), equalTo(OptionalLong.of(1)));
		}
	}

	@SneakyThrows
	private static Optional<SignState> signStateOf(final Rig rig) {
		try (SignStateFile file = new SignStateFile(rig.chainDirectory().resolve(LedgerNodes.SIGN_STATE))) {
			return file.load();
		}
	}

	@SneakyThrows
	private static List<Block> blocksOf(final Rig rig) {
		try (BlockLog log = BlockLog.open(rig.chainDirectory().resolve(LedgerNodes.BLOCK_LOG))) {
			return log.blocks();
		}
	}

	@Example
	public void shouldHaltOnATrustedCheckpointTheChainDoesNotMatchAndSayItOnceAtErrorLevel() {
		try (Rig rig = Rig.ready()) {
			final LedgerService first = rig.started(true);

			eventually(() -> first.status().signing());
			submitted(first, rig.vote(1));
			eventually(() -> first.height().equals(OptionalLong.of(1)));

			final byte[] wrong = flipped(first.tipHash().orElseThrow());

			first.stop();
			rig.events.clear();

			final LedgerService second = rig.started(true, Optional.of(new Checkpoint(1, wrong)));

			eventually(() -> second.status().halted());
			eventually(() -> !ours(rig).isEmpty());
			Awaitility.await().during(Duration.ofMillis(500)).atMost(PATIENCE).until(() -> ours(rig).size() == 1);

			final Report report = second.status();

			assertThat(report.running(), is(false));
			assertThat(report.signing(), is(false));
			assertThat(report.haltReason(), containsString("differs from the trusted checkpoint"));
			assertThat(report.height(), is(1L));
			assertThat(ours(rig), contains(containsString(report.haltReason())));
			assertThat(second.height(), equalTo(OptionalLong.of(1)));
			assertThat(submitted(second, rig.vote(2)), equalTo(Optional.of("The ledger is not running")));
		}
	}

	private static List<String> ours(final Rig rig) {
		return rig.appender.list.stream().filter(entry -> entry.getLevel().equals(Level.ERROR)
			&& entry.getLoggerName().equals(LedgerService.class.getName()))
			.map(ILoggingEvent::getFormattedMessage).filter(line -> line.contains("halted")).toList();
	}

	@Example
	public void shouldAnswerStatusAndReadsFromAnyThreadAtEveryStageOfItsLife() {
		final List<Throwable> failures = new CopyOnWriteArrayList<>();
		final CountDownLatch done = new CountDownLatch(4);
		final AtomicReference<Boolean> running = new AtomicReference<>(true);

		try (Rig rig = Rig.ready()) {
			final LedgerService service = rig.service();

			assertThat(service.status(), equalTo(INACTIVE));
			assertThat(service.height(), equalTo(OptionalLong.empty()));
			assertThat(service.tipHash(), equalTo(Optional.empty()));
			assertThat(service.supply(), equalTo(OptionalLong.empty()));
			assertThat(service.balanceOf(Ed25519.publicKey(SEED)), equalTo(OptionalLong.empty()));

			for (int n = 0; n < 4; n++) {
				new Thread(() -> {
					while (running.get()) {
						try {
							service.status();
							service.height();
							service.tipHash();
							service.supply();
							service.balanceOf(Ed25519.publicKey(SEED));
						} catch (RuntimeException ex) {
							failures.add(ex);
						}
					}

					done.countDown();
				}).start();
			}

			service.begin(() -> rig.setup(true, Optional.empty()));
			eventually(() -> service.status().signing());
			submitted(service, rig.vote(1));
			eventually(() -> service.height().equals(OptionalLong.of(1)));
			service.stop();
			running.set(false);

			assertThat(done.await(PATIENCE.toSeconds(), TimeUnit.SECONDS), is(true));
			assertThat(failures, empty());
			assertThat(service.status().running(), is(false));
			assertThat(service.status().height(), is(1L));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void awaitQuietly(final CountDownLatch latch) {
		try {
			latch.await(PATIENCE.toSeconds(), TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
