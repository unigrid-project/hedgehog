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

package org.unigrid.hedgehog.service.ledger;

import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.command.option.OptionsCli;
import org.unigrid.hedgehog.command.option.SnapshotOptions;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.LedgerGenesis;
import org.unigrid.hedgehog.ledger.ValidatorSet;
import org.unigrid.hedgehog.model.bootstrap.AddressSnapshots;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDigest;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;

/* The service as the container makes it, in a node that was told to run a ledger: the genesis is a file, the
   snapshot the one the node keeps in its data directory, and the gateway the one of the container */
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerServiceCdiTest extends BaseMockedWeldTest {
	@Inject private LedgerService service;

	@Example
	@SneakyThrows
	public void shouldRunOnTheCollaboratorsTheContainerInjectsOnceTheSnapshotIsInTheDataDirectory() {
		final Path data = ApplicationDirectory.create().getUserDataDir();
		final Path snapshot = AddressSnapshots.write(Map.of("11".repeat(20), 5_000L));
		final byte[] hash = SnapshotDigest.of(snapshot);
		final long total = SnapshotReader.open(snapshot).totalBalance();
		final Path genesisFile = data.resolve("ledger-genesis.json");
		final LedgerGenesis genesis = new LedgerGenesis(System.currentTimeMillis() - 60_000, 1_000,
			new ValidatorSet(List.of(Ed25519.publicKey(LedgerTestChain.seed(7)))), hash, total);

		GenesisFile.write(genesisFile, genesis);
		Files.move(snapshot, data.resolve(SnapshotOptions.SNAPSHOT_FILE));
		OptionsCli.parse(List.of("daemon"), "--ledger", "--ledger-genesis=" + genesisFile);

		await().atMost(Duration.ofSeconds(60)).until(() -> service.status().running()
			&& service.height().isPresent());

		assertThat(service.height(), equalTo(OptionalLong.of(0)));
		assertThat(service.supply(), equalTo(OptionalLong.of(total)));
		assertThat(Files.exists(data.resolve("ledger").resolve(LedgerService.chainDirectoryOf(genesis))
			.resolve(LedgerNodes.BLOCK_LOG)), is(true));
		assertThat(service.status().signing(), is(false));
		assertThat(service.status().tipHash().length(), is(128));
	}
}
