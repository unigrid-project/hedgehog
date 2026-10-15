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

package org.unigrid.hedgehog.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;

/* The sign state in a file of its own: the magic, a version byte, the size of the validator set the state was saved
   under, the length of the payload, the payload and the SHA-512 of everything before it. A save writes the whole file
   next to the real one, forces it to disk and moves it over the real one in a single step, so a crash leaves the old
   state or the new one, never a mix, and a leftover temporary file is overwritten by the next save and never read.
   The checksum is what notices damage that the move cannot prevent, such as a disk that returns other bytes than it
   was given. The set size is in the file, as the set may have changed by the time the file is read, and it sizes
   both what a proof can hold and the largest file that is read: that size is exact for a set of a given size and
   loose for a tiny set, which could hold many large proposals in theory and never does.

   Two nodes over one sign state could each sign something different for a view, as when a process is started twice, or
   a restored backup runs beside the original. The file therefore takes an exclusive lock on a file of its own beside
   it, at the first load or save, and holds it until close(); another file over the same path, in this process or in
   another, is refused with a FileLockedException. The lock covers this file alone, not the block log. */
public final class SignStateFile implements SignStore {
	/* How the lock is taken, which a test can replace, as the in-memory file system gives everyone a lock. Null means
	   that another process holds it; a lock held in this process is an OverlappingFileLockException. */
	@FunctionalInterface
	interface Locker {
		FileLock tryLock(Path lockFile, FileChannel channel) throws IOException;
	}

	/* The highest view that a file is sized for, which fends off a file that is garbage. The timeouts grow with
	   the view, so a validator that reaches it has been stalled for more than a month on one height. A state of a
	   later view is still read if its entries fit the size. */
	static final int MAX_VIEW = 1 << 12;
	private static final byte[] MAGIC = "UGSS".getBytes(StandardCharsets.US_ASCII);
	private static final byte VERSION = 2;
	private static final int LENGTH_OFFSET = MAGIC.length + Byte.BYTES + Integer.BYTES;
	private static final int HEADER_SIZE = LENGTH_OFFSET + Integer.BYTES;
	private static final int CHECKSUM_SIZE = Digests.HASH_SIZE;

	private final Path file;
	private final Path temporary;
	private final Path lockFile;
	private final int maxView;
	private final Locker locker;
	private FileChannel lockChannel;
	private FileLock held;

	public SignStateFile(Path file) {
		this(file, MAX_VIEW);
	}

	SignStateFile(Path file, int maxView) {
		this(file, maxView, (path, channel) -> channel.tryLock());
	}

	SignStateFile(Path file, int maxView, Locker locker) {
		this.file = file.toAbsolutePath();
		this.temporary = this.file.resolveSibling(this.file.getFileName() + ".tmp");
		this.lockFile = this.file.resolveSibling(this.file.getFileName() + ".lock");
		this.maxView = maxView;
		this.locker = locker;
	}

	static long largestFile(int largestProof, int maxView) {
		return HEADER_SIZE + SignState.largestEncodedSize(largestProof, maxView) + CHECKSUM_SIZE;
	}

	/* An in-memory file system has no disk to flush to, and Windows does not let a directory be opened */
	static boolean canForceDirectories(FileSystem fileSystem, String operatingSystem) {
		return fileSystem == FileSystems.getDefault() && !operatingSystem.startsWith("Windows");
	}

	@Override
	public synchronized Optional<SignState> load() throws IOException {
		lock();

		final FileChannel channel;

		try {
			channel = FileChannel.open(file, StandardOpenOption.READ);
		} catch (NoSuchFileException e) {
			return Optional.empty();
		}

		try (channel) {
			final long size = channel.size();
			final int validators = readValidators(channel, size);

			return Optional.of(decode(readAll(channel, size), validators));
		}
	}

	/* Refuses a state that load would refuse for its size, so that the validator stops here and not at its next
	   start, where it could neither sign nor read its own file */
	@Override
	public synchronized void save(SignState state, int largestProof) throws IOException {
		lock();

		if (!isSetSize(largestProof)) {
			throw new IllegalArgumentException("A set has 1 to " + ValidatorSet.MAX_SIZE
				+ " validators, found " + largestProof);
		}

		final byte[] framed = frame(state.encode(), largestProof);

		if (framed.length > largestFile(largestProof, maxView)) {
			throw new IllegalArgumentException("A sign state of " + framed.length + " bytes is more than the "
				+ largestFile(largestProof, maxView) + " bytes that can be read back");
		}

		Files.createDirectories(file.getParent());
		write(framed);
		Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		forceDirectory();
	}

	/* Lets go of the lock, so that a node over the same files can take it. A file that is used after it was closed
	   takes the lock again. */
	@Override
	public synchronized void close() throws IOException {
		if (lockChannel == null) {
			return;
		}

		final FileChannel channel = lockChannel;
		final FileLock lock = held;

		lockChannel = null;
		held = null;

		try (channel) {
			lock.release();
		}
	}

	private void lock() throws IOException {
		if (lockChannel != null) {
			return;
		}

		Files.createDirectories(file.getParent());

		final FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
			StandardOpenOption.WRITE);

