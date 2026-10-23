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

package org.unigrid.hedgehog.command.cli.spork;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist.SporkData;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParameterException;

@Command(name = "ledger-blacklist",
	description = "The legacy addresses held back from claims; to set them, the data is a file with one per line."
)
public class LedgerBlacklist extends SporkCommand<SporkData> {
	private static final String COMMENT = "#";

	private final FileSystem fileSystem;

	public LedgerBlacklist() {
		this(FileSystems.getDefault());
	}

	LedgerBlacklist(final FileSystem fileSystem) {
		this.fileSystem = fileSystem;
	}

	@Override
	protected String location() {
		return "/gridspork/ledger-blacklist";
	}

	@Override
	protected GridSpork.Type type() {
		return GridSpork.Type.LEDGER_BLACKLIST;
	}

	/* The list replaces the one before it as a whole */
	@Override
	protected void apply(final SporkData data, final GridSpork spork) {
		spork.setData(data);
	}

	/* The addresses are decoded here rather than by the daemon, which only ever sees and checks their hashes */
	@Override
	protected SporkData read(final String name) {
		final Path file = fileSystem.getPath(name);
		final List<String> lines = lines(file);
		final Set<String> hashes = new TreeSet<>();

		for (int number = 1; number <= lines.size(); number++) {
			final String address = StringUtils.substringBefore(lines.get(number - 1), COMMENT).strip();

			if (!address.isEmpty()) {
				hashes.add(hashOf(address, number, file));
			}
		}

		final SporkData data = new SporkData();

		data.setLegacyAddresses(hashes);
		return validated(data, file);
	}

	private List<String> lines(final Path file) {
		try {
			return Files.readAllLines(file);
		} catch (IOException ex) {
			throw new ParameterException(spec().commandLine(),
				"Cannot read the legacy addresses from " + file + ": " + ex, ex);
		}
	}

	private String hashOf(final String address, final int number, final Path file) {
		try {
			return HexFormat.of().formatHex(LegacyAddress.decode(address));
		} catch (IllegalArgumentException ex) {
			throw new ParameterException(spec().commandLine(), "Line %d of %s is not a legacy address: %s"
				.formatted(number, file, ex.getMessage()), ex);
		}
	}

	private SporkData validated(final SporkData data, final Path file) {
		try {
			data.validate();
			return data;
		} catch (IllegalArgumentException ex) {
			throw new ParameterException(spec().commandLine(), "The addresses in " + file + " are not accepted: "
				+ ex.getMessage(), ex);
		}
	}
}
