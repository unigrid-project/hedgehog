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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.bootstrap.BootstrapFetch;
import org.unigrid.hedgehog.command.option.OptionsCli;
import org.unigrid.hedgehog.common.model.Version;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.model.bootstrap.SnapshotInstaller;
import org.unigrid.hedgehog.model.cdi.CDIContext;
import org.unigrid.hedgehog.model.gridnode.GridnodeSetup;
import picocli.CommandLine;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class DaemonTest {
	private static final long INSTALL_TIMEOUT_SECONDS = 10;
	private static final AtomicInteger CONTAINERS = new AtomicInteger();

	private static volatile String refusal;
	private static volatile String runningVersion;

	/*
	   A fake that proceeds must be the only one on its class, so these are installed once and steered by
	   each try. The container would serve until stopped, so starting it is only counted.
	*/
	@BeforeContainer
	private static void installFakes() {
		new MockUp<CDIContext>() {
			@Mock public void run() {
				CONTAINERS.incrementAndGet();
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
	}

	@BeforeTry
	public void beforeTry() {
		CONTAINERS.set(0);
		refusal = null;
		runningVersion = null;
	}

	@Provide
	public Arbitrary<String> provideHost() {
		return OptionsCli.hosts();
	}

	@Provide
	public Arbitrary<String> provideVersion() {
		final Arbitrary<Integer> part = Arbitraries.integers().between(0, 999);

		return Combinators.combine(part, part, part, Arbitraries.of("", "-dev.1", "-SNAPSHOT"))
			.as((major, minor, patch, suffix) -> major + "." + minor + "." + patch + suffix);
	}

	@SneakyThrows
	private static Daemon daemonInstallingWith(SnapshotInstaller installer) {
		final Daemon daemon = new Daemon();
		final Field field = Daemon.class.getDeclaredField("snapshotInstaller");

		field.setAccessible(true);
		field.set(daemon, installer);
		return daemon;
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

	@Example
	@SneakyThrows
	public void shouldRefuseAGridnodeKeyFileItCannotRead() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = HedgehogCli.run(() -> new CommandLine(Hedgehog.class)
				.registerConverter(Path.class, fs::getPath), "daemon", "-G", "/keys/gridnode.key"
			);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("Cannot read the gridnode key file /keys/gridnode.key"));
			result.assertNoStackTrace();
			assertThat(CONTAINERS.get(), equalTo(0));
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
}