		try {
			held = acquire(channel);
			lockChannel = channel;
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
	}

	private FileLock acquire(FileChannel channel) throws IOException {
		try {
			final FileLock acquired = locker.tryLock(lockFile, channel);

			if (acquired != null) {
				return acquired;
			}
		} catch (OverlappingFileLockException e) {
			throw locked(e);
		}

		throw locked(null);
	}

	private FileLockedException locked(Throwable cause) {
		return new FileLockedException("The sign state file " + file + " is locked: another process or node holds "
			+ "the data directory", cause);
	}

	private static boolean isSetSize(int validators) {
		return validators >= 1 && validators <= ValidatorSet.MAX_SIZE;
	}

	private static byte[] frame(byte[] payload, int validators) {
		final byte[] body = Bytes.concat(MAGIC, new byte[] { VERSION }, Bytes.intBytes(validators),
			Bytes.intBytes(payload.length), payload);

		return Bytes.concat(body, Digests.sha512(body));
	}

	private void write(byte[] bytes) throws IOException {
		final ByteBuffer buffer = ByteBuffer.wrap(bytes);

		try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
			StandardOpenOption.TRUNCATE_EXISTING)) {
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}

			channel.force(true);
		}
	}

	/* The move is only durable once the directory entry is. Where a directory cannot be opened for this at all, the
	   step is skipped and only that step. Anywhere else a failure is the caller's to see, never to be swallowed. What
	   remains is a platform that is neither Windows nor in-memory and still cannot flush a directory, such as some
	   network mounts: there the validator stops at its first save, and on Windows the move is as durable as the
	   system makes a rename. */
	private void forceDirectory() throws IOException {
		if (!canForceDirectories(file.getFileSystem(), System.getProperty("os.name"))) {
			return;
		}

		try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
			directory.force(true);
		}
	}

	/* Reads the header alone and judges the size of the file by the set size that it records, before any more of
	   the file is read */
	private int readValidators(FileChannel channel, long size) throws IOException {
		if (size < HEADER_SIZE + CHECKSUM_SIZE) {
			throw corrupt("is " + size + " bytes, too short for a sign state");
		}

		final ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);

		readFully(channel, header);
		header.flip();
		requireMagicAndVersion(header);

		final int validators = header.getInt();

		if (!isSetSize(validators)) {
			throw corrupt("records a set of " + validators + " validators, not 1 to " + ValidatorSet.MAX_SIZE);
		}

		if (size > largestFile(validators, maxView)) {
			throw corrupt("is " + size + " bytes, more than a sign state can be");
		}

		return validators;
	}

	private byte[] readAll(FileChannel channel, long size) throws IOException {
		final ByteBuffer all = ByteBuffer.allocate(Math.toIntExact(size));

		readFully(channel, all);
		return all.array();
	}

	private void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			if (channel.read(buffer, buffer.position()) < 0) {
				throw corrupt("ended before its size said it would");
			}
		}
	}

	private SignState decode(byte[] bytes, int validators) throws CorruptSignStateException {
		final byte[] payload = unframe(bytes);

		try {
			return SignState.decode(payload, validators);
		} catch (IllegalArgumentException e) {
			throw new CorruptSignStateException(file + " holds no sign state: " + e.getMessage(), e);
		}
	}

	private byte[] unframe(byte[] bytes) throws CorruptSignStateException {
		final int length = ByteBuffer.wrap(bytes).getInt(LENGTH_OFFSET);
		final int held = bytes.length - HEADER_SIZE - CHECKSUM_SIZE;

		if (length != held) {
			throw corrupt("names a payload length of " + length + " bytes, but the file holds " + held
				+ " bytes of payload");
		}

		requireChecksum(bytes);
		return Arrays.copyOfRange(bytes, HEADER_SIZE, HEADER_SIZE + length);
	}

	private void requireMagicAndVersion(ByteBuffer header) throws CorruptSignStateException {
		final byte[] magic = new byte[MAGIC.length];

		header.get(magic);

		if (!Arrays.equals(magic, MAGIC)) {
			throw corrupt("does not start with the magic of a sign state");
		}

		final byte version = header.get();

		if (version != VERSION) {
			throw corrupt("is of version " + Byte.toUnsignedInt(version) + ", not " + VERSION);
		}
	}

	private void requireChecksum(byte[] bytes) throws CorruptSignStateException {
		final int end = bytes.length - CHECKSUM_SIZE;
		final MessageDigest digest = sha512();

		digest.update(bytes, 0, end);

		if (!MessageDigest.isEqual(digest.digest(), Arrays.copyOfRange(bytes, end, bytes.length))) {
			throw corrupt("does not match its checksum");
		}
	}

	/* Hashes the bytes where they lie: Digests takes whole arrays, so it would need a copy of what can be megabytes */
	private static MessageDigest sha512() {
		try {
			return MessageDigest.getInstance("SHA-512");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-512 is required of every Java platform", e);
		}
	}

	private CorruptSignStateException corrupt(String reason) {
		return new CorruptSignStateException("The sign state file " + file + " " + reason);
	}
}
