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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddressHex;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.LegacyWhiteList.SporkData;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParameterException;

/**
 * Sets the legacy whitelist from a file of addresses, one per line. Every address is checked against the node's snapshot
 * before anything is signed, so a mistyped or empty address is never approved.
 */
@Command(name = "legacy-whitelist",
	description = "The legacy addresses approved for migration; to set them, the data is a file with one per line."
)
public class LegacyWhiteList extends SporkCommand<SporkData> {
	private static final String COMMENT = "#";

	private final FileSystem fileSystem;

	/** Reads the file from this machine. */
	public LegacyWhiteList() {
		this(FileSystems.getDefault());
	}

	/** Reads the file from the given file system, so tests need no files on disk. */
	LegacyWhiteList(final FileSystem fileSystem) {
		this.fileSystem = fileSystem;
	}

	/** Where the node shows the whole list. */
	@Override
	protected String location() {
		return "/gridspork/legacy-whitelist";
	}

	/** The spork this command reads and sets. */
	@Override
	protected GridSpork.Type type() {
		return GridSpork.Type.LEGACY_WHITELIST;
	}

	/**
	 * The new list replaces the old one whole, so the board can take back a mistaken approval before the holder
	 * migrates.
	 */
	@Override
	protected void apply(final SporkData data, final GridSpork spork) {
		spork.setData(data);
	}

	/** Runs the snapshot check after the board key is confirmed and before anything is signed. */
	@Override
	protected int propose(final GridSporkSet set, final SporkData data) {
		return SporkProposal.propose(spec().commandLine(), type(), spork -> apply(data, spork), set.getBoardKey(),
			NetworkKey::isTrusted, sporks -> SnapshotFunding.require(sporks.getRest(), data),
			set.isFirstVersion());
	}

	/** Skips blank lines and anything after a #. Addresses are decoded here, since the node only sees their hashes. */
	@Override
	protected SporkData read(final String name) {
		final Path file = fileSystem.getPath(name);
		final List<String> lines = lines(file);
		final Set<String> hashes = new TreeSet<>();

		for (int index = 0; index < lines.size(); index++) {
			final String address = StringUtils.substringBefore(lines.get(index), COMMENT).strip();

			if (!address.isEmpty()) {
				hashes.add(hashOf(address, index + 1, file));
			}
		}

		final SporkData data = new SporkData();

		data.setApprovedLegacyAddresses(hashes);
		return validated(data, file);
	}

	/** Reports an unreadable file as a usage error, not a stack trace. */
	private List<String> lines(final Path file) {
		try {
			return Files.readAllLines(file);
		} catch (IOException ex) {
			throw usageError("Cannot read the legacy addresses from " + file + ": " + ex, ex);
		}
	}

	/** Names the line of an address that does not decode. */
	private String hashOf(final String address, final int number, final Path file) {
		try {
			return LegacyAddressHex.hashOf(address);
		} catch (IllegalArgumentException ex) {
			throw usageError("Line %d of %s is not a legacy address: %s"
				.formatted(number, file, ex.getMessage()), ex);
		}
	}

	/** Runs the same check a node makes, so a list the network would refuse is refused here. */
	private SporkData validated(final SporkData data, final Path file) {
		try {
			data.validate();
			return data;
		} catch (IllegalArgumentException ex) {
			throw usageError("The addresses in " + file + " are not accepted: " + ex.getMessage(), ex);
		}
	}

	/** A mistake in the file, which picocli shows with the usage rather than as a stack trace. */
	private ParameterException usageError(final String message, final Exception cause) {
		return new ParameterException(spec().commandLine(), message, cause);
	}
}
