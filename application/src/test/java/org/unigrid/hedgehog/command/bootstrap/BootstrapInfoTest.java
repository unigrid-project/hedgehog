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
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import java.io.IOException;
import java.nio.file.Path;
import mockit.Mock;
import mockit.MockUp;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.model.bootstrap.SnapshotInfo;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;

public class BootstrapInfoTest {
	private void readerAnswers(SnapshotInfo info, IOException problem) {
		final SnapshotReader reader = BootstrapCli.withoutConstructor(SnapshotReader.class);

		new MockUp<SnapshotReader>() {
			@Mock public /* static */ SnapshotReader open(Path path) throws IOException {
				if (problem != null) {
					throw problem;
				}

				return reader;
			}

			@Mock public SnapshotInfo getInfo() {
				return info;
			}
		};
	}

	@Provide
	public Arbitrary<SnapshotInfo> provideInfo() {
		return BootstrapArbitraries.infos();
	}

	@BeforeTry
	public void beforeTry() {
		BootstrapCli.snapshotInMemory();
	}

	@Property(tries = 50)
	public void shouldPrintEveryFieldOfTheSnapshot(@ForAll("provideInfo") SnapshotInfo info) {
		readerAnswers(info, null);

		final HedgehogCli.Result result = BootstrapCli.run("info");

		final List<Object> fields = List.of(info.getTipHash(), info.getTipHeight(), info.getAddressCount(),
			info.getEntryCount(), info.getTransactionCount(), info.getTotalUnspent(), info.getZerocoinMinted(),
			info.getBuilt(), info.getSignature()
		);

		final List<String> lines = result.out().lines().toList();

		assertThat(result.exitCode(), equalTo(0));
		assertThat(lines, hasSize(fields.size()));

		for (int i = 0; i < fields.size(); i++) {
			assertThat(lines.get(i), endsWith(" " + fields.get(i)));
		}
	}

	@Example
	public void shouldRefuseAnUnreadableSnapshotWithoutAStackTrace() {
		readerAnswers(null, new IOException("does not verify against any trusted key"));

		final HedgehogCli.Result result = BootstrapCli.run("info");

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("does not verify against any trusted key"));
		result.assertNoStackTrace();
	}
}
