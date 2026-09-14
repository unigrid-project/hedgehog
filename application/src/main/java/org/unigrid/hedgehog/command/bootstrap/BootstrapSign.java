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
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import lombok.Cleanup;
import org.unigrid.hedgehog.command.option.SnapshotOptions;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDigest;
import org.unigrid.hedgehog.model.bootstrap.SnapshotSignature;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.SigningException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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

	@Override
	public Integer call() throws IOException, SigningException {
		final Path snapshot = SnapshotOptions.getSnapshot();

		if (!Files.exists(snapshot)) {
			System.err.println("There is no snapshot at " + snapshot);
			return 1;
		}

		if (!NetworkKey.isTrusted(key)) {
			System.err.println("That key is not one the network trusts, so nothing would accept"
				+ " the result");
			return 2;
		}

		if (SnapshotSignature.read(snapshot).isPresent() && !force) {
			System.err.println(snapshot + " is already signed, pass --force to replace it");
			return 1;
		}

		truncateToContent(snapshot);
		SnapshotSignature.signAndAppend(snapshot, key);
		System.out.println("Signed " + snapshot);
		return 0;
	}

	private static void truncateToContent(Path snapshot) throws IOException {
		final long content = SnapshotDigest.contentLengthOf(snapshot);

		if (Files.size(snapshot) > content) {
			@Cleanup final FileChannel channel = FileChannel.open(snapshot,
				StandardOpenOption.WRITE);

			channel.truncate(content);
		}
	}
}
