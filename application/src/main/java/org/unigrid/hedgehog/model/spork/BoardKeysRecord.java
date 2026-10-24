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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.crypto.NetworkKey;

/**
* The longest chain of board keys that this machine verified, kept in the user data directory in the form it travels
* in. A node or a file that holds a shorter chain or none is behind the board or holds changes of it back, and one that
* holds another chain parts from the one verified here; what goes by either takes keys that the board may have switched
* off for active ones, so it is refused once a chain is recorded. What the record moves on to is shown change by change.
* A network of keys of its own has a root of its own, and a record of its own beside it. A chain is known by what its
* changes decide ({@link BoardKeyChain#holds}), as whoever holds a change can drop an approval beyond the quorum.
*/
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class BoardKeysRecord {
	private static final String FILE = "board-keys-%s.seen";
	private static final int SHOWN_HEX = 16;
	private static final Object LOCK = new Object();

	@Getter private final Path file;
	private final Runnable afterRead;

	public static BoardKeysRecord ofThisMachine() {
		return ofThisMachine(() -> { });
	}

	/* With what a test runs between reading the record and writing it, where another run may come in */
	static BoardKeysRecord ofThisMachine(Runnable afterRead) {
		final String root = StringUtils.left(Hex.encodeHexString(BoardKeyChain.root()), SHOWN_HEX);

		return new BoardKeysRecord(directory().resolve(FILE.formatted(root)), afterRead);
	}

	static Path directory() {
		return ApplicationDirectory.create().getUserDataDir();
	}

	/**
	* Judges a chain that holds from the root of this machine, as a node or a file holds it, by the record: it has to
	* hold the recorded chain whole, and a longer one takes its place. The text returned shows each change that the
	* record took on; no board keys, or no change of them, records nothing.
	*
	* <p>The record is read, judged and written under a lock of a file beside it, which another run of the command line
	* or of a util command waits for, and under one of this process, which a file lock does not keep its own threads
	* out of: a run that read the record before another one wrote a longer chain would write a shorter one over it.
	*/
	public Optional<String> keep(Optional<List<KeyChange>> chain, String holder) throws RecordedChainException,
		IOException {

		final Path absolute = file.toAbsolutePath();

		Files.createDirectories(absolute.getParent());

		final Path lockFile = absolute.resolveSibling(absolute.getFileName() + ".lock");

		synchronized (LOCK) {
			try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
				StandardOpenOption.WRITE); FileLock lock = channel.lock()) {

				return keepLocked(chain, holder);
			}
		}
	}

	private Optional<String> keepLocked(Optional<List<KeyChange>> chain, String holder)
		throws RecordedChainException, IOException {

		final List<KeyChange> recorded = read().orElse(List.of());
		final List<KeyChange> held = chain.orElse(List.of());

		afterRead.run();

		if (!recorded.isEmpty()) {
			requireHeld(chain, recorded, holder);
		}

		if (held.size() > recorded.size() && holds(held)) {
			write(held);
			return Optional.of(recorded(held, recorded.size(), holder));
		}

		return Optional.empty();
	}

	private String recorded(List<KeyChange> held, int from, String holder) {
		final String beyond = from == 0 ? "" : " beyond the " + changes(from) + " verified before";

		final String shown = IntStream.range(from, held.size())
			.mapToObj(index -> System.lineSeparator() + "  " + described(held, index))
			.collect(Collectors.joining());

		return "Recorded in " + file + " the " + changes(held.size() - from) + " of the board keys that " + holder
			+ " holds" + beyond + "; a node or a file that holds less of the chain, or another chain, is"
			+ " refused from now on:" + shown;
	}

	private void requireHeld(Optional<List<KeyChange>> chain, List<KeyChange> recorded, String holder)
		throws RecordedChainException {

		final String held = StringUtils.capitalize(holder) + " holds ";

		if (chain.isEmpty()) {
			throw behind(held + "no board keys, while this machine verified " + changes(recorded.size()));
		}

		if (chain.get().size() < recorded.size()) {
			throw behind(held + changes(chain.get().size()) + " of the board keys, fewer than the "
				+ changes(recorded.size()) + " that this machine verified");
		}

		if (!holds(chain.get())) {
			throw new RecordedChainException(held + "a chain of the board keys that does not hold from the root"
				+ " of this machine, and nothing goes by the keys it makes active");
		}

		if (!BoardKeyChain.holds(chain.get(), recorded)) {
			throw parted(held, chain.get(), recorded);
		}
	}

	/* A chain that does not hold from the root of this machine is no record, nor does it hold the record */
	private static boolean holds(List<KeyChange> chain) {
		return BoardKeyChain.verified(chain).isPresent();
	}

	private RecordedChainException behind(String reason) {
		return new RecordedChainException(reason + " before (" + file + "): it is behind the board or holds"
			+ " changes of it back, and nothing goes by the keys it makes active. Use one that holds the whole"
			+ " chain, or delete " + file + " only to start over from the chain that the release ships.");
	}

	/* Two chains that both hold from the root part at a change that each side made in its own way, and which one the
	   board made is for the operator to find out: a key that the board switched off can sign a chain of its own */
	private RecordedChainException parted(String held, List<KeyChange> chain, List<KeyChange> recorded) {
		final int index = IntStream.range(0, recorded.size())
			.filter(i -> !BoardKeyChain.holds(chain.subList(0, i + 1), recorded.subList(0, i + 1))).findFirst()
			.orElse(recorded.size() - 1);
		final String line = System.lineSeparator();

		return new RecordedChainException(held + "a chain of the board keys that parts at change " + index
			+ " from the one that this machine verified before (" + file + "):" + line
			+ "  verified here: " + described(recorded, index) + line
			+ "  held there: " + described(chain, index) + line
			+ "Nothing goes by the keys of a chain that parts from the one verified here. Find out which of the"
			+ " two the board made, and delete " + file + " only to start over from the chain that the release"
			+ " ships.");
	}

	/* A change as an operator reads it: where it is, when it was made, what it does to the keys before it, which the
	   change before it lists or the release builds in, and who approved it */
	private static String described(List<KeyChange> chain, int index) {
		final KeyChange change = chain.get(index);
		final List<String> active = change.getActive();
		final Set<String> before = Set.copyOf(index == 0 ? Arrays.asList(NetworkKey.getBuiltInKeys())
			: chain.get(index - 1).getActive());
		final String added = shortly(active.stream().filter(key -> !before.contains(key)));
		final String removed = shortly(before.stream().filter(key -> !active.contains(key)).sorted());

		final String approvers = shortly(change.getApprovals().stream().map(KeyChange.Approval::getKey));

		return "change " + index + ", made at " + change.getMadeAt() + ", adds " + added + ", switches off "
			+ removed + ", approved by " + approvers;
	}

	private static String shortly(Stream<String> keys) {
		final String shown = keys.map(key -> StringUtils.left(key, SHOWN_HEX)).collect(Collectors.joining(" "));

		return shown.isEmpty() ? "none" : shown;
	}

	private static String changes(int count) {
		return count + (count == 1 ? " change" : " changes");
	}

	/* The record was verified when it was written, so a file that does not read back whole is no record to go by. A
	   machine that has no record yet starts from the chain that its release ships. */
	public Optional<List<KeyChange>> read() throws IOException {
		if (!Files.exists(file)) {
			return Optional.of(BoardKeyChain.shipped()).filter(shipped -> !shipped.isEmpty());
		}

		try (InputStream in = Files.newInputStream(file)) {
			return Optional.of(BoardKeyChain.decoded(in.readNBytes(BoardKeyChain.MAX_BYTES + 1)));
		} catch (RuntimeException ex) {
			throw new IOException("The record of the board keys " + file + " cannot be read: " + ex.getMessage()
				+ "; delete it only to start over from the keys built into the release", ex);
		}
	}

	private void write(List<KeyChange> chain) throws IOException {
		SporkDatabase.replace(file, channel -> {
			channel.write(ByteBuffer.wrap(BoardKeyChain.encoded(chain)));
			channel.force(true);
		});
	}
}
