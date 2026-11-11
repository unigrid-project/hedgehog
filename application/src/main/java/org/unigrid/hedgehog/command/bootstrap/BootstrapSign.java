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

package org.unigrid.hedgehog.command.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.unigrid.hedgehog.command.ExitCode;
import org.unigrid.hedgehog.model.option.SnapshotOptions;
import org.unigrid.hedgehog.command.util.SporkDatabaseFile;
import org.unigrid.hedgehog.model.bootstrap.SignatureStatus;
import org.unigrid.hedgehog.model.bootstrap.SnapshotInspector;
import org.unigrid.hedgehog.model.bootstrap.SnapshotSignature;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.spork.BoardKeysAuthority;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "sign",
	description = "Sign a snapshot so others can verify it came from the foundation."
)
public class BootstrapSign implements Callable<Integer> {
	@Option(names = { "-k", "--key" }, required = true,
		description = "Hex representation of the private key signing the snapshot."
	)
	private String key;

	@Option(names = "--force", description = "Replace an existing signature.")
	private boolean force;

	@Option(names = "--spork-db", paramLabel = "<file>", description = "A spork database whose chain of board keys"
		+ " the key is judged by, as the util commands judge it, when it holds more of the chain than this machine"
		+ " verified before.")
	private Path sporkDb;

	@Spec
	private CommandSpec spec;

	@Override
	public Integer call() throws IOException, SigningException {
		final Path snapshot = SnapshotOptions.getSnapshot();

		if (!Files.exists(snapshot)) {
			System.err.println("There is no snapshot at " + snapshot);
			return ExitCode.FAILED;
		}

		goByTheBoardKeys();

		if (!NetworkKey.isTrusted(key)) {
			System.err.println("That key is not one the network trusts, so nothing would accept"
				+ " the result");
			return ExitCode.UNUSABLE;
		}

		final String problem = unsignableReason(snapshot);

		if (problem != null) {
			System.err.println(problem);
			return ExitCode.UNUSABLE;
		}

		if (SnapshotSignature.read(snapshot).isPresent() && !force) {
			System.err.println(snapshot + " is already signed, pass --force to replace it");
			return ExitCode.FAILED;
		}

		SnapshotSignature.signAndReplace(snapshot, key);
		System.out.println("Signed " + snapshot);
		return ExitCode.OK;
	}

	/* The board keys are those of the longest chain of them that this machine knows: the one the release ships, the
	   one it verified since, or that of the database given, as the util commands judge it, since the board may have
	   switched a key off or added one after the release */
	private void goByTheBoardKeys() throws IOException {
		BoardKeysAuthority.installTheRecordOfThisMachine();

		if (Objects.nonNull(sporkDb)) {
			SporkDatabaseFile.requireRecorded(spec, SporkDatabaseFile.load(spec, sporkDb), sporkDb);
		}
	}

	/*
	   Signing is what turns a file into an authenticated artefact, so refuse anything this build
	   cannot read rather than vouching for bytes it does not understand. Returns null when there is
	   nothing wrong with the file.
	*/
	private static String unsignableReason(Path snapshot) {
		final SignatureStatus status;

		try {
			status = SnapshotInspector.validate(snapshot);

		} catch (IOException ex) {
			return FileFailure.describe(ex);
		}

		return status == SignatureStatus.INVALID ? snapshot + " already carries a signature that"
			+ " does not verify, so its contents cannot be trusted enough to sign" : null;
	}
}
