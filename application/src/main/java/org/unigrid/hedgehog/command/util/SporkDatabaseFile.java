/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
    the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.command.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.SerializationException;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.BoardKeyChain;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.BoardKeysAuthority;
import org.unigrid.hedgehog.model.spork.BoardKeysRecord;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.RecordedChainException;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.content.SporkContent;
import org.unigrid.hedgehog.model.util.TerminalText;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;

/* A spork database as the spork-db commands read it and judge each spork in it: by who signed it, as a node judges
   the database it loads, and by what holds for the spork as a whole */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SporkDatabaseFile {
	static final int SHOWN_HEX = 16;

	/* What cannot be judged without harm to this machine is refused with the file, before anything of it is shown */
	static SporkDatabase load(CommandSpec spec, Path file) {
		final SporkDatabase database;

		try {
			database = SporkDatabase.load(file);
		} catch (IOException | ClassCastException | SerializationException ex) {
			throw unreadable(spec, file, TerminalText.printable(String.valueOf(ex.getMessage())), ex);
		}

		types().stream().filter(database::holdsAnotherSporkIn).findFirst().ifPresent(type -> {
			throw unreadable(spec, file, "the " + type + " slot holds a spork that is no " + type + " spork",
				null);
		});

		sporksOf(database).stream().filter(spork -> !spork.isWithinBounds()).findFirst().ifPresent(spork -> {
			throw unreadable(spec, file, "the " + spork.getType() + " spork holds an amount beyond the bounds of"
				+ " the wire", null);
		});

		Optional.ofNullable(database.getBoardKeys()).filter(boardKeys -> !boardKeys.holdsAChain())
			.ifPresent(boardKeys -> {
				throw unreadable(spec, file, "the " + Type.BOARD_KEYS + " spork holds no chain of changes",
					null);
			});

		goByTheBoardKeysOf(database);
		return database;
	}

	/* The sporks of a database are judged, and signed, by the keys that the chain of its board keys makes active, once
	   the chain holds from the root of this machine, whoever signed the board keys spork itself: the approvals of the
	   board make a chain, and the spork of a chain that a change just extended is signed with the keys it adds */
	private static void goByTheBoardKeysOf(SporkDatabase database) {
		Optional.ofNullable(database.getBoardKeys()).ifPresent(BoardKeysAuthority::install);
	}

	/* Into a file made new and never through a link, in one step with the check that nothing is there: a link could
	   lead anywhere, and a file put there after a check would be written over. The database is made once the file is,
	   and a file that is not written whole is removed again. */
	static void writeNew(CommandSpec spec, Path output, Supplier<SporkDatabase> database) {
		final FileChannel created = created(spec, output);
		boolean written = false;

		try (FileChannel channel = created) {
			SporkDatabase.write(channel, database.get());
			written = true;
		} catch (IOException ex) {
			throw new ParameterException(spec.commandLine(), "Cannot write the spork database " + output + ": "
				+ ex.getMessage());
		} finally {
			if (!written) {
				removeUnfinished(output);
			}
		}
	}

	private static FileChannel created(CommandSpec spec, Path output) {
		try {
			return FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
				LinkOption.NOFOLLOW_LINKS);
		} catch (FileAlreadyExistsException ex) {
			throw new ParameterException(spec.commandLine(), "--output " + output
				+ " exists and is not overwritten");
		} catch (IOException ex) {
			throw new ParameterException(spec.commandLine(), "Cannot make the spork database " + output + ": "
				+ ex.getMessage());
		}
	}

	private static void removeUnfinished(Path output) {
		try {
			Files.deleteIfExists(output);
		} catch (IOException ex) {
			System.err.println("Could not remove the unfinished " + output + ": " + ex.getMessage());
		}
	}

	/* A board member signs and approves by the keys that the chain of board keys of the file makes active, so a file
	   whose chain does not hold from the root of this machine, through the checkpoint of the release, or that holds
	   less than the chain that this machine verified before is refused, and one that holds more of it is recorded
	   (BoardKeysRecord) */
	static void requireRecorded(CommandSpec spec, SporkDatabase database, Path file) {
		final Optional<List<KeyChange>> chain = Optional.ofNullable(database.getBoardKeys())
			.map(boardKeys -> boardKeys.<BoardKeys.SporkData>getData().getChanges());

		try {
			chain.ifPresent(BoardKeyChain::verify);
		} catch (IllegalArgumentException ex) {
			throw new ParameterException(spec.commandLine(), "The chain of board keys of " + file
				+ " does not hold from the root of this machine: " + TerminalText.printable(ex.getMessage()),
				ex);
		}

		try {
			BoardKeysRecord.ofThisMachine().keep(chain, "the file " + file).ifPresent(System.out::println);
		} catch (RecordedChainException | IOException ex) {
			throw new ParameterException(spec.commandLine(), TerminalText.printable(ex.getMessage()), ex);
		}
	}

	private static ParameterException unreadable(CommandSpec spec, Path file, String reason, Exception cause) {
		return new ParameterException(spec.commandLine(), "Cannot read the spork database " + file + ": " + reason,
			cause);
	}

	static List<Type> types() {
		return Arrays.stream(Type.values()).filter(type -> type != Type.UNDEFINED).toList();
	}

	static List<GridSpork> sporksOf(SporkDatabase database) {
		return types().stream().map(database::get).filter(Objects::nonNull).toList();
	}

	/* What a database holds is what the network carried, and signing it again changes none of it, so it is judged as a
	   version that changes nothing: the entries are history, a mint storage entry at a height of the old chain was
	   paid by that chain, and the ledger leaves out any entry it cannot make. The bounds of the wire are those a node
	   reads a spork within, so what does not travel cannot be signed. A file can also hold what the wire cannot say,
	   as a set read from a file takes its entries as they come, the same one twice among them, so a spork has to read
	   back from its wire form as the same bytes: a node would otherwise read another spork than the one signed. */
	static Optional<String> contentProblem(GridSpork spork) {
		try {
			SporkContent.validate(spork, spork);

			final byte[] wire = SporkWire.encode(spork);

			return Arrays.equals(SporkWire.encode(SporkWire.decode(wire)), wire) ? Optional.empty()
				: Optional.of("it does not read back the same from its wire form");
		} catch (Exception ex) {
			return Optional.of(TerminalText.printable(String.valueOf(ex.getMessage())));
		}
	}

	/* The sporks as a node reads them from the wire, which is what is signed and installed */
	static SporkDatabase readBack(SporkDatabase database) {
		final SporkDatabase copy = SporkDatabase.builder().build();

		sporksOf(database).forEach(spork -> copy.set(readBack(spork)));
		return copy;
	}

	static GridSpork readBack(GridSpork spork) {
		return SporkWire.decode(SporkWire.encode(spork));
	}

	static boolean isValid(GridSpork spork) {
		return spork.isTakenByTheNetwork() && contentProblem(spork).isEmpty();
	}

	static String keyOf(GridSpork spork, byte[] signature) {
		if (Objects.isNull(signature)) {
			return "none";
		}

		return spork.signerOfHead(signature).map(key -> StringUtils.left(key, SHOWN_HEX)).orElse("not a board key");
	}

	private static String describe(GridSpork spork) {
		return spork.getType() + ": made " + spork.getTimeStamp() + ", digest "
			+ StringUtils.left(PendingSporks.digestOf(spork), SHOWN_HEX) + ", signer "
			+ keyOf(spork, spork.getSignature()) + ", co-signer " + keyOf(spork, spork.getCosignature())
			+ ", log entries " + spork.getSignatureLog().size() + ", " + signingOf(spork) + ", content "
			+ contentProblem(spork).map(problem -> "not valid: " + problem).orElse("valid");
	}

	/* A spork that keys signed which the board switched off since counts for nothing until it is signed again */
	private static String signingOf(GridSpork spork) {
		if (spork.isTakenByTheNetwork()) {
			return "signed by the board";
		}

		return spork.wasTakenByTheNetwork() ? "signed by board keys that are switched off"
			: "not signed by the board";
	}

	/* A line for each type, and whether every spork that the database holds is one that a node takes from it */
	static boolean report(SporkDatabase database) {
		types().forEach(type -> System.out.println(Optional.ofNullable(database.get(type))
			.map(SporkDatabaseFile::describe).orElse(type + ": absent")));

		final boolean valid = sporksOf(database).stream().allMatch(SporkDatabaseFile::isValid);

		System.out.println("valid: " + (valid ? "yes" : "no"));
		return valid;
	}
}
