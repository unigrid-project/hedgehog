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

package org.unigrid.hedgehog.model.storage.store;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.storage.DeleteProof;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageFormat;

@Slf4j
public class FragmentStore {
	public enum Tier { GUARANTEED, EXTRA }

	public enum PutResult { STORED, QUOTA, TOMBSTONE, DUPLICATE }

	@Value
	@Builder
	public static class Holding {
		private final StorageFormat format;
		private final int slots;
		private final Tier tier;
		private final int index;
		private final long size;
		private final long sequence;
	}

	@Value
	static final class Tombstone {
		private final Instant expiry;
		private final DeleteProof proof;
	}

	public static final long MIN_ENTRY_COST = Quota.MIN_ENTRY_COST;
	public static final int TOMBSTONE_BUDGET_PERCENT = Quota.TOMBSTONE_BUDGET_PERCENT;

	private static final String FRAGMENT_SUFFIX = ".frag";
	private static final String TEMPORARY_SUFFIX = ".tmp";
	private static final String TOMBSTONES = "tombstones";
	private static final int HEX_DIRECTORY = 2;

	private final Path root;
	private final Path tombstoneDirectory;
	private final Clock clock;
	private final Map<GroupId, Holding> holdings = new HashMap<>();
	private final Map<GroupId, Tombstone> tombstones = new HashMap<>();
	private final Quota quota = new Quota();
	private long sequence;

	public FragmentStore(Path root, Clock clock) throws IOException {
		this.root = root;
		this.tombstoneDirectory = root.resolve(TOMBSTONES);
		this.clock = clock;

		Files.createDirectories(tombstoneDirectory);
		scanFragments();
		scanTombstones();
	}

	public synchronized void limits(long maxBytes, int extraPoolPercent) {
		quota.limits(maxBytes, extraPoolPercent);
	}

	public synchronized PutResult put(GroupId id, StorageFormat format, int slots, Tier tier, int index,
		byte[] payload) throws IOException {

		requireUnsignedByte("slots", slots);
		requireUnsignedByte("index", index);

		if (isTombstoned(id)) {
			return PutResult.TOMBSTONE;
		}

		if (holdings.containsKey(id)) {
			return PutResult.DUPLICATE;
		}

		if (!quota.makeRoom(tier, payload.length, this::remove)) {
			return PutResult.QUOTA;
		}

		final Holding holding = Holding.builder().format(format).slots(slots).tier(tier).index(index)
			.size(payload.length).sequence(sequence++).build();
		write(pathOf(id), FragmentFormat.encodeFragment(holding, payload));
		track(id, holding);
		return PutResult.STORED;
	}

	public synchronized Optional<byte[]> get(GroupId id) throws IOException {
		if (!holdings.containsKey(id)) {
			return Optional.empty();
		}

		return Optional.of(FragmentFormat.payloadOf(Files.readAllBytes(pathOf(id))));
	}

	public synchronized Optional<Holding> holding(GroupId id) {
		return Optional.ofNullable(holdings.get(id));
	}

	public synchronized boolean remove(GroupId id) throws IOException {
		final Holding holding = holdings.remove(id);

		if (holding == null) {
			return false;
		}

		quota.release(holding);
		Files.deleteIfExists(pathOf(id));
		return true;
	}

	/* A valid proof costs anyone only a fresh key, so a group this node does not hold is tombstoned only within
	   a budget, while a held group always is, in exchange for the room its fragment frees. */
	public synchronized boolean delete(GroupId id, Duration keepTombstone, DeleteProof proof) throws IOException {
		if (!proof.isWellFormed()) {
			throw new IllegalArgumentException("A delete proof has a 32-byte key and a 64-byte signature");
		}

		if (!holdings.containsKey(id) && !tombstones.containsKey(id)
			&& !quota.admitsTombstone(tombstones.size(), this::remove)) {

			return false;
		}

		final Tombstone tombstone = new Tombstone(clock.instant().plus(keepTombstone), proof);

		write(tombstoneDirectory.resolve(id.toHex()), FragmentFormat.encodeTombstone(tombstone));
		remove(id);
		keep(id, tombstone);
		return true;
	}

	public synchronized Optional<DeleteProof> tombstone(GroupId id) {
		return Optional.ofNullable(tombstones.get(id))
			.filter(tombstone -> clock.instant().isBefore(tombstone.getExpiry())).map(Tombstone::getProof);
	}

	public synchronized boolean isTombstoned(GroupId id) {
		return tombstone(id).isPresent();
	}

	public synchronized void purgeTombstones() throws IOException {
		final List<GroupId> expired = tombstones.keySet().stream().filter(id -> !isTombstoned(id))
			.collect(Collectors.toList());

		for (GroupId id : expired) {
			tombstones.remove(id);
			quota.releaseTombstone();
			Files.deleteIfExists(tombstoneDirectory.resolve(id.toHex()));
		}
	}

