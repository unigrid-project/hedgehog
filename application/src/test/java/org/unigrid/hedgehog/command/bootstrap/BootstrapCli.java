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

package org.unigrid.hedgehog.command.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import mockit.Mock;
import mockit.MockUp;
import org.unigrid.hedgehog.Hedgehog;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.command.option.SnapshotOptions;
import picocli.CommandLine;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BootstrapCli {
	private static final Objenesis OBJENESIS = new ObjenesisStd();

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
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final ByteArrayOutputStream err = new ByteArrayOutputStream();
		final PrintStream originalOut = System.out;
		final PrintStream originalErr = System.err;
		final int exitCode;

		System.setOut(new PrintStream(out));
		System.setErr(new PrintStream(err));

		try {
			exitCode = new CommandLine(Hedgehog.class).execute(Stream.concat(Stream.of("bootstrap"),
				Stream.of(args)).toArray(String[]::new)
			);
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}

		return new Result(exitCode, out.toString(), err.toString());
	}

	/* A stand-in for classes whose constructor needs a real snapshot; a MockUp answers its methods */
	public static <T> T withoutConstructor(Class<T> type) {
		return OBJENESIS.newInstance(type);
	}

	/* The commands only ever see this path, so nothing they check or write reaches the real disk */
	public static Path snapshotInMemory() {
		final Path snapshot = Jimfs.newFileSystem(Configuration.unix()).getPath("/data/bootstrap.dat");

		new MockUp<SnapshotOptions>() {
			@Mock public /* static */ Path getSnapshot() {
				return snapshot;
			}

			@Mock public /* static */ Path defaultSnapshot() {
				return snapshot;
			}
		};

		return snapshot;
	}
}
