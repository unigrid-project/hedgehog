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

package org.unigrid.hedgehog.command.cli;

import jakarta.inject.Inject;
import java.lang.reflect.Constructor;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.ExitCode;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.cli.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.InMemoryDataDirectoryMockUp;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.option.BoardKeyOptions;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.model.option.RestOptions;
import org.unigrid.hedgehog.model.spork.BoardKeysRecordFixture;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;
import org.unigrid.hedgehog.model.storage.Fingerprint;
import org.unigrid.hedgehog.server.BaseDefaultNodeTest;
import org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures;
import picocli.CommandLine;

/* The commands of the parts that this release leaves off, which the help leaves out, run through the command line
   against a node started without the preview, with the token and a board key of the node. Each one fails without a
   stack trace and prints nothing that reads as a result, and the node holds, notes and builds nothing for it. The
   files that the commands read and write are of a file system in memory. */
public class PreviewCommandDefaultNodeTest extends BaseDefaultNodeTest {
	private static final List<Type> OF_THE_PREVIEW = List.of(Type.STORAGE, Type.LEDGER_BLACKLIST, Type.LEDGER_FEE,
		Type.COMMUNICATION);

	private static final String STORAGE_LEFT_OFF = "This release does not include the storage part";
	private static final String GRIDNODE_LEFT_OFF = "This release does not include the gridnode part";
	private static final String GRIDNODES_LEFT_OFF = "Gridnodes are not part of this release";
	private static final String FIRST_VERSION = "--first-version";

	private static final String KEY = SporkCommands.PROPOSER.getPrivateKey();
	private static final String FINGERPRINT = Fingerprint.generate(new SecureRandom()).encode();
	private static final String ACCOUNT = "ab".repeat(32);
	private static final String LEGACY = LegacyAddress.encode(new byte[20]);
	private static final String SIGNATURE = Base64.getEncoder().encodeToString(new byte[65]);
	private static final String UPLOAD = "/files/upload";
	private static final String BLACKLIST = "/files/blacklist.txt";
	private static final String OUTPUT = "/out";

	/* The subcommand of each spork of the parts, with data that a node which takes the spork accepts */
	private static final Map<String, String> SPORK_DATA = Map.of("storage", "{\"manifestCopies\":5}",
		"ledger-blacklist", BLACKLIST, "ledger-fee", "0.001", "communication", "{\"groupSize\":3}");

	@Inject private SporkDatabase database;
	@Inject private PendingSporks pendingSporks;
	@Inject private SporkLag lag;

	private FileSystem files;

	/* A command by the words that name it below cli, the arguments it is run with, and what it says of the release
	   where it says more than that it failed */
	private record Hidden(String command, List<String> arguments, Optional<String> reason) {
		static Hidden saying(String reason, String command, String... arguments) {
			return new Hidden(command, List.of(arguments), Optional.of(reason));
		}

		static Hidden failing(String command, String... arguments) {
			return new Hidden(command, List.of(arguments), Optional.empty());
		}

		String[] words() {
			return Stream.concat(Stream.of(command.split(" ")), arguments.stream()).toArray(String[]::new);
		}

		/* A first version is shown before it is signed and handed to the node, which then refuses it */
		boolean showsWhatItSigns() {
			return arguments.contains(FIRST_VERSION);
		}

		@Override
		public String toString() {
			return String.join(" ", words()).replace(KEY, "<key>");
		}
	}

	private static List<Hidden> hidden() {
		final List<Hidden> hidden = new ArrayList<>(List.of(
			Hidden.saying(STORAGE_LEFT_OFF, "storage-put", UPLOAD),
			Hidden.saying(STORAGE_LEFT_OFF, "storage-get", "-f", FINGERPRINT, "-o", OUTPUT + "/file"),
			Hidden.saying(STORAGE_LEFT_OFF, "storage-delete", "-f", FINGERPRINT),
			Hidden.saying(GRIDNODE_LEFT_OFF, "gridnode-list"),
			Hidden.saying(GRIDNODES_LEFT_OFF, "gridnode-start"),
			Hidden.saying(GRIDNODES_LEFT_OFF, "gridnode-stop"),
			Hidden.failing("ledger info"),
			Hidden.failing("ledger balance", ACCOUNT),
			Hidden.failing("ledger block", "1"),
			Hidden.failing("ledger transfer", "--key", LedgerKeyFixtures.KEY_FILE, "--to", ACCOUNT, "--amount", "1"),
			Hidden.failing("ledger claim", "--address", LEGACY, "--to", ACCOUNT),
			Hidden.failing("ledger claim", "--address", LEGACY, "--to", ACCOUNT, "--signature", SIGNATURE),
			Hidden.failing("ledger vote", "--key", LedgerKeyFixtures.KEY_FILE, "--candidate", ACCOUNT, "--add")
		));

		SPORK_DATA.forEach((name, data) -> {
			final String type = name.toUpperCase(Locale.ROOT).replace('-', '_');

			hidden.add(Hidden.saying("This release does not take the " + type + " spork", "gridspork-get " + name));
			hidden.add(Hidden.failing("gridspork-set " + name, "-D", data, "-k", KEY));
			hidden.add(Hidden.saying("This node takes no " + type + " spork", "gridspork-set " + name, "-D", data,
				"-k", KEY, FIRST_VERSION));
		});

		return hidden;
	}

	@BeforeContainer
	private static void holdTheBoard() {
		new InMemoryDataDirectoryMockUp();
		SporkCommands.fakeTheBoard();
		BoardKeysRecordFixture.inMemory();
	}

