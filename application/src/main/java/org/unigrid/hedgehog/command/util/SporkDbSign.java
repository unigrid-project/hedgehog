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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.command.option.BoardKeyOptions;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.spork.ChunkData;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.SignatureLog;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.util.TerminalText;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/* Signing takes two runs, one for each board key, so the two keys never have to be on one machine. The data of every
   spork is shown in full before anything is signed, as the one who signs vouches for all of it. */
@Command(name = "sign",
	description = "Signs a spork database with a board key and writes the result to --output. A spork that no"
		+ " current board key signed becomes a first version of the same data, made now and signed with the key; a"
		+ " spork that another board key signed once is co-signed; a spork that two board keys signed is left as it"
		+ " is. A second board member runs the command on the output with their own key."
)
public class SporkDbSign implements Runnable {
	private static final ObjectWriter JSON = new JsonConfiguration().getContext(ChunkData.class)
		.writerWithDefaultPrettyPrinter();

	@Parameters(index = "0", paramLabel = "<file>", description = "The spork database to sign.")
	private Path file;

	@Option(names = "--output", paramLabel = "<file>", required = true,
		description = "Where to write the signed spork database; the file must not exist."
	)
	private Path output;

	@Mixin
	private BoardKeyOptions boardKey;

	@Spec private CommandSpec spec;

	@Override
	public void run() {
		final String key = boardKey.resolve(NetworkKey::isTrusted);

		if (Files.exists(output)) {
			throw refused("--output " + output + " exists and is not overwritten");
		}

		final SporkDatabase database = SporkDatabaseFile.load(spec, file);
		final List<GridSpork> versions = new ArrayList<>();

		for (GridSpork spork : SporkDatabaseFile.sporksOf(database)) {
			SporkDatabaseFile.contentProblem(spork).ifPresent(problem -> {
				throw refused("Nothing was signed: the content of the " + spork.getType()
					+ " spork does not hold: " + problem);
			});

			versions.add(versionToSign(spork));
		}

		for (GridSpork version : versions) {
			show(version);
			System.out.println(version.getType() + ": " + signed(version, key));
			database.set(version);
		}

		write(database);
		System.out.println("output: " + output);
	}

	/* A spork that a board key signed once is co-signed only in the shape this command gives a first version, which
	   holds nothing but its data: anything else, a proposal over a version this file does not hold among them, is
	   judged against the spork the network holds, which only a node knows */
	private GridSpork versionToSign(GridSpork spork) {
		if (spork.isTakenByTheNetwork()) {
			return spork;
		}

		if (spork.isPending() && spork.isValidSignature()) {
			if (isFirstVersion(spork)) {
				return spork;
			}

			throw refused("Nothing was signed: the " + spork.getType() + " spork is signed once and is no"
				+ " first version of its data, which is all this command co-signs; co-sign a proposal over a"
				+ " stored spork with gridspork-cosign");
		}

		return GridSpork.firstVersionOf(spork);
	}

	private static boolean isFirstVersion(GridSpork spork) {
		final ChunkData empty = spork.<ChunkData>getData().empty();

		return (spork.getFlags() & GridSpork.Flag.WIRE_SIGNABLE.getValue()) != 0
			&& Instant.EPOCH.equals(spork.getPreviousTimeStamp()) && spork.getSignatureLog().isEmpty()
			&& empty.equals(Objects.requireNonNullElse(spork.getPreviousData(), empty));
	}

	/* Every field the signature covers, so that nothing signed goes unseen */
	private void show(GridSpork version) {
		final String type = version.getType().toString();
		final SignatureLog log = version.getSignatureLog();

		print(type + " time stamp: " + version.getTimeStamp());
		print(type + " previous time stamp: " + version.getPreviousTimeStamp());
		print(type + " flags: " + version.getFlags());
		print(type + " data:\n" + json(version, version.getData()));
		print(type + " previous data:\n" + json(version, version.getPreviousData()));
		print(type + " signature log: " + log.size() + " entries, head " + (log.isEmpty() ? "none"
			: StringUtils.left(Hex.encodeHexString(log.headHash()), SporkDatabaseFile.SHOWN_HEX)));

		if (Objects.nonNull(version.getSignature()) && version.isPending()) {
			print(type + " first signer: " + SporkDatabaseFile.keyOf(version, version.getSignature()));
		}
	}

	private static void print(String text) {
		System.out.println(TerminalText.printable(text));
	}

	private String json(GridSpork version, ChunkData data) {
		try {
			return JSON.writeValueAsString(data);
		} catch (JsonProcessingException ex) {
			throw refused("Nothing was signed: the data of the " + version.getType() + " spork cannot be shown: "
				+ ex.getMessage());
		}
	}

	/* Only a spork whose signature and log the current keys vouch for is co-signed: anything else could carry a
	   history that no board made, and is begun again from its data */
	private String signed(GridSpork version, String key) {
		try {
			if (version.isTakenByTheNetwork()) {
				return "signed by the board, left as it is";
			}

			if (Objects.nonNull(version.getSignature())) {
				version.cosign(key);
				return "co-signed by " + SporkDatabaseFile.keyOf(version, version.getCosignature());
			}

			version.sign(key);
			return "a first version of its data, made at " + version.getTimeStamp() + " and signed by "
				+ SporkDatabaseFile.keyOf(version, version.getSignature());
		} catch (SigningException ex) {
			throw refused("Nothing was signed: the " + version.getType() + " spork cannot be signed: "
				+ ex.getMessage());
		}
	}

	private void write(SporkDatabase database) {
		try {
			SporkDatabase.persist(output, database);
		} catch (IOException ex) {
			throw refused("Cannot write the spork database " + output + ": " + ex.getMessage());
		}
	}

	private ParameterException refused(String reason) {
		return new ParameterException(spec.commandLine(), reason);
	}
}
