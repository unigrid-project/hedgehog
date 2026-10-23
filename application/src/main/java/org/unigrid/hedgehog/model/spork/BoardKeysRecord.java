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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;

/**
* The longest chain of board keys that this machine verified, kept in the user data directory in the form it travels
* in. A node or a file that holds a shorter chain, another one or none hides changes that the board made, and what goes
* by it takes keys that the board switched off for active ones, so it is refused once a chain is recorded. A network of
* keys of its own has a root of its own, and a record of its own beside it. A chain is known by what its changes decide
* ({@link BoardKeyChain#holds}), as whoever holds a change can drop an approval beyond the quorum.
*/
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class BoardKeysRecord {
	private static final String FILE = "board-keys-%s.seen";
	private static final int SHOWN_HEX = 16;

	/* A chain of the most changes, each of the most keys and approvals, with room to spare */
	private static final int MAX_BYTES = 1 << 20;

	@Getter private final Path file;

	public static BoardKeysRecord ofThisMachine() {
		final String root = StringUtils.left(Hex.encodeHexString(BoardKeyChain.root()), SHOWN_HEX);

		return new BoardKeysRecord(directory().resolve(FILE.formatted(root)));
	}

	static Path directory() {
		return ApplicationDirectory.create().getUserDataDir();
	}

	/**
	* Judges a chain that holds from the root of this machine, as a node or a file holds it, by the record: it has to
	* hold the recorded chain whole, and a longer one takes its place. The first chain makes the record, which the line
	* returned says; no board keys, or no change of them, records nothing.
	*/
	public Optional<String> keep(Optional<List<KeyChange>> chain, String holder) throws RecordedChainException,
		IOException {

		final Optional<List<KeyChange>> recorded = read();
		final List<KeyChange> held = chain.orElse(List.of());

		if (recorded.isEmpty()) {
			return held.isEmpty() || !holds(held) ? Optional.empty() : Optional.of(created(held, holder));
		}

		requireHeld(chain, recorded.get(), holder);

		if (held.size() > recorded.get().size()) {
			write(held);
		}

		return Optional.empty();
	}

	private String created(List<KeyChange> held, String holder) throws IOException {
		write(held);
		return "Recorded the chain of " + changes(held.size()) + " of the board keys that " + holder + " holds in "
			+ file + "; a node or a file that holds less of it, or another chain, is refused from now on";
	}

	private void requireHeld(Optional<List<KeyChange>> chain, List<KeyChange> recorded, String holder)
		throws RecordedChainException {

		final String held = StringUtils.capitalize(holder) + " holds ";

		if (chain.isEmpty()) {
			throw refused(held + "no board keys, nor", recorded);
		}

		if (chain.get().size() < recorded.size()) {
			throw refused(held + "a chain of " + changes(chain.get().size()) + " of the board keys, fewer than",
				recorded);
		}

		if (!holds(chain.get()) || !BoardKeyChain.holds(chain.get(), recorded)) {
			throw refused(held + "a chain of " + changes(chain.get().size()) + " of the board keys that does not"
				+ " hold", recorded);
		}
	}

	/* A chain that does not hold from the root of this machine is no record, nor does it hold the record */
	private static boolean holds(List<KeyChange> chain) {
		return BoardKeyChain.verified(chain).isPresent();
	}

	private RecordedChainException refused(String reason, List<KeyChange> recorded) {
		return new RecordedChainException(reason + " the chain of " + changes(recorded.size()) + " that this"
			+ " machine verified before (" + file + "): it hides changes that the board made, and nothing"
			+ " goes by the keys it makes active. Delete " + file + " only to start over from the keys built"
			+ " into the release.");
	}

	private static String changes(int count) {
		return count + (count == 1 ? " change" : " changes");
	}

	/* The record was verified when it was written, so a file that does not read back whole is no record to go by */
	public Optional<List<KeyChange>> read() throws IOException {
		if (!Files.exists(file)) {
			return Optional.empty();
		}

		try (InputStream in = Files.newInputStream(file)) {
			return Optional.of(chainIn(in.readNBytes(MAX_BYTES + 1)));
		} catch (RuntimeException ex) {
			throw new IOException("The record of the board keys " + file + " cannot be read: " + ex.getMessage()
				+ "; delete it only to start over from the keys built into the release", ex);
		}
	}

	@SneakyThrows
	private static List<KeyChange> chainIn(byte[] read) {
		Validate.isTrue(read.length <= MAX_BYTES, "it is larger than a chain of board keys is");

		final ByteBuf bytes = Unpooled.wrappedBuffer(read);
		final List<KeyChange> chain = new BoardKeysDecoder().decodeChunk(null, bytes).orElseThrow().getChanges();

		Validate.isTrue(!bytes.isReadable(), "it holds more than a chain of board keys");
		return chain;
	}

	private void write(List<KeyChange> chain) throws IOException {
		final BoardKeys.SporkData data = new BoardKeys.SporkData();

		data.setChanges(new ArrayList<>(chain));
		Files.createDirectories(file.toAbsolutePath().getParent());
		SporkDatabase.replace(file, channel -> {
			channel.write(ByteBuffer.wrap(SporkContentEncoder.encode(GridSpork.Type.BOARD_KEYS, data)));
			channel.force(true);
		});
	}
}
