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
import static org.hamcrest.Matchers.not;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.Hedgehog;
import picocli.CommandLine;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HedgehogCli {
	/* Logback reports its own start-up on stdout, which must not end up in the output of a command */
	static {
		LoggerFactory.getILoggerFactory();
	}

	public record Result(int exitCode, String out, String err) {
		public void assertNoStackTrace() {
			assertThat(err, not(containsString("\tat ")));
		}
	}

	public static Result run(String... args) {
		return run(new CommandLine(Hedgehog.class), args);
	}

	public static Result run(CommandLine line, String... args) {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final ByteArrayOutputStream err = new ByteArrayOutputStream();
		final PrintStream originalOut = System.out;
		final PrintStream originalErr = System.err;
		final int exitCode;

		System.setOut(new PrintStream(out));
		System.setErr(new PrintStream(err));

		try {
			exitCode = line.execute(args);
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}

		return new Result(exitCode, out.toString(), err.toString());
	}
}
