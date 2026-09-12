/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import lombok.SneakyThrows;
import net.jqwik.api.Example;

public class SnapshotReproducibilityTest {
	/* The 31-block fixture's tip is block 30, mined 2018-09-20T10:59:49Z. */
	private static final long FIXTURE_TIP_TIME = 1537441189L;

	@Example
	@SneakyThrows
	public void shouldProduceTheSameBytesEveryTime() {
		final Path blocks = BlockFixture.directory();
		final Path first = Files.createTempFile("hhg-first-", ".dat");
		final Path second = Files.createTempFile("hhg-second-", ".dat");

		first.toFile().deleteOnExit();
		second.toFile().deleteOnExit();
		SnapshotBuilder.build(blocks, first);
		SnapshotBuilder.build(blocks, second);

		assertThat(Files.mismatch(first, second), equalTo(-1L));
	}

	@Example
	@SneakyThrows
	public void shouldDateTheSnapshotByItsTipBlock() {
		final Path snapshot = Files.createTempFile("hhg-dated-", ".dat");

		snapshot.toFile().deleteOnExit();
		SnapshotBuilder.build(BlockFixture.directory(), snapshot);

		final SnapshotInfo info = SnapshotReader.open(snapshot).getInfo();

		assertThat(info.getBuilt(), equalTo(Instant.ofEpochSecond(FIXTURE_TIP_TIME)));
	}
}
