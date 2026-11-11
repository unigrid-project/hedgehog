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

import ch.qos.logback.classic.spi.IThrowableProxy;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import org.jboss.weld.environment.se.events.ContainerInitialized;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.jqwik.FreePort;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.ledger.ExclusiveLock;
import org.unigrid.hedgehog.model.cdi.CDIContext;
import org.unigrid.hedgehog.model.spork.SporkDatabaseLock;
import org.unigrid.hedgehog.server.ListenException;
import org.unigrid.hedgehog.server.rest.RestToken;

/* A daemon run from the command line, as an operator runs it, on a port that something else holds. The container of
   the node starts for real, up to the server that cannot listen, and is given up then: what it started by then runs
   on, as it does in a node until the process exits, and here until the JVM of this test class does. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class DaemonBusyPortTest {
	private static final String HOST = "127.0.0.1";
	private static final FileSystem FILES = Jimfs.newFileSystem(Configuration.unix());

	/* A start takes seconds, more of them on a machine that runs other tests */
	private static final Duration START_TIME = Duration.ofMinutes(2);

	/* What the system says of a port that is taken is a few words in the language of the machine */
	private static final int MAX_REASON_LENGTH = 80;

	@BeforeContainer
	private static void installFakes() {
		/* No test takes the lock in the data directory of this machine, or reads or writes anything else there */
		new MockUp<SporkDatabaseLock>() {
			@Mock public /* static */ void holdWhileRunning(Path dataDirectory, ExclusiveLock.Locker locker) {
				/* Held by no one */
			}
		};

		new MockUp<ApplicationDirectory>() {
			@Mock public Path getUserCacheDir() {
				return FILES.getPath("/cache");
			}

			@Mock public Path getUserConfigDir() {
				return FILES.getPath("/config");
			}

			@Mock public Path getUserDataDir() {
				return FILES.getPath("/data");
			}

			@Mock public Path getUserLogDir() {
				return FILES.getPath("/log");
			}
		};

		/* A node that started all the same would download the snapshot of its release */
		new MockUp<Daemon>() {
			@Mock void start(ContainerInitialized event) {
				/* Nothing to install */
			}
		};
	}

	/* A port that nothing holds, for the server that is to start */
	private static int freePort() throws IOException {
		try (FreePort port = FreePort.hold()) {
			return port.getNumber();
		}
	}

	/* A node that started all the same would serve until it is stopped, and the example with it, so it is stopped
	   after a time that no start takes: the exit code then tells that it started */
	private static HedgehogCli.Result runDaemon(final int netPort, final int restPort) {
		final Thread stopper = Thread.ofVirtual().start(() -> {
			try {
				Thread.sleep(START_TIME);
				CDIContext.stop();
			} catch (InterruptedException ex) {
				/* The daemon returned by itself */
			}
		});

		try {
			return HedgehogCli.run("daemon", "-v", "--nethost", HOST, "--netport", String.valueOf(netPort),
				"--resthost", HOST, "--restport", String.valueOf(restPort), "--resttoken", RestToken.generate(),
				"--no-seeds");
		} finally {
			stopper.interrupt();
		}
	}

	/* The line names the address and the options that set it, and ends in what the system says of the port. Nothing of
	   the exceptions that the container wraps the failure in is shown. */
	private static void assertToldInOneLine(final HedgehogCli.Result result, final String start) {
		assertThat(result.err(), startsWith(start));
		assertThat(result.err().lines().toList(), hasSize(1));

		assertThat(result.err().strip().length(), allOf(greaterThan(start.length()),
			lessThan(start.length() + MAX_REASON_LENGTH)));

		assertThat(result.err(), not(containsString("WELD-")));
		result.assertNoStackTrace();
		assertThat(result.out(), not(containsString("\tat ")));
		assertThat(result.exitCode(), equalTo(1));
	}

	/* The trace is logged once, from the exception of the container down to the bind that failed, and the line that
	   the operator was shown is the message of the exception that names the server */
	private static void assertLoggedOnceWithItsCause(final ErrorLog log, final HedgehogCli.Result result) {
		assertThat(log.traces(), hasSize(1));

		final List<IThrowableProxy> trace = log.traces().getFirst();
		final IThrowableProxy told = trace.get(trace.size() - 2);

		assertThat(trace.getFirst().getClassName(), endsWith("DeploymentException"));
		assertThat(told.getClassName(), equalTo(ListenException.class.getName()));
		assertThat(told.getMessage(), equalTo(result.err().strip()));
		assertThat(trace.getLast().getClassName(), equalTo(BindException.class.getName()));
	}

	@Example
	public void shouldTellOfABusyRestPortInOneLineAndLogTheTrace() throws IOException {
		try (ServerSocket other = new ServerSocket(0, 1, InetAddress.getByName(HOST));
			ErrorLog log = new ErrorLog()) {

			final HedgehogCli.Result result = runDaemon(freePort(), other.getLocalPort());

			assertToldInOneLine(result, "Cannot listen on %s:%d for REST (--resthost, --restport): "
				.formatted(HOST, other.getLocalPort()));

			assertLoggedOnceWithItsCause(log, result);
		}
	}

	/* The peers of a node reach it over UDP, so the port is held for UDP */
	@Example
	public void shouldTellOfABusyNetPortInOneLineAndLogTheTrace() throws IOException {
		try (DatagramSocket other = new DatagramSocket(0, InetAddress.getByName(HOST));
			ErrorLog log = new ErrorLog()) {

			final HedgehogCli.Result result = runDaemon(other.getLocalPort(), freePort());

			assertToldInOneLine(result, "Cannot listen on %s:%d for P2P (--nethost, --netport): "
				.formatted(HOST, other.getLocalPort()));

			assertLoggedOnceWithItsCause(log, result);
		}
	}
}
