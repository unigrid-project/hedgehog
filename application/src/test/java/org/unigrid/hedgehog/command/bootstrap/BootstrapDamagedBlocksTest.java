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
import static org.hamcrest.Matchers.equalTo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.command.ExitCode;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.model.bootstrap.SyntheticBlocks;

/* The import runs here on block files as they are, and nothing but the path of the snapshot is faked. A record is
   taken for a block once it starts with the network magic, so what is wrong with it is first found out while it is
   read, by whatever the reading runs into. */
public class BootstrapDamagedBlocksTest {
	private Path snapshot;

	@BeforeTry
	public void beforeTry() {
		snapshot = BootstrapCli.snapshotInMemory();
	}

	@Example
	@SneakyThrows
	public void shouldSayInOneLineWhichBlockIsDamaged() {
		final Path blocks = SyntheticBlocks.store(builder -> builder.record(new byte[8])).getPaths().get(0);
		final HedgehogCli.Result result = BootstrapCli.run("import", "-b", blocks.getParent().toString());

		result.assertNoStackTrace();
		assertThat(result.err().lines().toList(),
			equalTo(List.of("The block at offset 8 of " + blocks + " is damaged and cannot be read")));
		assertThat(result.exitCode(), equalTo(ExitCode.FAILED));
		assertThat(Files.exists(snapshot), equalTo(false));
	}
}
