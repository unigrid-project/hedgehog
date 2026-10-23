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

package org.unigrid.hedgehog.command.util;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.spork.ProposedKeyChange;
import org.unigrid.hedgehog.model.util.TerminalText;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;

/* A change of the board keys in the file that board members pass between them, as JSON. The file may come from anyone,
   so what is read is bounded, and the change has to be one that can be written again before anyone signs it. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ChangeFile {
	static final int SHOWN_HEX = 16;

	/* A full change, its keys and approvals in hex and base64, with room to spare */
	private static final int MAX_BYTES = 1 << 16;

	private static final ObjectMapper JSON = new JsonConfiguration().getContext(ProposedKeyChange.class);
	private static final ObjectWriter PRETTY = JSON.writerWithDefaultPrettyPrinter();

	public static ProposedKeyChange read(CommandSpec spec, Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			final byte[] bytes = in.readNBytes(MAX_BYTES + 1);

			Validate.isTrue(bytes.length <= MAX_BYTES, "it is larger than a change of the board keys is");

			final ProposedKeyChange proposed = JSON.readValue(bytes, ProposedKeyChange.class);

			Validate.isTrue(Objects.nonNull(proposed.getChange()) && Objects.nonNull(proposed.getBefore()),
				"it holds no change of the board keys");
			proposed.getChange().requireEncodable();
			return proposed;
		} catch (IOException | IllegalArgumentException ex) {
			throw new ParameterException(spec.commandLine(), "Cannot read the change of the board keys " + file
				+ ": " + TerminalText.printable(String.valueOf(ex.getMessage())), ex);
		}
	}

	/* Made new and never through a link, as a spork database that util spork-db sign writes is */
	static void writeNew(CommandSpec spec, Path file, ProposedKeyChange proposed) {
		try {
			Files.write(file, PRETTY.writeValueAsBytes(proposed), StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
		} catch (FileAlreadyExistsException ex) {
			throw new ParameterException(spec.commandLine(), "--output " + file
				+ " exists and is not overwritten");
		} catch (IOException ex) {
			throw new ParameterException(spec.commandLine(), "Cannot write the change " + file + ": "
				+ ex.getMessage(), ex);
		}
	}

	/* Written beside the file and moved over it in one step, so a board member who passes it on never passes half */
	static void replace(CommandSpec spec, Path file, ProposedKeyChange proposed) {
		final Path absolute = file.toAbsolutePath();

		try {
			final Path temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName() + ".",
				".tmp");

			Files.write(temporary, PRETTY.writeValueAsBytes(proposed));
			Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ex) {
			throw new ParameterException(spec.commandLine(), "Cannot write the change " + file + ": "
				+ ex.getMessage(), ex);
		}
	}

	static String shortly(String key) {
		return StringUtils.left(key, SHOWN_HEX);
	}
}
