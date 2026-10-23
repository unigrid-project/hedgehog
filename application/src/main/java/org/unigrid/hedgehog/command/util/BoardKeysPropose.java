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

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import org.unigrid.hedgehog.model.crypto.ActiveKeys;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.BoardKeyChain;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.ProposedKeyChange;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/* A change follows on the chain it is proposed over, so it is proposed over the chain that the board goes by: that of
   a spork database of a node, or the keys built into the release when the network holds no board keys yet */
@Command(name = "propose",
	description = "Proposes a change of the board keys and writes it to --output, for board members to approve with"
		+ " util board-keys approve. The change follows on the chain of board keys of --spork-db, or on the keys"
		+ " built into the release when none is given or it holds none. It leaves 2 to 16 keys active."
)
public class BoardKeysPropose implements Callable<Integer> {
	@Option(names = "--spork-db", paramLabel = "<file>",
		description = "The spork database whose chain of board keys the change follows on."
	)
	private Path sporkDb;

	@Option(names = "--activate", paramLabel = "<hex>", description = "A public key that the change makes active.")
	private List<String> activate = new ArrayList<>();

	@Option(names = "--deactivate", paramLabel = "<hex>", description = "An active key that the change switches off.")
	private List<String> deactivate = new ArrayList<>();

	@Option(names = "--output", paramLabel = "<file>", required = true,
		description = "Where to write the change; the file must not exist."
	)
	private Path output;

	@Spec private CommandSpec spec;

	@Override
	public Integer call() {
		final List<KeyChange> chain = ChangeFile.chainOf(spec, sporkDb);
		final ActiveKeys before = verified(chain);
		final Set<String> after = after(before.active());
		final KeyChange change = new KeyChange(chain.size(), Instant.now().truncatedTo(ChronoUnit.MILLIS),
			BoardKeyChain.headOf(BoardKeyChain.root(), chain), new ArrayList<>(after), new ArrayList<>());
		final ProposedKeyChange proposed = new ProposedKeyChange(new ArrayList<>(before.active()), change);

		ChangeFile.writeNew(spec, output, proposed);
		System.out.println("sequence: " + change.getSequence());
		before.active().forEach(key -> System.out.println("before: " + key));
		after.forEach(key -> System.out.println("after: " + key));
		System.out.println("quorum: " + proposed.getQuorum() + " of the " + before.active().size()
			+ " keys active before it");
		proposed.getAdded().forEach(key -> System.out.println("approve too: " + key));
		System.out.println("output: " + output);
		return 0;
	}

	private ActiveKeys verified(List<KeyChange> chain) {
		try {
			final ActiveKeys keys = BoardKeyChain.verify(chain);

			require(chain.size() < BoardKeyChain.MAX_CHANGES, "The chain of board keys holds " + chain.size()
				+ " changes, the most it can; a release with a new root starts a new one");
			return keys;
		} catch (IllegalArgumentException ex) {
			throw refused("The board keys of " + sporkDb + " do not hold from the root of this machine: "
				+ ex.getMessage());
		}
	}

	private Set<String> after(Set<String> before) {
		final Set<String> after = new TreeSet<>(before);

		for (String key : activate) {
			require(Signature.isPublicKey(key), "--activate takes a public key of P-521 in lower-case hex, as"
				+ " util key-generate prints it");
			require(after.add(key), ChangeFile.shortly(key) + " is already active");
		}

		for (String key : deactivate) {
			require(after.remove(key), ChangeFile.shortly(key) + " is not active");
		}

		require(!after.equals(before), "The change changes nothing; give --activate or --deactivate");
		require(after.size() >= BoardKeyChain.MIN_KEYS && after.size() <= BoardKeyChain.MAX_KEYS, "A change leaves "
			+ BoardKeyChain.MIN_KEYS + " to " + BoardKeyChain.MAX_KEYS + " keys active, not " + after.size());
		return after;
	}

	private void require(boolean holds, String reason) {
		if (!holds) {
			throw refused(reason);
		}
	}

	private ParameterException refused(String reason) {
		return new ParameterException(spec.commandLine(), "Nothing was proposed: " + reason);
	}
}
