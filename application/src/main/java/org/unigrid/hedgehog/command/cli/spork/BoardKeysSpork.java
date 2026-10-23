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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.client.rest.SporkClient;
import org.unigrid.hedgehog.client.rest.UntrustedSporkException;
import org.unigrid.hedgehog.command.util.ChangeFile;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.ProposedKeyChange;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/* The change is judged against the chain of the node, which it follows on, and it takes effect with a head that a key
   it makes active signs: a key that it adds may sign it, and a key that it switches off may not */
@Command(name = "board-keys",
	description = "The chain of changes of the board keys; to change them, the data is the change file that the board"
		+ " approved with util board-keys approve, and the key is one that the change makes active."
)
public class BoardKeysSpork extends SporkCommand<ProposedKeyChange> {
	@Option(names = "--allow-uncounted", description = "Switch off keys that signed sporks the node holds, which"
		+ " then count for nothing until keys that stay sign them again; renew them first to keep them counting.")
	private boolean allowUncounted;

	private final FileSystem fileSystem;

	public BoardKeysSpork() {
		this(FileSystems.getDefault());
	}

	BoardKeysSpork(final FileSystem fileSystem) {
		this.fileSystem = fileSystem;
	}

	@Override
	protected String location() {
		return "/gridspork/board-keys";
	}

	@Override
	protected GridSpork.Type type() {
		return GridSpork.Type.BOARD_KEYS;
	}

	@Override
	protected ProposedKeyChange read(final String name) {
		return ChangeFile.read(spec(), fileSystem.getPath(name));
	}

	@Override
	protected void apply(final ProposedKeyChange proposed, final GridSpork spork) {
		spork.<BoardKeys.SporkData>getData().getChanges().add(proposed.getChange());
	}

	@Override
	protected void propose(final GridSporkSet set, final ProposedKeyChange proposed) {
		SporkProposal.propose(spec().commandLine(), type(), spork -> apply(proposed, spork), set.getBoardKey(),
			key -> NetworkKey.isOneOf(key, proposed.getChange().getActive()),
			sporks -> requireCounting(sporks, proposed), set.isFirstVersion());
	}

	/* A spork that a key signed which the change switches off would count for nothing until it is signed again, so
	   the change is refused while the node holds one, unless the board means it to */
	private void requireCounting(final SporkClient sporks, final ProposedKeyChange read)
		throws ResponseOddityException, UntrustedSporkException, IOException {

		final List<KeyChange> chain = sporks.stored(GridSpork.Type.BOARD_KEYS)
			.map(held -> held.<BoardKeys.SporkData>getData().getChanges()).orElse(List.of());
		final Set<String> removed = ChangeFile.onTheChain(spec(), chain, read).getRemoved();
		final List<String> uncounted = new ArrayList<>();

		for (GridSpork.Type type : GridSpork.Type.values()) {
			if (type != GridSpork.Type.UNDEFINED && type != GridSpork.Type.BOARD_KEYS) {
				sporks.held(type).filter(GridSpork::isTakenByTheNetwork)
					.filter(spork -> isSignedByAnyOf(spork, removed))
					.ifPresent(spork -> uncounted.add(type + " " + SporkClient.signingOf(spork)));
			}
		}

		requireNone(uncounted);
	}

	private static boolean isSignedByAnyOf(final GridSpork spork, final Set<String> keys) {
		return SporkClient.signersOf(spork).stream().flatMap(Optional::stream).anyMatch(keys::contains);
	}

	private void requireNone(final List<String> uncounted) {
		if (!uncounted.isEmpty() && !allowUncounted) {
			throw new IllegalArgumentException("the change switches off keys that signed sporks the node holds,"
				+ " which would count for nothing until keys that stay sign them again: "
				+ String.join("; ", uncounted) + ". Renew them first with keys that stay, or give"
				+ " --allow-uncounted");
		}
	}
}