	@BeforeTry
	@SneakyThrows
	public void makeTheFiles() {
		files = LedgerKeyFixtures.posixFileSystem();
		Files.createDirectories(files.getPath("/files"));
		Files.createDirectories(files.getPath(OUTPUT));
		Files.write(files.getPath(UPLOAD), new byte[] { 1, 2, 3, 4 });
		Files.writeString(files.getPath(BLACKLIST), LEGACY + "\n");
		LedgerKeyFixtures.writeSeed(files, new byte[32]);
	}

	@AfterTry
	@SneakyThrows
	public void dropTheFiles() {
		files.close();
	}

	/* The blacklist is read from the file system of the test, not from the disk */
	private CommandLine.IFactory readingFromTheFiles() {
		return new CommandLine.IFactory() {
			@Override
			public <K> K create(Class<K> type) throws Exception {
				if (type == LedgerBlacklist.class) {
					final Constructor<LedgerBlacklist> constructor = LedgerBlacklist.class
						.getDeclaredConstructor(FileSystem.class);

					constructor.setAccessible(true);
					return type.cast(constructor.newInstance(files));
				}

				return CommandLine.defaultFactory().create(type);
			}
		};
	}

	/* On the address of the node of the test, which the command line is told again as the options of the node are
	   static and every parse sets them */
	private HedgehogCli.Result run(Hidden hidden) {
		final List<String> arguments = new ArrayList<>(List.of("cli", "--nethost", NetOptions.getHost(), "--netport",
			String.valueOf(NetOptions.getPort()), "--resthost", RestOptions.getHost(), "--restport",
			String.valueOf(RestOptions.getPort()), "--resttoken", RestOptions.getToken(), "--no-seeds"));

		arguments.addAll(List.of(hidden.words()));

		return HedgehogCli.run(() -> new CommandLine(Hedgehog.class, readingFromTheFiles())
			.setParameterExceptionHandler(BoardKeyOptions::reportWithoutKeys)
			.registerConverter(Path.class, files::getPath), arguments.toArray(String[]::new));
	}

	/* What is wrong with the way the command ended: it has to fail, with a line that is no stack trace and no name of
	   an exception, and with the reason where it gives one */
	private static List<String> problemsOf(Hidden hidden, HedgehogCli.Result result) {
		final List<String> problems = new ArrayList<>();
		final String error = result.err();

		if (result.exitCode() == ExitCode.OK) {
			problems.add(hidden + " exited with " + ExitCode.OK);
		}

		if (error.isBlank() || error.contains("\tat ") || error.contains("Exception")) {
			problems.add(hidden + " printed as its error: " + error);
		}

		if (!error.contains(hidden.reason().orElse(""))) {
			problems.add(hidden + " did not say \"" + hidden.reason().orElseThrow() + "\" but: " + error);
		}

		if (!hidden.showsWhatItSigns() && !result.out().isEmpty()) {
			problems.add(hidden + " printed as its result: " + result.out());
		}

		return problems;
	}

	/* What the node holds, notes or built that it would not without the command */
	private List<String> changesOfTheNodeAfter(Hidden hidden) {
		final List<String> changes = new ArrayList<>();

		final List<Type> proposed = pendingSporks.list().stream().map(GridSpork::getType).toList();

		for (Type type : OF_THE_PREVIEW) {
			if (database.get(type) != null) {
				changes.add(hidden + " left the node a " + type + " spork");
			}

			if (lag.isBehind(type, database)) {
				changes.add(hidden + " left the node behind on " + type);
			}
		}

		if (!proposed.isEmpty()) {
			changes.add(hidden + " left the node proposals of " + proposed);
		}

		if (!createdOfThePreview().isEmpty()) {
			changes.add(hidden + " left the node " + createdOfThePreview());
		}

		return changes;
	}

	@SneakyThrows
	private List<Path> writtenByTheCommands() {
		try (Stream<Path> written = Files.list(files.getPath(OUTPUT))) {
			return written.toList();
		}
	}

	/* The commands that the help of cli leaves out, and those below one that it leaves out, by the words that name
	   them */
	private static void collectHidden(CommandLine group, String words, boolean below, Set<String> hidden) {
		group.getSubcommands().forEach((name, command) -> {
			final String named = (words + " " + name).strip();
			final boolean isHidden = below || command.getCommandSpec().usageMessage().hidden();

			if (!command.getSubcommands().isEmpty()) {
				collectHidden(command, named, isHidden, hidden);
			} else if (isHidden) {
				hidden.add(named);
			}
		});
	}

	/* A command added in secret has to be run here as well */
	@Example
	public void shouldRunEveryHiddenCommandOfTheCommandLine() {
		final Set<String> hidden = new TreeSet<>();

		collectHidden(new CommandLine(Hedgehog.class).getSubcommands().get("cli"), "", false, hidden);

		assertThat(hidden().stream().map(Hidden::command).collect(Collectors.toCollection(TreeSet::new)),
			equalTo(hidden));
	}

	@Example
	public void shouldFailEveryHiddenCommandAndLeaveTheNodeAsItWas() {
		final List<String> problems = new ArrayList<>();

		for (Hidden hidden : hidden()) {
			problems.addAll(problemsOf(hidden, run(hidden)));
			problems.addAll(changesOfTheNodeAfter(hidden));
		}

		assertThat(problems, is(empty()));
		assertThat(writtenByTheCommands(), is(empty()));
		assertThat(createdOfThePreview(), is(empty()));
		assertThat(threadsOfThePreview(), is(empty()));
	}
}
