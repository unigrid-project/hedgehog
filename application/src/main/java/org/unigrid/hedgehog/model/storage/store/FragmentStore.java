/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.storage.DeleteProof;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;

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
	private static class Tombstone {
		private final Instant expiry;
		private final DeleteProof proof;
	}

	/* Every entry costs a file, an inode and a heap entry whatever its size, so charging at least this much lets
	   the quota bound how many entries there are as well as how many bytes they hold */
	public static final long MIN_ENTRY_COST = 4096;
	public static final int TOMBSTONE_BUDGET_PERCENT = 1;

	private static final String FRAGMENT_SUFFIX = ".frag";
	private static final String TEMPORARY_SUFFIX = ".tmp";
	private static final String TOMBSTONES = "tombstones";
	private static final int HEADER_SIZE = 4 + Long.BYTES;
	private static final int TOMBSTONE_SIZE = 2 * Long.BYTES + GroupKey.PUBLIC_KEY_SIZE + GroupKey.SIGNATURE_SIZE;
	private static final int HEX_DIRECTORY = 2;
	private static final int UNSIGNED_BYTE_MAX = 0xFF;
	private static final int PERCENT = 100;

	private final Path root;
	private final Path tombstoneDirectory;
	private final Clock clock;
	private final Map<GroupId, Holding> holdings = new HashMap<>();
	private final TreeMap<Long, GroupId> extrasByAge = new TreeMap<>();
	private final Map<GroupId, Tombstone> tombstones = new HashMap<>();
	private long sequence;
	private long usedBytes;
	private long extraBytes;
	private long maxBytes;
	private int extraPoolPercent;

	public FragmentStore(Path root, Clock clock) throws IOException {
		this.root = root;
		this.tombstoneDirectory = root.resolve(TOMBSTONES);
		this.clock = clock;

		Files.createDirectories(tombstoneDirectory);
		scanFragments();
		scanTombstones();
	}

	public synchronized void limits(long maxBytes, int extraPoolPercent) {
		this.maxBytes = maxBytes;
		this.extraPoolPercent = extraPoolPercent;
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

		if (!makeRoom(tier, costOf(payload.length))) {
			return PutResult.QUOTA;
		}

		final Holding holding = Holding.builder().format(format).slots(slots).tier(tier).index(index)
			.size(payload.length).sequence(sequence++).build();
		write(pathOf(id), ByteBuffer.allocate(HEADER_SIZE + payload.length).put(format.getId()).put((byte) slots)
			.put((byte) tier.ordinal()).put((byte) index).putLong(holding.getSequence()).put(payload).array());
		track(id, holding);
		return PutResult.STORED;
	}

	public synchronized Optional<byte[]> get(GroupId id) throws IOException {
		if (!holdings.containsKey(id)) {
			return Optional.empty();
		}

		final byte[] content = Files.readAllBytes(pathOf(id));
		return Optional.of(Arrays.copyOfRange(content, HEADER_SIZE, content.length));
	}

	public synchronized Optional<Holding> holding(GroupId id) {
		return Optional.ofNullable(holdings.get(id));
	}

	public synchronized boolean remove(GroupId id) throws IOException {
		final Holding holding = holdings.remove(id);

		if (holding == null) {
			return false;
		}

		usedBytes -= costOf(holding.getSize());

		if (holding.getTier() == Tier.EXTRA) {
			extraBytes -= costOf(holding.getSize());
			extrasByAge.remove(holding.getSequence());
		}

		Files.deleteIfExists(pathOf(id));
		return true;
	}

	/* [expiry u64][timestamp i64][public key 32 bytes][signature 64 bytes]
	   A valid proof costs anyone only a fresh key, so a group this node does not hold is tombstoned only within
	   a budget, while a held group always is, in exchange for the room its fragment frees. */
	public synchronized boolean delete(GroupId id, Duration keepTombstone, DeleteProof proof) throws IOException {
		if (!proof.isWellFormed()) {
			throw new IllegalArgumentException("A delete proof has a 32-byte key and a 64-byte signature");
		}

		if (!holdings.containsKey(id) && !tombstones.containsKey(id) && !admitsTombstone()) {
			return false;
		}

		final Instant expiry = clock.instant().plus(keepTombstone);

		write(tombstoneDirectory.resolve(id.toHex()), ByteBuffer.allocate(TOMBSTONE_SIZE)
			.putLong(expiry.toEpochMilli()).putLong(proof.getTimestamp()).put(proof.getPublicKey())
			.put(proof.getSignature()).array());
		remove(id);
		keep(id, new Tombstone(expiry, proof));
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
			usedBytes -= MIN_ENTRY_COST;
			Files.deleteIfExists(tombstoneDirectory.resolve(id.toHex()));
		}
	}

	public synchronized Set<GroupId> groups() {
		return Set.copyOf(holdings.keySet());
	}

	public synchronized long usedBytes() {
		return usedBytes;
	}

	public synchronized long extraBytes() {
		return extraBytes;
	}

	private static void requireUnsignedByte(String name, int value) {
		if (value < 0 || value > UNSIGNED_BYTE_MAX) {
			throw new IllegalArgumentException("The " + name + " must be within 0.." + UNSIGNED_BYTE_MAX);
		}
	}

	private static long costOf(long size) {
		return Math.max(size, MIN_ENTRY_COST);
	}

	private boolean admitsTombstone() throws IOException {
		return (tombstones.size() + 1) * MIN_ENTRY_COST <= percentOf(maxBytes, TOMBSTONE_BUDGET_PERCENT)
			&& makeRoom(Tier.GUARANTEED, MIN_ENTRY_COST);
	}

	private void keep(GroupId id, Tombstone tombstone) {
		if (tombstones.put(id, tombstone) == null) {
			usedBytes += MIN_ENTRY_COST;
		}
	}

	private long extraLimit() {
		return percentOf(maxBytes, extraPoolPercent);
	}

	/* Splits off the whole hundreds first, so even a quota near Long.MAX_VALUE cannot overflow */
	private static long percentOf(long amount, int percent) {
		final int bounded = Math.max(0, Math.min(percent, PERCENT));
		return amount / PERCENT * bounded + amount % PERCENT * bounded / PERCENT;
	}

	private boolean fits(Tier tier, long size) {
		final boolean fitsTotal = usedBytes + size <= maxBytes;
		return tier == Tier.GUARANTEED ? fitsTotal : fitsTotal && extraBytes + size <= extraLimit();
	}

	private boolean makeRoom(Tier tier, long size) throws IOException {
		final long room = maxBytes - (usedBytes - extraBytes);
		final long ceiling = tier == Tier.GUARANTEED ? room : Math.min(room, extraLimit());

		if (size > ceiling) {
			return false;
		}

		while (!fits(tier, size)) {
			remove(extrasByAge.firstEntry().getValue());
		}

		return true;
	}

	private void track(GroupId id, Holding holding) {
		holdings.put(id, holding);
		usedBytes += costOf(holding.getSize());

		if (holding.getTier() == Tier.EXTRA) {
			extraBytes += costOf(holding.getSize());
			extrasByAge.put(holding.getSequence(), id);
		}
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

		for (Path file : files) {
			load(file);
		}

		sequence = holdings.values().stream().mapToLong(Holding::getSequence).max().orElse(-1) + 1;
	}

	private void load(Path file) {
		try {
			track(groupOf(file, FRAGMENT_SUFFIX), readHolding(file));
		} catch (IllegalArgumentException | IndexOutOfBoundsException | BufferUnderflowException ex) {
			discard(file, "fragment");
		} catch (IOException ex) {
			skip(file, "fragment", ex);
		}
	}

	private static GroupId groupOf(Path file, String suffix) {
		final String name = file.getFileName().toString();
		return GroupId.fromHex(name.substring(0, name.length() - suffix.length()));
	}

	private static Holding readHolding(Path file) throws IOException {
		final ByteBuffer header = ByteBuffer.wrap(readHeader(file));

		return Holding.builder().format(StorageFormat.of(header.get() & UNSIGNED_BYTE_MAX))
			.slots(header.get() & UNSIGNED_BYTE_MAX).tier(Tier.values()[header.get()])
			.index(header.get() & UNSIGNED_BYTE_MAX).sequence(header.getLong())
			.size(Files.size(file) - HEADER_SIZE).build();
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
			return input.readNBytes(HEADER_SIZE);
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
			keep(groupOf(file, ""), readTombstone(file));
		} catch (IllegalArgumentException ex) {
			discard(file, "tombstone");
		} catch (IOException ex) {
			skip(file, "tombstone", ex);
		}
	}

	private static Tombstone readTombstone(Path file) throws IOException {
		final byte[] content = Files.readAllBytes(file);

		if (content.length != TOMBSTONE_SIZE) {
			throw new IllegalArgumentException("A tombstone file is exactly " + TOMBSTONE_SIZE + " bytes");
		}

		final ByteBuffer buffer = ByteBuffer.wrap(content);
		final Instant expiry = Instant.ofEpochMilli(buffer.getLong());
		final long timestamp = buffer.getLong();
		final byte[] publicKey = new byte[GroupKey.PUBLIC_KEY_SIZE];
		final byte[] signature = new byte[GroupKey.SIGNATURE_SIZE];

		buffer.get(publicKey).get(signature);
		return new Tombstone(expiry, new DeleteProof(publicKey, timestamp, signature));
	}
}
