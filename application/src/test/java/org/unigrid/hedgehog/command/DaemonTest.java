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

package org.unigrid.hedgehog.command;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures.KEY_FILE;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import jakarta.inject.Provider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.bootstrap.BootstrapFetch;
import org.unigrid.hedgehog.model.option.OptionsCli;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.common.model.Version;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.ExclusiveLock;
import org.unigrid.hedgehog.ledger.FileLockedException;
import org.unigrid.hedgehog.model.bootstrap.SnapshotInstaller;
import org.unigrid.hedgehog.model.cdi.CDIContext;
import org.unigrid.hedgehog.model.gridnode.GridnodeSetup;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkDatabaseLock;
import org.unigrid.hedgehog.model.util.Reflection;
import org.unigrid.hedgehog.producer.SporkDatabaseProducer;
import org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures;
import org.unigrid.hedgehog.service.ledger.LedgerTestChain;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.OptionSpec;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class DaemonTest {
	private static final long INSTALL_TIMEOUT_SECONDS = 10;
	private static final int DIGEST_SIZE = 64;
	private static final AtomicInteger CONTAINERS = new AtomicInteger();
	private static final int TAKEN = 17;

	private static final List<Path> LOCKED = new CopyOnWriteArrayList<>();

	/* The hidden options of the parts that this release leaves off, each by its longest name and with the arguments
	   that give it: those that tell the node to run a part */
	private static final List<Tuple2<String, List<String>>> REFUSED_WITHOUT_THE_PREVIEW = List.of(
		Tuple.of("--ledger", List.of("--ledger")),
		Tuple.of("--gridnode", List.of("-G", "/keys/gridnode.key")),
		Tuple.of("--socks-port", List.of("--socks-port", "1080"))
	);

	/* And those that only say how a part runs, with a file that is not there or a value that fails if anything uses
	   it */
	private static final List<Tuple2<String, List<String>>> TAKEN_WITHOUT_THE_PREVIEW = List.of(
		Tuple.of("--ledger-key", List.of("--ledger-key", KEY_FILE)),
		Tuple.of("--ledger-genesis", List.of("--ledger-genesis", "/genesis.json")),
		Tuple.of("--ledger-trusted", List.of("--ledger-trusted=garbage")),
		Tuple.of("--announce-address", List.of("--announce-address", "garbage")),
		Tuple.of("--socks-host", List.of("--socks-host", "0.0.0.0")),
		Tuple.of("--socks-country", List.of("--socks-country", "SE")),
		Tuple.of("--socks-geoip", List.of("--socks-geoip", "/geoip/country.mmdb")),
		Tuple.of("--no-s3", List.of("--no-s3")),
		Tuple.of("--s3host", List.of("--s3host", "s3.example.org")),
		Tuple.of("--s3port", List.of("--s3port", "1")),
		Tuple.of("--s3domain", List.of("--s3domain", "s3.example.org")),
		Tuple.of("--s3cachedir", List.of("--s3cachedir", "/cache")),
		Tuple.of("--s3cachesize", List.of("--s3cachesize", "1")),
		Tuple.of("--s3cachehalflife", List.of("--s3cachehalflife", "PT1H")),
		Tuple.of("--s3lifecycleinterval", List.of("--s3lifecycleinterval", "PT0S")),
		Tuple.of("--s3root", List.of("--s3root", "root")),
		Tuple.of("--s3plain", List.of("--s3plain"))
	);

	private static volatile String refusal;
	private static volatile String runningVersion;
	private static volatile String lockedBy;
	private static volatile IOException lockFailure;
	private static volatile RuntimeException startFailure;

	/*
	   A fake that proceeds must be the only one on its class, so these are installed once and steered by
	   each try. The container would serve until stopped, so starting it is only counted.
	*/
	@BeforeContainer
	private static void installFakes() {
		new MockUp<CDIContext>() {
			@Mock public void run() {
				CONTAINERS.incrementAndGet();

				if (startFailure != null) {
					throw startFailure;
				}
			}
		};

		new MockUp<GridnodeSetup>() {
			@Mock public /* static */ void validate(Invocation invocation) {
				if (refusal != null) {
					throw new IllegalArgumentException(refusal);
				}

				invocation.proceed();
			}
		};

		new MockUp<Version>() {
			@Mock public /* static */ String getVersionNumber(Invocation invocation) {
				return runningVersion == null ? invocation.proceed() : runningVersion;
			}
		};

		/* No test takes the lock in the data directory of this machine */
		new MockUp<SporkDatabaseLock>() {
			@Mock public /* static */ void holdWhileRunning(Path dataDirectory, ExclusiveLock.Locker locker)
				throws IOException {

				LOCKED.add(dataDirectory);

				if (lockFailure != null) {
					throw lockFailure;
				}

				if (lockedBy != null) {
					throw new FileLockedException(lockedBy);
				}
			}
		};
	}

	@BeforeTry
	public void beforeTry() {
		CONTAINERS.set(0);
		LOCKED.clear();
		refusal = null;
		runningVersion = null;
		lockedBy = null;
		lockFailure = null;
		startFailure = null;
	}

	/* The container wraps what a part of the node throws as it starts in exceptions of its own, hundreds of lines of
	   trace in all. The operator is told what failed first, in one line, and the trace goes to the log, which the
	   first -v shows. */
	@Example
	public void shouldTellWhatFailedTheStartInOneLineAndLogTheTrace() {
		startFailure = new IllegalStateException("Unable to invoke init()",
			new UncheckedIOException(new IOException("No space left on device")));

		try (ErrorLog log = new ErrorLog()) {
			final HedgehogCli.Result result = HedgehogCli.run("daemon", "-v");

			assertThat(result.err(), equalTo("IOException: No space left on device" + System.lineSeparator()));
			assertThat(result.exitCode(), equalTo(1));
			assertThat(result.out(), not(containsString("\tat ")));
			assertThat(CONTAINERS.get(), equalTo(1));

			assertThat(log.traces().stream().map(trace -> trace.stream().map(IThrowableProxy::getClassName).toList())
				.toList(), equalTo(List.of(List.of(IllegalStateException.class.getName(),
				UncheckedIOException.class.getName(), IOException.class.getName()))));
		}
	}

	/* The container tells what is wrong with a bean over several lines, the first of which says the least */
	@Example
	public void shouldTellAFailureWhoseMessageSpansLinesInOneLine() {
		startFailure = new IllegalStateException("WELD-001408: Unsatisfied dependencies for type Topology\n"
			+ "  at injection point [BackedAnnotatedField] @Inject private P2PServer.topology\n"
			+ "\tat P2PServer.topology(P2PServer.java:0)\n");

		final HedgehogCli.Result result = HedgehogCli.run("daemon");

		assertThat(result.err(), equalTo("IllegalStateException: WELD-001408: Unsatisfied dependencies for type "
			+ "Topology at injection point [BackedAnnotatedField] @Inject private P2PServer.topology "
			+ "at P2PServer.topology(P2PServer.java:0)" + System.lineSeparator()));

		assertThat(result.exitCode(), equalTo(1));
	}

	/* A node that starts has no failure to tell of, on the terminal or in the log */
	@Example
	public void shouldTellOfNoFailureWhenTheNodeStarts() {
		try (ErrorLog log = new ErrorLog()) {
			final HedgehogCli.Result result = HedgehogCli.run("daemon", "-v");

			assertThat(result.err(), is(emptyString()));
			assertThat(result.exitCode(), equalTo(0));
			assertThat(log.events(), is(empty()));
		}
	}

	@Command(name = "failing")
	public static class Failing implements Runnable {
		@Override
		public void run() {
			throw new IllegalStateException("Not the start of a node");
		}
	}

	/* Only the start of a node is told in one line: what any other command lets out is shown with its trace */
	@Example
	public void shouldShowWhatAnyOtherCommandThrowsWithItsTrace() {
		final HedgehogCli.Result result = HedgehogCli.run(() -> Hedgehog.commandLine().addSubcommand(new Failing()),
			"failing");

		assertThat(result.err(), startsWith("java.lang.IllegalStateException: Not the start of a node"));
		assertThat(result.err(), containsString("\tat "));
		assertThat(result.exitCode(), equalTo(1));
	}

	/* A node writes its spork database over the file as it saves, so it holds a lock on it while it runs, and does not
	   start while another node or an import holds it */
	@Example
	public void shouldHoldTheLockOfItsSporkDatabaseWhileItRunsAndRefuseToStartWhileItIsHeld() {
		final HedgehogCli.Result started = HedgehogCli.run("daemon");

		assertThat(started.err(), started.exitCode(), equalTo(0));
		assertThat(LOCKED, equalTo(List.of(ApplicationDirectory.create().getUserDataDir())));

		lockedBy = "The spork database in /data is in use";
		CONTAINERS.set(0);

		final HedgehogCli.Result refused = HedgehogCli.run("daemon");

		assertThat(refused.exitCode(), equalTo(2));
		assertThat(refused.err(), containsString("The spork database in /data is in use"));
		refused.assertNoStackTrace();
		assertThat(CONTAINERS.get(), equalTo(0));
	}

	/* The file system gives no sentence with a refusal such as a data directory that the user cannot write, but the
	   file alone: the operator is told what failed and why, and not shown a path and the usage of the command */
	@Example
	public void shouldSayWhyItCannotLockTheSporkDatabaseWhenTheFileSystemGivesOnlyTheFile() {
		final Path dataDirectory = ApplicationDirectory.create().getUserDataDir();
		final String file = dataDirectory.resolve(SporkDatabaseLock.FILE).toString();

		Map.<String, IOException>of("permission denied", new AccessDeniedException(file),
			"no such file or directory", new NoSuchFileException(file),
			"Read-only file system", new FileSystemException(file, null, "Read-only file system"),
			"IOException", new IOException()
		).forEach((reason, failure) -> {
			lockFailure = failure;
			CONTAINERS.set(0);

			final HedgehogCli.Result refused = HedgehogCli.run("daemon");

			assertThat(refused.exitCode(), equalTo(2));
			assertThat(refused.err(),
				containsString("Cannot lock the spork database in " + dataDirectory + ": " + reason));
			refused.assertNoStackTrace();
			assertThat(CONTAINERS.get(), equalTo(0));
		});
	}

	@Provide
	public Arbitrary<String> provideHost() {
		return OptionsCli.hosts();
	}

	@Provide
	public Arbitrary<String> provideBadCheckpoint() {
		return Arbitraries.of("12", "12:abc", "-5:" + "ab".repeat(DIGEST_SIZE), "0:" + "ab".repeat(DIGEST_SIZE),
			"12:" + "xy".repeat(DIGEST_SIZE)
		);
	}

	@Provide
	public Arbitrary<String> provideVersion() {
		final Arbitrary<Integer> part = Arbitraries.integers().between(0, 999);

		return Combinators.combine(part, part, part, Arbitraries.of("", "-dev.1", "-SNAPSHOT"))
			.as((major, minor, patch, suffix) -> major + "." + minor + "." + patch + suffix);
	}

	private static Daemon daemonInstallingWith(SnapshotInstaller installer) {
		return daemonInstallingWith(installer, () -> SporkDatabase.builder().build());
	}

	/* A daemon as its container sets it up: with the installer and with the lookup of the spork database, which reads
	   the database of the node the first time it is made */
	@SneakyThrows
	private static Daemon daemonInstallingWith(SnapshotInstaller installer, Provider<SporkDatabase> sporkDatabase) {
		final Daemon daemon = new Daemon();

		field("snapshotInstaller").set(daemon, installer);
		field("sporkDatabase").set(daemon, sporkDatabase);
		return daemon;
	}

	@SneakyThrows
	private static Field field(String name) {
		final Field field = Daemon.class.getDeclaredField(name);

		field.setAccessible(true);
		return field;
	}

	@Property(tries = 30)
	public void shouldStartTheNodeOnTheAddressItIsGiven(@ForAll("provideHost") String host,
		@ForAll @IntRange(min = 1, max = OptionsCli.MAX_PORT) int port) {

		final HedgehogCli.Result result = HedgehogCli.run("daemon", "-H", host, "-p", String.valueOf(port));

		assertThat(result.exitCode(), equalTo(0));
		assertThat(CONTAINERS.get(), equalTo(1));
	}

	@Property(tries = 30)
	public void shouldRefuseASetupItCannotRunWithoutStarting(
		@ForAll @AlphaChars @StringLength(min = 1, max = 60) String reason) {

		refusal = reason;

		final HedgehogCli.Result result = HedgehogCli.run("daemon");

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString(reason));
		result.assertNoStackTrace();
		assertThat(CONTAINERS.get(), equalTo(0));
	}

	private static HedgehogCli.Result runOn(FileSystem fs, String... args) {
		return HedgehogCli.run(() -> new CommandLine(Hedgehog.class).registerConverter(Path.class, fs::getPath), args);
	}

	private static void assertRefusedWithout(HedgehogCli.Result result, String... reasons) {
		assertThat(result.exitCode(), equalTo(2));
		Stream.of(reasons).forEach(reason -> assertThat(result.err(), containsString(reason)));
		result.assertNoStackTrace();
		assertThat(CONTAINERS.get(), equalTo(0));
	}

	@Provide
	public Arbitrary<Tuple2<String, List<String>>> provideOptionOfAPartThisReleaseLeavesOff() {
		return Arbitraries.of(REFUSED_WITHOUT_THE_PREVIEW);
	}

	@Provide
	public Arbitrary<Tuple2<String, List<String>>> provideOptionTakenWithoutThePreview() {
		return Arbitraries.of(TAKEN_WITHOUT_THE_PREVIEW);
	}

	private static String[] daemonGiven(List<String> option) {
		return Stream.concat(Stream.of("daemon"), option.stream()).toArray(String[]::new);
	}

	/* A node that is told to run a part this release leaves off is refused, rather than started without it */
	@Property
	public void shouldRefuseAnOptionOfThePartsThisReleaseLeavesOffWithoutThePreview(
		@ForAll("provideOptionOfAPartThisReleaseLeavesOff") Tuple2<String, List<String>> option) {

		assertRefusedWithout(HedgehogCli.run(daemonGiven(option.get2())),
			option.get1() + " is not part of this release");
	}

	/* The hidden options of the daemon by their longest name, bar the switch that turns the parts on */
	private static Set<String> hiddenOptionsOfTheDaemon() {
		final CommandLine daemon = new CommandLine(Hedgehog.class).getSubcommands().get("daemon");

		return daemon.getCommandSpec().options().stream().filter(OptionSpec::hidden)
			.map(option -> Stream.of(option.names()).max(Comparator.comparingInt(String::length)).orElseThrow())
			.filter(name -> !"--preview".equals(name)).collect(Collectors.toCollection(TreeSet::new));
	}

	/* An option added to the daemon in secret has to be sorted into the ones it refuses and the ones it takes, and one
	   it takes has to be given to the node of DaemonHiddenOptionStartTest, which shows that it starts nothing */
	@Example
	public void shouldHaveSortedEveryHiddenOptionIntoRefusedOrTaken() {
		final Set<String> sorted = Stream.concat(REFUSED_WITHOUT_THE_PREVIEW.stream(),
			TAKEN_WITHOUT_THE_PREVIEW.stream()).map(Tuple2::get1).collect(Collectors.toCollection(TreeSet::new));

		assertThat(sorted, equalTo(hiddenOptionsOfTheDaemon()));
		assertThat(TAKEN_WITHOUT_THE_PREVIEW.size(), equalTo(TAKEN));
	}

	/* The daemon reads none of them while the part is off, so it starts as it does without them, whatever file is not
	   there and whatever value no part can use */
	@Property(tries = TAKEN)
	@SneakyThrows
	public void shouldTakeAnyOtherHiddenOptionOnItsOwnAndStartTheNode(
		@ForAll("provideOptionTakenWithoutThePreview") Tuple2<String, List<String>> option) {

		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final HedgehogCli.Result result = runOn(fs, daemonGiven(option.get2()));

			assertThat(option.get1() + ": " + result.err(), result.exitCode(), equalTo(0));
			assertThat(result.err(), not(containsString("is not part of this release")));
			result.assertNoStackTrace();
			assertThat(CONTAINERS.get(), equalTo(1));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAGridnodeKeyFileItCannotRead() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			assertRefusedWithout(runOn(fs, "daemon", "--preview", "-G", "/keys/gridnode.key"),
				"Cannot read the gridnode key file /keys/gridnode.key"
			);
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseALedgerKeyFileItCannotRead() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			assertRefusedWithout(runOn(fs, "daemon", "--preview", "--ledger", "--ledger-key", KEY_FILE),
				"Cannot read the ledger key file " + KEY_FILE
			);
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseALedgerKeyFileOthersCanRead() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.write(fs, KEY_FILE, "00".repeat(Ed25519.SEED_SIZE) + "\n", "rw-r--r--");

			assertRefusedWithout(runOn(fs, "daemon", "--preview", "--ledger", "--ledger-key", KEY_FILE),
				KEY_FILE, "chmod 600"
			);
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseALedgerKeyFileThatHoldsNoSeedWithoutShowingIt() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.write(fs, KEY_FILE, "ab".repeat(Ed25519.SEED_SIZE - 1) + "xx\n",
				LedgerKeyFixtures.OWNER_ONLY
			);

			final HedgehogCli.Result result = runOn(fs, "daemon", "--preview", "--ledger", "--ledger-key", KEY_FILE);

			assertRefusedWithout(result, KEY_FILE);
			assertThat(result.err(), not(containsString("abababab")));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseALedgerGenesisFileThatIsNotThere() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			assertRefusedWithout(runOn(fs, "daemon", "--preview", "--ledger", "--ledger-genesis", "/genesis.json"),
				"/genesis.json"
			);
		}
	}

	@Property(tries = 5)
	public void shouldRefuseATrustedCheckpointItCannotRead(@ForAll("provideBadCheckpoint") String checkpoint) {
		assertRefusedWithout(HedgehogCli.run("daemon", "--preview", "--ledger", "--ledger-trusted=" + checkpoint),
			"--ledger-trusted"
		);
	}

	@Example
	@SneakyThrows
	public void shouldRefuseALedgerThatHasNoGenesisToRunOn() {
		assertRefusedWithout(HedgehogCli.run("daemon", "--preview", "--ledger"),
			"--ledger needs a genesis: give --ledger-genesis <file>"
		);
	}

	@Example
	@SneakyThrows
	public void shouldRefuseALedgerGenesisThatIsNotJson() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.write(fs, "/genesis.json", "{ not a genesis", null);

			assertRefusedWithout(runOn(fs, "daemon", "--preview", "--ledger", "--ledger-genesis", "/genesis.json"),
				"/genesis.json"
			);
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseALedgerGenesisWithAFieldThatIsNotInTheFormat() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.write(fs, "/genesis.json", "{\"colour\": 1}", null);

			assertRefusedWithout(runOn(fs, "daemon", "--preview", "--ledger", "--ledger-genesis", "/genesis.json"),
				"/genesis.json", "unknown field 'colour'"
			);
		}
	}

	@Example
	@SneakyThrows
	public void shouldStartWhenTheLedgerSetupHolds() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, new byte[Ed25519.SEED_SIZE]);
			LedgerKeyFixtures.writeGenesis(fs, "/genesis.json", new byte[Ed25519.SEED_SIZE]);

			final HedgehogCli.Result result = runOn(fs, "daemon", "--preview", "--ledger", "--ledger-key", KEY_FILE,
				"--ledger-genesis", "/genesis.json", "--ledger-trusted=7:" + "ab".repeat(DIGEST_SIZE)
			);

			assertThat(result.exitCode(), equalTo(0));
			assertThat(CONTAINERS.get(), equalTo(1));
		}
	}

	@Example
	@SneakyThrows
	public void shouldStartWithAKeyThatIsNoValidatorOfTheGenesis() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, new byte[Ed25519.SEED_SIZE]);
			LedgerKeyFixtures.writeGenesis(fs, "/genesis.json", LedgerTestChain.seed(1));

			final HedgehogCli.Result result = runOn(fs, "daemon", "--preview", "--ledger", "--ledger-key", KEY_FILE,
				"--ledger-genesis", "/genesis.json"
			);

			assertThat(result.exitCode(), equalTo(0));
			assertThat(CONTAINERS.get(), equalTo(1));
		}
	}

	@Example
	@SneakyThrows
	public void shouldLeaveTheLedgerSetupUnreadWhileTheLedgerIsOff() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.write(fs, "/genesis.json", "{ not a genesis", null);

			final HedgehogCli.Result result = runOn(fs, "daemon", "--ledger-key", KEY_FILE, "--ledger-genesis",
				"/genesis.json", "--ledger-trusted=garbage"
			);

			assertThat(result.exitCode(), equalTo(0));
			assertThat(CONTAINERS.get(), equalTo(1));
		}
	}

	@Property(tries = 10)
	@SneakyThrows
	public void shouldInstallTheSnapshotOfTheRunningVersionInTheBackground(@ForAll("provideVersion") String version) {
		final CompletableFuture<URL> source = new CompletableFuture<>();
		final CompletableFuture<Thread> worker = new CompletableFuture<>();

		runningVersion = version;

		new MockUp<SnapshotInstaller>() {
			@Mock public void installIfMissing(URL url) {
				worker.complete(Thread.currentThread());
				source.complete(url);
			}
		};

		daemonInstallingWith(new SnapshotInstaller()).start(null);

		assertThat(source.get(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS), equalTo(BootstrapFetch.defaultUrl(version)));
		assertThat(worker.get().isVirtual(), equalTo(true));
		assertThat(worker.get().getName(), equalTo("snapshot-install"));
	}

	/*
	   The install judges a snapshot by the board keys the node holds, and the node holds the changes to them that its
	   release ships, and those that its spork database stores, only once it read the database. So it reads the
	   database as it starts, on the thread that starts it, and starts the install after that.

	   A thread that is started runs when it gets to, which may be after whatever follows its start, so the lookup
	   also tells whether the daemon holds an install by then.
	*/
	@Example
	@SneakyThrows
	public void shouldReadTheSporkDatabaseAsItStartsBeforeItStartsTheInstallOfTheSnapshot() {
		final List<String> sequence = new CopyOnWriteArrayList<>();
		final CompletableFuture<Thread> worker = new CompletableFuture<>();
		final String starter = Thread.currentThread().getName();

		new MockUp<SnapshotInstaller>() {
			@Mock public void installIfMissing(URL url) {
				sequence.add("install on " + Thread.currentThread().getName());
				worker.complete(Thread.currentThread());
			}
		};

		final Daemon daemon = daemonInstallingWith(new SnapshotInstaller());

		field("sporkDatabase").set(daemon, (Provider<SporkDatabase>) () -> {
			sequence.add(hasStartedTheInstall(daemon) ? "database after the install was started"
				: "database on " + Thread.currentThread().getName());
			return SporkDatabase.builder().build();
		});

		daemon.start(null);
		worker.get(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

		assertThat(sequence, equalTo(List.of("database on " + starter, "install on snapshot-install")));
	}

	@SneakyThrows
	private static boolean hasStartedTheInstall(Daemon daemon) {
		return field("snapshotInstall").get(daemon) != null;
	}

	/* A spork database that cannot be read is moved aside by the lookup that reads it, which hands out an empty one
	   in its place: the node starts all the same, and so does the install */
	@Example
	@SneakyThrows
	public void shouldStartTheInstallOfTheSnapshotWhenTheSporkDatabaseCannotBeRead() {
		final CompletableFuture<Thread> worker = new CompletableFuture<>();

		new MockUp<SnapshotInstaller>() {
			@Mock public void installIfMissing(URL url) {
				worker.complete(Thread.currentThread());
			}
		};

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path data = Files.createDirectories(fs.getPath("/data"));
			final SporkDatabaseProducer producer = new SporkDatabaseProducer();

			Files.write(data.resolve(SporkDatabase.SPORK_DB_FILE), new byte[] {1, 2, 3});

			FieldUtils.writeField(producer, "applicationDirectory", new ApplicationDirectory("test", "test") {
				@Override
				public Path getUserDataDir() {
					return data;
				}
			}, true);

			try {
				daemonInstallingWith(new SnapshotInstaller(), () -> produced(producer)).start(null);
				worker.get(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

				assertThat(namesIn(data),
					contains(startsWith(SporkDatabase.SPORK_DB_FILE + ".unreadable-")));
			} finally {
				Reflection.invoke(producer, "destroy");
			}
		}
	}

	/* What the container hands out as the spork database: the producer reads the file at the first lookup */
	@SneakyThrows
	private static SporkDatabase produced(SporkDatabaseProducer producer) {
		return Reflection.invoke(producer, "produce");
	}

	@SneakyThrows
	private static List<String> namesIn(Path directory) {
		try (Stream<Path> files = Files.list(directory)) {
			return files.map(file -> file.getFileName().toString()).toList();
		}
	}

	/* A download, or the pause before the next one, would go on in a process that outlives the node, as the one of
	   a test does, so a node that stops interrupts it */
	@Example
	@SneakyThrows
	public void shouldInterruptTheInstallOfTheSnapshotAsItStops() {
		final CompletableFuture<Thread> worker = new CompletableFuture<>();

		new MockUp<SnapshotInstaller>() {
			@Mock public void installIfMissing(URL url) {
				worker.complete(Thread.currentThread());

				try {
					new CountDownLatch(1).await();
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		};

		final Daemon daemon = daemonInstallingWith(new SnapshotInstaller());

		daemon.start(null);
		worker.get(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		daemon.destroy();

		assertThat(worker.get().join(Duration.ofSeconds(INSTALL_TIMEOUT_SECONDS)), equalTo(true));
	}

	/* A node that is stopped before it started the install has none to interrupt */
	@Example
	public void shouldStopWithNoInstallOfTheSnapshotStarted() {
		new Daemon().destroy();
	}
}