	public synchronized Set<GroupId> groups() {
		return Set.copyOf(holdings.keySet());
	}

	public synchronized long usedBytes() {
		return quota.usedBytes();
	}

	public synchronized long extraBytes() {
		return quota.extraBytes();
	}

	private static void requireUnsignedByte(String name, int value) {
		if (value < 0 || value > FragmentFormat.UNSIGNED_BYTE_MAX) {
			throw new IllegalArgumentException("The " + name + " must be within 0.."
				+ FragmentFormat.UNSIGNED_BYTE_MAX);
		}
	}

	private void keep(GroupId id, Tombstone tombstone) {
		if (tombstones.put(id, tombstone) == null) {
			quota.chargeTombstone();
		}
	}

	private void track(GroupId id, Holding holding) {
		holdings.put(id, holding);
		quota.charge(id, holding);
	}

	private Path pathOf(GroupId id) {
		final String hex = id.toHex();

		return root.resolve(hex.substring(0, HEX_DIRECTORY))
			.resolve(hex.substring(HEX_DIRECTORY, 2 * HEX_DIRECTORY)).resolve(hex + FRAGMENT_SUFFIX);
	}

	private static void write(Path path, byte[] content) throws IOException {
		final Path temporary = path.resolveSibling(path.getFileName() + TEMPORARY_SUFFIX);

		Files.createDirectories(path.getParent());
		Files.write(temporary, content);
		Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	private void scanFragments() throws IOException {
		final List<Path> files;

		try (Stream<Path> paths = Files.find(root, 3, (path, attributes) -> !attributes.isDirectory()
			&& path.getFileName().toString().endsWith(FRAGMENT_SUFFIX))) {

			files = paths.collect(Collectors.toList());
		}

		final long foreign = files.stream().filter(file -> !load(file)).count();

		if (foreign > 0) {
			log.atWarn().log("Leaving {} fragment files of a storage format this build does not know", foreign);
		}

		sequence = holdings.values().stream().mapToLong(Holding::getSequence).max().orElse(-1) + 1;
	}

	/* False for a file of a format this build does not know: a newer build wrote it and may run here again */
	private boolean load(Path file) {
		try {
			final GroupId id = groupOf(file, FRAGMENT_SUFFIX);
			final byte[] header = readHeader(file);

			if (FragmentFormat.isForeign(header)) {
				return false;
			}

			track(id, FragmentFormat.decodeHeader(header, Files.size(file) - FragmentFormat.HEADER_SIZE));
		} catch (IllegalArgumentException | IndexOutOfBoundsException | BufferUnderflowException ex) {
			discard(file, "fragment");
		} catch (IOException ex) {
			skip(file, "fragment", ex);
		}

		return true;
	}

	private static GroupId groupOf(Path file, String suffix) {
		final String name = file.getFileName().toString();
		return GroupId.fromHex(name.substring(0, name.length() - suffix.length()));
	}

	/* Repair restores the redundancy an unreadable file held, so keeping it would only waste quota. */
	private static void discard(Path file, String kind) {
		log.atWarn().log("Removing an unreadable {} file", kind);
		log.atTrace().log("Unreadable {} file {}", kind, file.getFileName());

		try {
			Files.deleteIfExists(file);
		} catch (IOException ex) {
			skip(file, kind, ex);
		}
	}

	/* A file the node cannot read right now may still be sound, so it stays where it is, and a single one must never
	   keep the store, and with it the whole daemon, from starting */
	private static void skip(Path file, String kind, IOException ex) {
		log.atWarn().log("Skipping a {} file that cannot be read: {}", kind, ex.getClass().getSimpleName());
		log.atTrace().log("Skipped {} file {}", kind, file.getFileName());
	}

	private static byte[] readHeader(Path file) throws IOException {
		try (var input = Files.newInputStream(file)) {
			return input.readNBytes(FragmentFormat.HEADER_SIZE);
		}
	}

	private void scanTombstones() throws IOException {
		final List<Path> files;

		try (Stream<Path> paths = Files.list(tombstoneDirectory)) {
			files = paths.filter(path -> !path.getFileName().toString().endsWith(TEMPORARY_SUFFIX))
				.collect(Collectors.toList());
		}

		for (Path file : files) {
			loadTombstone(file);
		}
	}

	private void loadTombstone(Path file) {
		try {
			keep(groupOf(file, ""), FragmentFormat.decodeTombstone(Files.readAllBytes(file)));
		} catch (IllegalArgumentException ex) {
			discard(file, "tombstone");
		} catch (IOException ex) {
			skip(file, "tombstone", ex);
		}
	}
}
