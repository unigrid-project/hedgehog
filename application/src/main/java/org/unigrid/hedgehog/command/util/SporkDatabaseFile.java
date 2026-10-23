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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.SerializationException;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.content.SporkContent;
import org.unigrid.hedgehog.model.util.TerminalText;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;

/* A spork database as the spork-db commands read it and judge each spork in it: by who signed it, as a node judges
   the database it loads, and by its content, judged whole as a node judges a first version */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SporkDatabaseFile {
	static final int SHOWN_HEX = 16;

	static SporkDatabase load(CommandSpec spec, Path file) {
		try {
			return SporkDatabase.load(file);
		} catch (IOException | ClassCastException | SerializationException ex) {
			throw new ParameterException(spec.commandLine(), "Cannot read the spork database " + file + ": "
				+ TerminalText.printable(String.valueOf(ex.getMessage())), ex);
		}
	}

	static List<Type> types() {
		return Arrays.stream(Type.values()).filter(type -> type != Type.UNDEFINED).toList();
	}

	static List<GridSpork> sporksOf(SporkDatabase database) {
		return types().stream().map(database::get).filter(Objects::nonNull).toList();
	}

	/* The bounds of the wire are those a node reads a spork within, so what does not travel cannot be signed */
	static Optional<String> contentProblem(GridSpork spork) {
		try {
			SporkContent.validate(spork, null);
			SporkWire.decode(SporkWire.encode(spork));
			return Optional.empty();
		} catch (Exception ex) {
			return Optional.of(TerminalText.printable(String.valueOf(ex.getMessage())));
		}
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
			+ ", log entries " + spork.getSignatureLog().size() + ", "
			+ (spork.isTakenByTheNetwork() ? "signed by the board" : "not signed by the board") + ", content "
			+ contentProblem(spork).map(problem -> "not valid: " + problem).orElse("valid");
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
