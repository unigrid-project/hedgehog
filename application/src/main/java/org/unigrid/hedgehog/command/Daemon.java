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

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Optional;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.weld.environment.se.events.ContainerInitialized;
import org.unigrid.hedgehog.command.bootstrap.BootstrapFetch;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.option.GridnodeOptions;
import org.unigrid.hedgehog.model.option.LedgerOptions;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.model.option.PreviewOptions;
import org.unigrid.hedgehog.model.option.RestOptions;
import org.unigrid.hedgehog.model.option.S3Options;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.common.model.Version;
import org.unigrid.hedgehog.ledger.ExclusiveLock;
import org.unigrid.hedgehog.ledger.FileLockedException;
import org.unigrid.hedgehog.model.PreviewSetup;
import org.unigrid.hedgehog.model.bootstrap.SnapshotInstaller;
import org.unigrid.hedgehog.model.option.SocksOptions;
import org.unigrid.hedgehog.model.cdi.CDIContext;
import org.unigrid.hedgehog.model.gridnode.GridnodeSetup;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkDatabaseLock;
import org.unigrid.hedgehog.model.util.TerminalText;
import org.unigrid.hedgehog.server.ListenException;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.server.rest.RestServer;
import org.unigrid.hedgehog.service.ledger.LedgerSetup;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Spec;

@Slf4j
@ApplicationScoped
@Command(name = "daemon")
public class Daemon extends CDIContext implements Runnable {
	@Mixin private NetOptions netOptions;
	@Mixin private RestOptions restOptions;
	@Mixin private S3Options s3Options;
	@Mixin private GridnodeOptions gridnodeOptions;
	@Mixin private LedgerOptions ledgerOptions;
	@Mixin private SocksOptions socksOptions;
	@Mixin private PreviewOptions previewOptions;

	@Inject private P2PServer p2pServer;
	@Inject private RestServer restServer;
	@Inject private SnapshotInstaller snapshotInstaller;
	@Inject private Provider<SporkDatabase> sporkDatabase;

	@Spec private CommandSpec spec;

	private volatile Thread snapshotInstall;

	@Override
	public void run() {
		try {
			PreviewSetup.validate();
			GridnodeSetup.validate();
			LedgerSetup.validate();
		} catch (IllegalArgumentException ex) {
			throw new ParameterException(spec.commandLine(), ex.getMessage(), ex);
		}

		final Path dataDirectory = ApplicationDirectory.create().getUserDataDir();

		try {
			SporkDatabaseLock.holdWhileRunning(dataDirectory, ExclusiveLock.SYSTEM);
		} catch (FileLockedException ex) {
			throw new ParameterException(spec.commandLine(), StringUtils.capitalize(ex.getMessage()), ex);
		} catch (IOException ex) {
			/* The file system names the file, not the reason, or nothing at all */
			throw new ParameterException(spec.commandLine(),
				RestClientCommand.describe("Cannot lock the spork database in", dataDirectory, ex), ex);
		}

		super.run();
	}

	/*
	   Handles what a command lets out as it runs. The container wraps what a part of the node throws as it starts in
	   exceptions of its own, hundreds of lines of trace in all, so the operator is shown one line: the server that
	   cannot listen where it was told to, or else what failed first. The trace goes to the log, which the first -v
	   shows. What any other command lets out is thrown on, and so shown with its trace.
	*/
	public static int reportFailedStart(Exception ex, CommandLine command, ParseResult parsed) throws Exception {
		if (!(command.getCommand() instanceof Daemon)) {
			throw ex;
		}

		command.getErr().println(command.getColorScheme().errorText(describe(ex)));
		log.atError().setCause(ex).log("The node failed to start");

		return command.getCommandSpec().exitCodeOnExecutionException();
	}

	/* A message of the container spans lines, and the line shown is one */
	private static String describe(Exception ex) {
		final String failure = ExceptionUtils.getThrowableList(ex).stream().filter(ListenException.class::isInstance)
			.findFirst().map(Throwable::getMessage).orElseGet(() -> ExceptionUtils.getRootCauseMessage(ex));

		return StringUtils.normalizeSpace(TerminalText.printable(failure));
	}

	/*
	   The node serves everything else while the snapshot downloads, so startup never waits for it. It does wait
	   for the spork database, which the first lookup reads: the install judges a snapshot by the board keys that
	   the node holds, and the changes to them that the release ships and that the database stores count from that
	   read on. A database that cannot be read is moved aside by the lookup, and the node starts with an empty one.
	*/
	@Override
	@SneakyThrows
	protected void start(@Observes ContainerInitialized event) {
		final URL source = BootstrapFetch.defaultUrl(Version.getVersionNumber());

		sporkDatabase.get();

		snapshotInstall = Thread.ofVirtual().name("snapshot-install")
			.start(() -> snapshotInstaller.installUntilInstalled(source));
	}

	/* A download, or the pause before the next one, does not outlast the node */
	@PreDestroy
	void destroy() {
		Optional.ofNullable(snapshotInstall).ifPresent(Thread::interrupt);
	}
}
