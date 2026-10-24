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
import java.security.GeneralSecurityException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import org.apache.commons.codec.binary.Hex;
import org.unigrid.hedgehog.command.option.BoardKeyOptions;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.ProposedKeyChange;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/* An approval covers what the change decides, so that is shown in full before it is signed, with the keys before it as
   the chain it follows on makes them active: nothing signs what the change file says of those */
@Command(name = "approve",
	description = "Approves a change of the board keys with a board key that is active before it or that the change"
		+ " adds, and writes the approval into the change file. Every key that the change adds approves it too. The"
		+ " change is judged against the chain of board keys of --spork-db, which it follows on."
)
public class BoardKeysApprove implements Callable<Integer> {
	@Parameters(index = "0", paramLabel = "<change>", description = "The change file that util board-keys propose"
		+ " wrote.")
	private Path changeFile;

	@Option(names = "--spork-db", paramLabel = "<file>", required = true,
		description = "A spork database that holds the chain of board keys that the change follows on, or no board"
			+ " keys when it is the first change of the network."
	)
	private Path sporkDb;

	@Mixin
	private BoardKeyOptions boardKey;

	@Spec private CommandSpec spec;

	@Override
	public Integer call() {
		final ProposedKeyChange read = ChangeFile.read(spec, changeFile);
		final ProposedKeyChange proposed = ChangeFile.onTheChain(spec, ChangeFile.chainOf(spec, sporkDb), read);
		final KeyChange change = proposed.getChange();
		final Set<String> approvers = new TreeSet<>(proposed.getBefore());

		approvers.addAll(proposed.getAdded());

		final String key = boardKey.resolve();

		if (!NetworkKey.isOneOf(key, approvers)) {
			throw new ParameterException(spec.commandLine(), "The board key is neither active before the change"
				+ " nor added by it, so it does not approve it");
		}

		show(proposed);
		approve(change, key, approvers);
		ChangeFile.replace(spec, changeFile, proposed);

		final List<String> approved = change.getApprovals().stream().map(KeyChange.Approval::getKey).toList();

		System.out.println("approvals: " + approved.stream().filter(proposed.getBefore()::contains).count()
			+ " of the " + proposed.getQuorum() + " that the keys before it need");
		System.out.println("added keys that approved it: " + approved.stream().filter(proposed.getAdded()::contains)
			.count() + " of " + proposed.getAdded().size());
		return 0;
	}

	private static void show(ProposedKeyChange proposed) {
		final KeyChange change = proposed.getChange();

		System.out.println("sequence: " + change.getSequence());
		System.out.println("made at: " + change.getMadeAt());
		System.out.println("follows on: " + ChangeFile.shortly(Hex.encodeHexString(change.getPrevious())));
		proposed.getBefore().forEach(key -> System.out.println("before: " + key));
		change.getActive().forEach(key -> System.out.println("after: " + key));
	}

	/* Of the same key, an approval takes the place of the one before it */
	private void approve(KeyChange change, String key, Set<String> approvers) {
		try {
			final byte[] signable = change.getSignable();
			final byte[] signature = new Signature(Optional.of(key), Optional.empty()).signInOneForm(signable);
			final String approver = approvers.stream()
				.filter(candidate -> Signature.isSignedBy(candidate, signable, signature)).findFirst()
				.orElseThrow();
			final List<KeyChange.Approval> approvals = change.getApprovals();

			approvals.removeIf(approval -> approver.equals(approval.getKey()));
			approvals.add(new KeyChange.Approval(approver, signature));
			approvals.sort(Comparator.comparing(KeyChange.Approval::getKey));
		} catch (GeneralSecurityException | SigningException ex) {
			throw new ParameterException(spec.commandLine(), "The change cannot be approved: " + ex.getMessage(),
				ex);
		}
	}
}
