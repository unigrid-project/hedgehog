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

import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.Cleanup;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.ProposedKeyChange;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;

/* The change file is what board members pass between them, so a write that the disk cannot finish leaves neither a
   half of it where it was to be written nor a file beside it */
public class ChangeFileTest {
	private static final int BLOCK_SIZE = 256;
	private static final ObjectWriter PRETTY = new JsonConfiguration().getContext(ProposedKeyChange.class)
		.writerWithDefaultPrettyPrinter();

	private static CommandSpec spec() {
		return new CommandLine(CommandSpec.create()).getCommandSpec();
	}

	/* A change from key 1 and 2 and the keys that approved it */
	private static ProposedKeyChange proposed(Collection<Signature> approvers) {
		return new ProposedKeyChange(new ArrayList<>(List.of(publicKeysOf(keys(0, 1)))),
			change(rootOf(keys(0, 1)), List.of(), keys(1, 2), approvers));
	}

	@SneakyThrows
	private static int blocksOf(ProposedKeyChange proposed) {
		return Math.ceilDiv(PRETTY.writeValueAsBytes(proposed).length, BLOCK_SIZE);
	}

	/* A disk of the blocks given, with the directory /data to write the change file into */
	@SneakyThrows
	private static FileSystem diskOf(int blocks) {
		final FileSystem fs = Jimfs.newFileSystem(Configuration.unix().toBuilder().setBlockSize(BLOCK_SIZE)
			.setMaxSize((long) blocks * BLOCK_SIZE).build());

		Files.createDirectories(fs.getPath("/data"));
		return fs;
	}

	@SneakyThrows
	private static List<String> entriesOf(Path directory) {
		try (var entries = Files.list(directory)) {
			return entries.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	/* A file that stays, cut short, is one that the next run refuses to overwrite, and that is passed on cut */
	@Example
	@SneakyThrows
	public void shouldLeaveNoFileBehindWhenTheDiskFillsUpDuringTheWriteOfANewChange() {
		final ProposedKeyChange proposed = proposed(keys(0, 1, 2));
		@Cleanup final FileSystem fs = diskOf(blocksOf(proposed) - 1);

		try {
			ChangeFile.writeNew(spec(), fs.getPath("/data/change.json"), proposed);
			throw new AssertionError("A change that did not fit on the disk was reported as written");
		} catch (ParameterException expected) {
			assertThat(expected.getMessage(), containsString("Cannot write the change /data/change.json"));
			assertThat(entriesOf(fs.getPath("/data")), equalTo(List.of()));
		}
	}

	@Example
	@SneakyThrows
	public void shouldWriteANewChangeThatFitsOnTheDisk() {
		final ProposedKeyChange proposed = proposed(keys(0, 1, 2));
		@Cleanup final FileSystem fs = diskOf(blocksOf(proposed));
		final Path file = fs.getPath("/data/change.json");

		ChangeFile.writeNew(spec(), file, proposed);

		assertThat(Files.readAllBytes(file), equalTo(PRETTY.writeValueAsBytes(proposed)));
	}

	/* The change file is replaced in one step, so what was there stays whole when the step cannot be made */
	@Example
	@SneakyThrows
	public void shouldKeepTheChangeFileAndLeaveNoTemporaryFileWhenTheDiskFillsUpDuringTheReplace() {
		final ProposedKeyChange earlier = proposed(keys(0));
		final ProposedKeyChange approved = proposed(keys(0, 1, 2));
		@Cleanup final FileSystem fs = diskOf(blocksOf(approved));
		final Path file = fs.getPath("/data/change.json");

		Files.write(file, PRETTY.writeValueAsBytes(earlier));

		try {
			ChangeFile.replace(spec(), file, approved);
			throw new AssertionError("A change that did not fit on the disk was reported as written");
		} catch (ParameterException expected) {
			assertThat(expected.getMessage(), containsString("Cannot write the change /data/change.json"));
			assertThat(Files.readAllBytes(file), equalTo(PRETTY.writeValueAsBytes(earlier)));
			assertThat(entriesOf(fs.getPath("/data")), equalTo(List.of("change.json")));
		}
	}

	@Example
	@SneakyThrows
	public void shouldReplaceTheChangeFileWhenThereIsRoomForBoth() {
		final ProposedKeyChange approved = proposed(keys(0, 1, 2));
		@Cleanup final FileSystem fs = diskOf(2 * blocksOf(approved));
		final Path file = fs.getPath("/data/change.json");

		Files.write(file, PRETTY.writeValueAsBytes(proposed(keys(0))));
		ChangeFile.replace(spec(), file, approved);

		assertThat(Files.readAllBytes(file), equalTo(PRETTY.writeValueAsBytes(approved)));
		assertThat(entriesOf(fs.getPath("/data")), equalTo(List.of("change.json")));
	}
}
