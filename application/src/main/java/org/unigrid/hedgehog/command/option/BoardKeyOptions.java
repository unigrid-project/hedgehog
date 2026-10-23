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

package org.unigrid.hedgehog.command.option;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.model.util.OwnerOnlyFile;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import picocli.CommandLine.UnmatchedArgumentException;

/* The private board key that signs a spork on this machine and never leaves it. The operator gives it in one of three
   ways: on the command line, where other users of the machine can read it in the list of processes, in the
   environment, or in a file that only its owner can read. Exactly one of them has to give it, as a key taken from a
   source the operator forgot about would sign what they did not mean it to. No message repeats the key. */
public class BoardKeyOptions {
	public static final String ENVIRONMENT = "HEDGEHOG_BOARD_KEY";

	/* The hex of a key, a line ending and room for stray blanks: a bigger file holds no key */
	private static final int MAX_FILE_BYTES = 260;

	private static final Pattern KEY = Pattern.compile("(?<![0-9a-fA-F])[0-9a-fA-F]{130,132}(?![0-9a-fA-F])");

	@Option(names = { "-k", "--key" }, scope = CommandLine.ScopeType.INHERIT, paramLabel = "<hex>",
		description = "The private board key in hex. Other users of this machine can read it in the list of "
			+ "processes, so prefer --key-file or $" + ENVIRONMENT + "."
	)
	private String key;

	@Option(names = "--key-file", scope = CommandLine.ScopeType.INHERIT, paramLabel = "<path>",
		description = "A file that holds the private board key in hex and that only its owner can read."
	)
	private String keyFile;

	@Spec(Spec.Target.MIXEE)
	private CommandSpec mixee;

	private final FileSystem fileSystem;

	public BoardKeyOptions() {
		this(FileSystems.getDefault());
	}

	BoardKeyOptions(FileSystem fileSystem) {
		this.fileSystem = fileSystem;
	}

	/* Read on its own, so that a test can stand in for the environment */
	static String environmentKey() {
		return System.getenv(ENVIRONMENT);
	}

	/**
	* The board key, from the one source that gives it, once {@code isBoardKey} knows it as one of the current board
	* keys of the network: a wrong key fails before the daemon is asked anything. Anything else is refused with a
	* ParameterException, which the command line reports with its usage.
	*/
	public String resolve(Predicate<String> isBoardKey) {
		final String environment = environmentKey();
		final List<String> sources = sourcesOf(environment);

		if (sources.isEmpty()) {
			throw refused("A board key is needed: give it with --key-file, $" + ENVIRONMENT + " or -k");
		}

		if (sources.size() > 1) {
			throw refused("The board key is given by " + String.join(" and ", sources)
				+ "; give it in one way only");
		}

		final String hex = keyOf(environment);

		if (!isBoardKey.test(hex)) {
			throw refused("The board key is not one of the current board keys of the network "
				+ "(see --network-keys)");
		}

		return hex;
	}

	private List<String> sourcesOf(String environment) {
		final List<String> sources = new ArrayList<>();

		if (Objects.nonNull(key)) {
			sources.add("-k");
		}

		if (Objects.nonNull(keyFile)) {
			sources.add("--key-file");
		}

		if (StringUtils.isNotEmpty(environment)) {
			sources.add("$" + ENVIRONMENT);
		}

		return sources;
	}

	private String keyOf(String environment) {
		if (Objects.nonNull(key)) {
			return key;
		}

		return Objects.isNull(keyFile) ? environment : read(pathOf(keyFile));
	}

	/* Every answer about the file names it, so a key given as its name by mistake is refused before any of them */
	private String pathOf(String name) {
		if (KEY.matcher(name).matches()) {
			throw refused("--key-file takes the path of a file that holds the board key, not the key; give "
				+ "a key with -k or $" + ENVIRONMENT);
		}

		return name;
	}

	/* A file that is there and is no regular file, a pipe or a device, could be read for ever; one that is not there
	   is left to the read, which says so */
	private String read(String name) {
		try {
			final Path file = fileSystem.getPath(name);

			if (Files.exists(file) && !Files.isRegularFile(file)) {
				throw refused(name + " must be a regular file that holds the board key");
			}

			OwnerOnlyFile.require(file);
			return readBounded(file).strip();
		} catch (NoSuchFileException ex) {
			throw refused("There is no board key file " + name);
		} catch (IOException ex) {
			throw refused("Cannot read the board key file " + name + ": " + ex.getMessage());
		} catch (IllegalArgumentException ex) {
			throw refused(ex.getMessage());
		}
	}

	/* The size a file claims cannot be trusted (a device says 0), so what is read is bounded instead */
	private String readBounded(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			final byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);

			if (bytes.length > MAX_FILE_BYTES) {
				throw refused(file + " holds more than a board key");
			}

			return new String(bytes, StandardCharsets.US_ASCII);
		}
	}

	private ParameterException refused(String reason) {
		return new ParameterException(mixee.commandLine(), reason);
	}

	/**
	* Reports a mistake on the command line as picocli does, with every argument that looks like a key left out of
	* it: picocli repeats an argument it cannot place, and a key given in the wrong place would end up on the
	* terminal and in its scrollback.
	*/
	public static int reportWithoutKeys(ParameterException ex, String[] arguments) {
		final CommandLine command = ex.getCommandLine();
		final PrintWriter err = command.getErr();

		err.println(command.getColorScheme().errorText(KEY.matcher(String.valueOf(ex.getMessage()))
			.replaceAll("<key>")));

		if (!UnmatchedArgumentException.printSuggestions(ex, err)) {
			command.usage(err, command.getColorScheme());
		}

		return command.getCommandSpec().exitCodeOnInvalidInput();
	}
}
