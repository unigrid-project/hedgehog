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

package org.unigrid.hedgehog.command.util;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.option.BoardKeyOptions;
import org.unigrid.hedgehog.command.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.BoardKeysRecordFixture;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import picocli.CommandLine;

/* A spork database file can hold a spork of the board keys with no chain at all, which no node writes and which the
   commands that read the chain of a file take for a file they cannot read, as they take any other file of a spork that
   is not whole. The release builds in keys 0 and 1. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysWithoutAChainTest {
	private static final String BOARD = "--network-keys=" + key(0).getPublicKey() + "," + key(1).getPublicKey();

	@BeforeTry
	public void before() {
		new MockUp<BoardKeyOptions>() {
			@Mock String environmentKey() {
				return null;
			}
		};

		OptionsCli.parse(List.of("util"), BOARD);
		BoardKeysRecordFixture.inMemory();
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private static HedgehogCli.Result run(FileSystem fs, String... args) {
		final List<String> arguments = new ArrayList<>(List.of("util"));

		arguments.addAll(Arrays.asList(args));
		arguments.add(BOARD);
		NetworkKey.reset();

		return HedgehogCli.run(() -> new CommandLine(Hedgehog.class).registerConverter(Path.class, fs::getPath)
			.setParameterExceptionHandler(BoardKeyOptions::reportWithoutKeys), arguments.toArray(String[]::new));
	}

	@SneakyThrows
	private static Path withoutAChain(FileSystem fs, BoardKeys.SporkData data) {
		final BoardKeys boardKeys = new BoardKeys();
		final SporkDatabase database = SporkDatabase.builder().build();
		final Path file = fs.getPath("/spork.db");

		boardKeys.setData(data);
		database.set(boardKeys);
		SporkDatabase.persist(file, database);
		return file;
	}

	@Example
	@SneakyThrows
	public void readsNoFileOfBoardKeysWithoutAChain() {
		final BoardKeys.SporkData noChanges = new BoardKeys.SporkData();
		final BoardKeys.SporkData aMissingChange = new BoardKeys.SporkData();

		noChanges.setChanges(null);
		aMissingChange.getChanges().add(null);

		for (BoardKeys.SporkData data : Arrays.asList(null, noChanges, aMissingChange)) {
			try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
				final String file = withoutAChain(fs, data).toString();

				for (HedgehogCli.Result result : List.of(run(fs, "spork-db", "sign", file, "--output", "/signed.db",
					"-k", key(0).getPrivateKey()), run(fs, "board-keys", "apply", file, "--change", "/change.json",
					"--output", "/changed.db"), run(fs, "board-keys", "propose", "--activate", key(2).getPublicKey(),
					"--spork-db", file, "--output", "/change.json"), run(fs, "spork-db", "validate", file))) {

					assertThat(result.err(), result.exitCode(), equalTo(2));
					assertThat(result.err(), containsString("Cannot read the spork database " + file));
					assertThat(result.err(), not(containsString("Exception")));
				}
			}
		}
	}

	/* What a node holds was read from the wire, which always carries a chain, so a version without one follows on
	   nothing and nothing follows on it */
	@Example
	public void shouldTakeABoardKeysSporkWithoutAChainForNoChain() {
		final BoardKeys whole = new BoardKeys();
		final BoardKeys noChanges = new BoardKeys();

		noChanges.<BoardKeys.SporkData>getData().setChanges(null);
		assertThat(whole.holdsTheChainOf(noChanges), is(false));
		assertThat(noChanges.holdsTheChainOf(whole), is(false));
		assertThat(noChanges.followsOn(whole), is(false));
		assertThat(whole.followsOn(noChanges), is(false));
	}
}
