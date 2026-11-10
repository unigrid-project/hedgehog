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

package org.unigrid.hedgehog.model.spork;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.math.BigDecimal;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;

/* The database is saved by a thread of its own while event loops store the sporks that peers sent, so a file that a save
   leaves has to load whatever is stored meanwhile, and has to hold each spork as one version of it. A version of a
   spork carries its number as its time stamp and in every amount it holds, so that a spork which was written while it
   changed shows as amounts of several numbers. */
public class PersistRaceTest {
	private static final int ROUNDS = 3000;
	private static final int VERSIONS = 16;
	private static final int MINTS = 500;
	private static final List<Type> TYPES = List.of(Type.MINT_STORAGE, Type.MINT_SUPPLY, Type.LEDGER_FEE,
		Type.COMMUNICATION);

	private final SporkDatabase database = SporkDatabase.builder().build();

	private static MintStorage.SporkData mintsOf(int number) {
		final Map<Location, BigDecimal> mints = new HashMap<>();
		final MintStorage.SporkData data = new MintStorage.SporkData();

		for (int height = 0; height < MINTS; height++) {
			mints.put(new Location(new Address(String.format("A%039d", height)), height), BigDecimal.valueOf(number));
		}

		data.setMints(mints);
		return data;
	}

	private static ChunkData dataOf(Type type, int number) {
		switch (type) {
			case MINT_STORAGE:
				return mintsOf(number);

			case MINT_SUPPLY: {
				final MintSupply.SporkData data = new MintSupply.SporkData();

				data.setMaxSupply(BigDecimal.valueOf(number));
				return data;
			}

			case LEDGER_FEE: {
				final LedgerFee.SporkData data = new LedgerFee.SporkData();

				data.setMinimumTransferFee(BigDecimal.valueOf(number));
				return data;
			}

			default: {
				final CommunicationSpork.SporkData data = new CommunicationSpork.SporkData();

				data.setGroupSize(number);
				return data;
			}
		}
	}

	private static Set<Long> numbersIn(GridSpork spork) {
		switch (spork.getType()) {
			case MINT_STORAGE:
				return spork.<MintStorage.SporkData>getData().getMints().values().stream()
					.map(BigDecimal::longValueExact).collect(Collectors.toSet());

			case MINT_SUPPLY:
				return Set.of(spork.<MintSupply.SporkData>getData().getMaxSupply().longValueExact());

			case LEDGER_FEE:
				return Set.of(spork.<LedgerFee.SporkData>getData().getMinimumTransferFee().longValueExact());

			default:
				return Set.of((long) spork.<CommunicationSpork.SporkData>getData().getGroupSize());
		}
	}

	private static GridSpork version(Type type, int number) {
		final GridSpork spork = GridSpork.nextVersion(type, null, Optional.empty());

		spork.setTimeStamp(Instant.ofEpochMilli(number));
		spork.setData(dataOf(type, number));
		return spork;
	}

	/* Stores one version after the other of a type, over what is stored, as a peer that sends them does */
	private Future<?> storing(ExecutorService executor, Type type, AtomicBoolean isRunning) {
		final List<GridSpork> versions = IntStream.rangeClosed(1, VERSIONS).mapToObj(number -> version(type, number))
			.toList();

		return executor.submit(() -> {
			for (int next = 0; isRunning.get(); next = (next + 1) % VERSIONS) {
				database.replace(database.get(type), versions.get(next));
			}
		});
	}

	private static void assertWhole(SporkDatabase loaded, int round) {
		for (Type type : TYPES) {
			final GridSpork spork = loaded.get(type);

			assertThat("a " + type + " spork in the file of round " + round, spork.getType(), is(type));
			assertThat("a " + type + " spork in the file of round " + round, loaded.holdsAnotherSporkIn(type),
				is(false));
			assertThat("the amounts of the " + type + " spork in the file of round " + round, numbersIn(spork),
				equalTo(Set.of(spork.getTimeStamp().toEpochMilli())));
		}
	}

	@Example
	@SneakyThrows
	public void shouldLeaveAFileThatLoadsWhileSporksAreStoredOnOtherThreads() {
		final ExecutorService executor = Executors.newFixedThreadPool(TYPES.size());
		final AtomicBoolean isRunning = new AtomicBoolean(true);

		TYPES.forEach(type -> database.set(version(type, 1)));

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = Files.createDirectories(fs.getPath("/data")).resolve(SporkDatabase.SPORK_DB_FILE);
			final List<Future<?>> storing = TYPES.stream().<Future<?>>map(type -> storing(executor, type, isRunning))
				.toList();

			try {
				for (int round = 0; round < ROUNDS; round++) {
					SporkDatabase.persist(file, database);
					assertWhole(SporkDatabase.load(file), round);
				}
			} finally {
				isRunning.set(false);
			}

			for (Future<?> stored : storing) {
				try {
					stored.get(30, TimeUnit.SECONDS);
				} catch (ExecutionException ex) {
					throw ex.getCause();
				}
			}
		} finally {
			executor.shutdownNow();
		}
	}
}
