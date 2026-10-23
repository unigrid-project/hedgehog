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

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Cleanup;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SerializationUtils;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

@Slf4j
@Data @Builder
@NoArgsConstructor
@AllArgsConstructor
public class SporkDatabase implements Serializable {
	private static final long serialVersionUID = 5650360876357285601L;

	public static final String SPORK_DB_FILE = "spork.db";

	/* The network threads store sporks while other threads read them, so a stored spork must be seen at once */
	private volatile MintStorage mintStorage;
	private volatile MintSupply mintSupply;
	private volatile VestingStorage vestingStorage;
	private volatile StorageSpork storageSpork;
	private volatile LedgerBlacklist ledgerBlacklist;
	private volatile LedgerFee ledgerFee;

	private volatile StatisticsPubKey statisticsPubKey;

	public static SporkDatabase load(Path path) throws IOException {
		@Cleanup final InputStream stream = Files.newInputStream(path, StandardOpenOption.READ);
		return SerializationUtils.deserialize(stream);
	}

	/* The content is written beside the real file and moved over it in one atomic step, so a crash leaves the old
	   database or the new one, never a truncated mix. Saves take turns, as they share the name of the temporary file. */
	public static synchronized void persist(Path path, SporkDatabase sporkDatabase) throws IOException {
		final Path file = path.toAbsolutePath();
		final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

		try {
			write(temporary, sporkDatabase);
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			forceDirectory(file);

		} catch (IOException | RuntimeException ex) {
			removeTemporary(temporary, ex);
			throw ex;
		}
	}

	private static void write(Path temporary, SporkDatabase sporkDatabase) throws IOException {
		try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
			StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

			/* Closing the stream would close the channel before it can be forced to disk */
			final ObjectOutputStream stream = new ObjectOutputStream(Channels.newOutputStream(channel));

			stream.writeObject(sporkDatabase);
			stream.flush();
			channel.force(true);
		}
	}

	private static void removeTemporary(Path temporary, Throwable failure) {
		try {
			Files.deleteIfExists(temporary);
		} catch (IOException ex) {
			failure.addSuppressed(ex);
		}
	}

	/* An in-memory file system has no disk to flush to, and Windows does not let a directory be opened */
	private static void forceDirectory(Path file) throws IOException {
		if (file.getFileSystem() != FileSystems.getDefault()
			|| System.getProperty("os.name").startsWith("Windows")) {
			return;
		}

		try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
			directory.force(true);
		}
	}

	public GridSpork get(Type gridSporkType) {
		return slotOf(gridSporkType).reader().apply(this);
	}

	public void set(GridSpork gridSpork) {
		slotOf(gridSpork.getType()).writer().accept(this, gridSpork);
	}

	public void clear(Type gridSporkType) {
		slotOf(gridSporkType).writer().accept(this, null);
	}

	private static Slot slotOf(Type gridSporkType) {
		return Optional.ofNullable(SLOTS.get(gridSporkType)).orElseThrow(
			() -> new IllegalArgumentException("Unsupported spork type for the database")
		);
	}

	/* A switch over every type outgrows the complexity limit of a method, so each type lists its field here instead */
	private record Slot(Function<SporkDatabase, GridSpork> reader, BiConsumer<SporkDatabase, GridSpork> writer) { }

	private static final Map<Type, Slot> SLOTS = Map.of(
		Type.MINT_STORAGE, new Slot(db -> db.mintStorage,
			(db, spork) -> db.mintStorage = (MintStorage) spork),
		Type.MINT_SUPPLY, new Slot(db -> db.mintSupply,
			(db, spork) -> db.mintSupply = (MintSupply) spork),
		Type.VESTING_STORAGE, new Slot(db -> db.vestingStorage,
			(db, spork) -> db.vestingStorage = (VestingStorage) spork),
		Type.STORAGE, new Slot(db -> db.storageSpork,
			(db, spork) -> db.storageSpork = (StorageSpork) spork),
		Type.LEDGER_BLACKLIST, new Slot(db -> db.ledgerBlacklist,
			(db, spork) -> db.ledgerBlacklist = (LedgerBlacklist) spork),
		Type.LEDGER_FEE, new Slot(db -> db.ledgerFee,
			(db, spork) -> db.ledgerFee = (LedgerFee) spork),
		Type.STATISTICS_PUBKEY, new Slot(db -> db.statisticsPubKey,
			(db, spork) -> db.statisticsPubKey = (StatisticsPubKey) spork)
	);
}
