/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog;

import java.io.OutputStream;
import java.io.PrintStream;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.geronimo.arthur.api.RegisterClass;
import org.apache.geronimo.arthur.api.RegisterClasses;
import org.apache.geronimo.arthur.api.RegisterClasses.Entry;
import org.jboss.weld.bootstrap.api.helpers.RegistrySingletonProvider;
import org.unigrid.hedgehog.command.Daemon;
import org.unigrid.hedgehog.command.CLI;
import org.unigrid.hedgehog.command.Util;
import org.unigrid.hedgehog.model.VersionProvider;
import org.unigrid.hedgehog.model.util.ApplicationLogLevel;
import org.unigrid.hedgehog.model.util.Reflection;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "hedgehog", mixinStandardHelpOptions = true, versionProvider = VersionProvider.class,
	scope = CommandLine.ScopeType.INHERIT, header = {
		"",
		"     .:.:.:.:.:.:.:.               ${HEDGEHOG_VERSION_PAD}${HEDGEHOG_VERSION}",
		"    :   _.:.:.:.:.::.     © 2021-2022 The Unigrid Foundation, UGD Software AB",
		"   /  0  .:.:.:.:.:::                         (A segmented blocktree network)",
		"  o____._:.oO:.:.oO:'                         Under an addended AGPL3 license",
		""
	}, subcommands = { CLI.class, Daemon.class, Util.class }
)
@RegisterClasses({
	@Entry(clazz = RegistrySingletonProvider.class, registration = @RegisterClass(all = true)),
	@Entry(clazz = org.jboss.weld.logging.VersionLogger.class, registration = @RegisterClass(all = true))
})
public class Hedgehog {
	@Getter
	private static boolean[] verbose;

	@Option(names = { "-v", "--verbose" }, scope = CommandLine.ScopeType.INHERIT,
		description = "Verbose mode. Multiple options increase verbosity."
	)
	public void setVerbose(boolean[] verbose) {
		Hedgehog.verbose = verbose.clone();
		ApplicationLogLevel.configure(verbose.length);
	}

	@SneakyThrows
	public static void main(String[] args) {
		final PrintStream stdout = System.out;
		System.setOut(new PrintStream(OutputStream.nullOutputStream()));

		Reflection.resetIllegalAccessLogger(); /* Try to get rid of the "illegal reflective access..." nags */
		ApplicationLogLevel.configure(0); /* Start quiet, if any -v are defined, the setter above is called */

		System.setOut(stdout);
		System.exit(new CommandLine(Hedgehog.class).execute(args));
	}
}
