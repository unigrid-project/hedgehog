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

package org.unigrid.hedgehog.model.producer;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SerializationException;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

@Slf4j
@ApplicationScoped
public class SporkDatabaseProducer {
	@Inject private ApplicationDirectory applicationDirectory;
	private AtomicReference<SporkDatabase> sporkDatabase = new AtomicReference<>(null);
	private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(task -> {
		final Thread thread = new Thread(task, "spork-database-saver");

		thread.setDaemon(true);
		return thread;
	});

	private Path path() {
		return applicationDirectory.getUserDataDir().resolve(SporkDatabase.SPORK_DB_FILE);
	}

	/* The database is saved by one timer of its own, as often as sporks are sent, and not by every stream that sends
	   them: that would be a write to disk for each peer, on the threads that read the network */
	@Produces
	private synchronized SporkDatabase produce() {
		if (Objects.isNull(sporkDatabase.get())) {
			sporkDatabase.set(loaded(path()));
			saver.scheduleWithFixedDelay(this::save, PublishSpork.DISTRIBUTION_FREQUENCY_MINUTES,
				PublishSpork.DISTRIBUTION_FREQUENCY_MINUTES, TimeUnit.MINUTES);
		}

		return sporkDatabase.get();
	}

	/* A database that cannot be read is moved aside before the node starts with an empty one, as the next save
	   would write over it, and what it held is the operator's to look at and the network's to send again */
	private SporkDatabase loaded(Path path) {
		try {
			Files.createDirectories(applicationDirectory.getUserDataDir());
			return Files.exists(path) ? signedByTheBoard(SporkDatabase.load(path))
				: SporkDatabase.builder().build();

		} catch (IOException | ClassCastException | SerializationException ex) {
			log.atWarn().addArgument(path).addArgument(movedAside(path)).addArgument(ex.getMessage())
				.log("The spork database {} cannot be read, it is kept as {} and the node starts with an "
					+ "empty one: {}");
			log.atTrace().log(() -> ex.toString());
			return SporkDatabase.builder().build();
		}
	}

	/* A node hands out what it holds as what the network took, so of its own file it keeps only what the current
	   board signed: a tampered or stale file injects nothing, and the versions the board signed come from the peers
	   as first versions. The file keeps the rest until the node saves. */
	private static SporkDatabase signedByTheBoard(SporkDatabase database) {
		Arrays.stream(GridSpork.Type.values()).filter(type -> type != GridSpork.Type.UNDEFINED)
			.filter(type -> Objects.nonNull(database.get(type)) && !database.get(type).isTakenByTheNetwork())
			.forEach(type -> {
				log.atWarn().addArgument(type).addArgument(reasonOf(database.get(type)))
					.log("The stored {} spork is left out: {}");
				database.clear(type);
			});

		return database;
	}

	private static String reasonOf(GridSpork spork) {
		try {
			return spork.isDoublySigned() ? "its signature log is not one that current network keys signed"
				: "two different current network keys did not sign it";
		} catch (RuntimeException ex) {
			return "it cannot be encoded to check its signatures: " + ex.getMessage();
		}
	}

	private static String movedAside(Path path) {
		final Path aside = path.resolveSibling(path.getFileName() + ".unreadable-" + Instant.now().getEpochSecond());

		try {
			return Files.move(path, aside).toString();
		} catch (IOException ex) {
			log.atWarn().addArgument(path).addArgument(ex.getMessage()).log("Could not move {} aside: {}");
			return path.toString();
		}
	}

	private void save() {
		try {
			Files.createDirectories(applicationDirectory.getUserDataDir());
			SporkDatabase.persist(path(), sporkDatabase.get());

		} catch (Exception ex) {
			log.atWarn().log("Saving of spork database failed: {}", ex.getMessage());
			log.atTrace().log(() -> ex.toString());
		}
	}

	@PreDestroy
	private void destroy() {
		saver.shutdownNow();

		if (Objects.nonNull(sporkDatabase.get())) {
			save();
		}
	}
}
