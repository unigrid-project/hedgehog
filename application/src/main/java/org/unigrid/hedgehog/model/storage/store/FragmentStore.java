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
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageFormat;

@Slf4j
public class FragmentStore {
	public enum Tier { GUARANTEED, EXTRA }

	public enum PutResult { STORED, QUOTA, TOMBSTONE, DUPLICATE }

	@Value
	public static class Holding {
		private final StorageFormat format;
		private final int slots;
		private final Tier tier;
		private final int index;
		private final long size;
		private final long sequence;
	}

	private static final String FRAGMENT_SUFFIX = ".frag";
	private static final String TEMPORARY_SUFFIX = ".tmp";
	private static final String TOMBSTONES = "tombstones";
	private static final int HEADER_SIZE = 4 + Long.BYTES;
	private static final int HEX_DIRECTORY = 2;

	private final Path root;
	private final Path tombstoneDirectory;
	private final Clock clock;
	private final Map<GroupId, Holding> holdings = new HashMap<>();
	private final TreeMap<Long, GroupId> extrasByAge = new TreeMap<>();
	private final Map<GroupId, Instant> tombstones = new HashMap<>();
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

		if (isTombstoned(id)) {
			return PutResult.TOMBSTONE;
		}

		if (holdings.containsKey(id)) {
			return PutResult.DUPLICATE;
		}

		if (!makeRoom(tier, payload.length)) {
			return PutResult.QUOTA;
		}

		final Holding holding = new Holding(format, slots, tier, index, payload.length, sequence++);
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

		usedBytes -= holding.getSize();

		if (holding.getTier() == Tier.EXTRA) {
			extraBytes -= holding.getSize();
			extrasByAge.remove(holding.getSequence());
		}

		Files.deleteIfExists(pathOf(id));
		return true;
	}

	public synchronized void delete(GroupId id, Duration keepTombstone) throws IOException {
		final Instant expiry = clock.instant().plus(keepTombstone);

		remove(id);
		write(tombstoneDirectory.resolve(id.toHex()),
			ByteBuffer.allocate(Long.BYTES).putLong(expiry.toEpochMilli()).array());
		tombstones.put(id, expiry);
	}

	public synchronized boolean isTombstoned(GroupId id) {
		final Instant expiry = tombstones.get(id);
		return expiry != null && clock.instant().isBefore(expiry);
	}

	public synchronized void purgeTombstones() throws IOException {
		final List<GroupId> expired = tombstones.keySet().stream().filter(id -> !isTombstoned(id))
			.collect(Collectors.toList());

		for (GroupId id : expired) {
			tombstones.remove(id);
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

	private long extraLimit() {
		return Math.min(maxBytes, maxBytes * extraPoolPercent / 100);
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
		usedBytes += holding.getSize();

		if (holding.getTier() == Tier.EXTRA) {
			extraBytes += holding.getSize();
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

		try (Stream<Path> paths = Files.find(root, 3, (path, attributes) -> attributes.isRegularFile()
			&& path.getFileName().toString().endsWith(FRAGMENT_SUFFIX))) {

			files = paths.collect(Collectors.toList());
		}

		for (Path file : files) {
			load(file);
		}

		sequence = holdings.values().stream().mapToLong(Holding::getSequence).max().orElse(-1) + 1;
	}

	private void load(Path file) throws IOException {
		final String name = file.getFileName().toString();
		final ByteBuffer header = ByteBuffer.wrap(readHeader(file));

		try {
			final StorageFormat format = StorageFormat.of(header.get() & 0xFF);
			final int slots = header.get() & 0xFF;
			final Tier tier = Tier.values()[header.get()];
			final int index = header.get() & 0xFF;
			final Holding holding = new Holding(format, slots, tier, index, Files.size(file) - HEADER_SIZE,
				header.getLong());

			track(GroupId.fromHex(name.substring(0, name.length() - FRAGMENT_SUFFIX.length())), holding);
		} catch (IllegalArgumentException | IndexOutOfBoundsException | BufferUnderflowException ex) {
			log.atWarn().log("Skipping an unreadable fragment file");
			log.atTrace().log("Unreadable fragment file {}", name);
		}
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
			final long expiry = ByteBuffer.wrap(Files.readAllBytes(file)).getLong();
			tombstones.put(GroupId.fromHex(file.getFileName().toString()), Instant.ofEpochMilli(expiry));
		}
	}
}
