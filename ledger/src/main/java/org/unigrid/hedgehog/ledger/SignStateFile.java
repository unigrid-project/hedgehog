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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;

/* The sign state in a file of its own: the magic, a version byte, the length of the payload, the payload and the
   SHA-512 of everything before it. A save writes the whole file next to the real one, forces it to disk and moves it
   over the real one in a single step, so a crash leaves the old state or the new one, never a mix, and a leftover
   temporary file is overwritten by the next save and never read. The checksum is what notices damage that the move
   cannot prevent, such as a disk that returns other bytes than it was given. */
public final class SignStateFile implements SignStore {
	/* Sizes the largest file that load reads, to fend off one that is garbage. A validator that needs more views
	   than this for one height has been stalled for days, and what it signed in them is far less than this size. */
	static final int MAX_VIEWS = 1 << 10;
	private static final byte[] MAGIC = "UGSS".getBytes(StandardCharsets.US_ASCII);
	private static final byte VERSION = 1;
	private static final int HEADER_SIZE = MAGIC.length + Byte.BYTES + Integer.BYTES;
	private static final int CHECKSUM_SIZE = Digests.HASH_SIZE;

	private final Path file;
	private final Path temporary;
	private final int maxViews;

	public SignStateFile(Path file) {
		this(file, MAX_VIEWS);
	}

	SignStateFile(Path file, int maxViews) {
		this.file = file.toAbsolutePath();
		this.temporary = this.file.resolveSibling(this.file.getFileName() + ".tmp");
		this.maxViews = maxViews;
	}

	static long largestFile(int largestProof, int views) {
		return HEADER_SIZE + SignState.largestEncodedSize(largestProof, views) + CHECKSUM_SIZE;
	}

	@Override
	public Optional<SignState> load(int largestProof) throws IOException {
		final long size;

		try {
			size = Files.size(file);
		} catch (NoSuchFileException e) {
			return Optional.empty();
		}

		if (size > largestFile(largestProof, maxViews)) {
			throw corrupt("is " + size + " bytes, more than a sign state can be");
		}

		return Optional.of(decode(Files.readAllBytes(file), largestProof));
	}

	@Override
	public void save(SignState state) throws IOException {
		Files.createDirectories(file.getParent());
		write(frame(state.encode()));
		Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		forceDirectory();
	}

	private static byte[] frame(byte[] payload) {
		final byte[] body = Bytes.concat(MAGIC, new byte[] { VERSION }, Bytes.intBytes(payload.length), payload);

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

	/* The move is only durable once the directory entry is, but not every file system lets a directory be opened
	   to force it. Where it cannot be opened there is nothing more to do, so only that step is skipped. */
	private void forceDirectory() throws IOException {
		final FileChannel directory;

		try {
			directory = FileChannel.open(file.getParent(), StandardOpenOption.READ);
		} catch (IOException e) {
			return;
		}

		try (directory) {
			directory.force(true);
		}
	}

	private SignState decode(byte[] bytes, int largestProof) throws CorruptSignStateException {
		final byte[] payload = unframe(bytes);

		try {
			return SignState.decode(payload, largestProof);
		} catch (IllegalArgumentException e) {
			throw new CorruptSignStateException(file + " holds no sign state: " + e.getMessage(), e);
		}
	}

	private byte[] unframe(byte[] bytes) throws CorruptSignStateException {
		if (bytes.length < HEADER_SIZE + CHECKSUM_SIZE) {
			throw corrupt("is " + bytes.length + " bytes, too short for a sign state");
		}

		final ByteBuffer in = ByteBuffer.wrap(bytes);

		requireHeader(in);

		final int length = in.getInt();

		if (length != bytes.length - HEADER_SIZE - CHECKSUM_SIZE) {
			throw corrupt("names a payload length of " + length + " bytes, but the file holds "
				+ (bytes.length - HEADER_SIZE - CHECKSUM_SIZE) + " bytes of payload");
		}

		requireChecksum(bytes);
		return Arrays.copyOfRange(bytes, HEADER_SIZE, HEADER_SIZE + length);
	}

	private void requireHeader(ByteBuffer in) throws CorruptSignStateException {
		final byte[] magic = new byte[MAGIC.length];

		in.get(magic);

		if (!Arrays.equals(magic, MAGIC)) {
			throw corrupt("does not start with the magic of a sign state");
		}

		final byte version = in.get();

		if (version != VERSION) {
			throw corrupt("is of version " + Byte.toUnsignedInt(version) + ", not " + VERSION);
		}
	}

	private void requireChecksum(byte[] bytes) throws CorruptSignStateException {
		final int end = bytes.length - CHECKSUM_SIZE;
		final byte[] expected = Digests.sha512(Arrays.copyOf(bytes, end));

		if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(bytes, end, bytes.length))) {
			throw corrupt("does not match its checksum");
		}
	}

	private CorruptSignStateException corrupt(String reason) {
		return new CorruptSignStateException("The sign state file " + file + " " + reason);
	}
}
