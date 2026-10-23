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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.crypto.ActiveKeys;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.BoardKeyChain;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.ProposedKeyChange;
import org.unigrid.hedgehog.model.util.TerminalText;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;

/* A change of the board keys in the file that board members pass between them, as JSON. The file may come from anyone,
   so what is read is bounded, holds keys and nothing else, and is a change that can be written again; and as nothing
   signs what the file says of the keys before the change, those are taken from the chain that it follows on. */
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

			Validate.isTrue(Objects.nonNull(proposed.getChange()), "it holds no change of the board keys");
			requireKeys(proposed.getBefore(), "the keys before the change");
			requireKeys(proposed.getChange().getActive(), "the keys after the change");
			requireApprovals(proposed.getChange().getApprovals());
			proposed.getChange().requireEncodable();
			return proposed;
		} catch (IOException | IllegalArgumentException ex) {
			throw new ParameterException(spec.commandLine(), "Cannot read the change of the board keys " + file
				+ ": " + TerminalText.printable(String.valueOf(ex.getMessage())), ex);
		}
	}

	private static void requireKeys(List<String> keys, String what) {
		Validate.isTrue(Objects.nonNull(keys) && keys.size() <= BoardKeyChain.MAX_KEYS, "%s are at most %d",
			what, BoardKeyChain.MAX_KEYS);
		Validate.isTrue(keys.stream().allMatch(Signature::isPublicKey), "%s are not all public keys of P-521 in"
			+ " lower-case hex", what);
		Validate.isTrue(isAscending(keys), "%s are not in ascending order without repeats", what);
	}

	private static void requireApprovals(List<KeyChange.Approval> approvals) {
		Validate.isTrue(Objects.nonNull(approvals) && approvals.size() <= BoardKeyChain.MAX_APPROVALS
			&& approvals.stream().allMatch(ChangeFile::isApproval), "the approvals are at most %d, each by a"
			+ " public key of P-521 in lower-case hex with a signature of at most %d bytes",
			BoardKeyChain.MAX_APPROVALS, KeyChange.MAX_SIGNATURE_SIZE);
		Validate.isTrue(isAscending(approvals.stream().map(KeyChange.Approval::getKey).toList()), "the approvals"
			+ " are not in ascending order of their keys without repeats");
	}

	private static boolean isApproval(KeyChange.Approval approval) {
		return Objects.nonNull(approval) && Signature.isPublicKey(approval.getKey())
			&& Objects.nonNull(approval.getSignature())
			&& approval.getSignature().length <= KeyChange.MAX_SIGNATURE_SIZE;
	}

	private static boolean isAscending(List<String> keys) {
		return IntStream.range(1, keys.size()).allMatch(i -> keys.get(i - 1).compareTo(keys.get(i)) < 0);
	}

	/* The chain of board keys of a spork database, or the keys built into the release when none is given or it holds
	   none */
	static List<KeyChange> chainOf(CommandSpec spec, Path sporkDb) {
		final BoardKeys held = Objects.isNull(sporkDb) ? null : SporkDatabaseFile.load(spec, sporkDb).getBoardKeys();

		return Objects.isNull(held) ? List.of() : held.<BoardKeys.SporkData>getData().getChanges();
	}

	/**
	* The change as the chain it follows on decides it: the keys before it are those that the chain makes active, from
	* the root of this machine, and the change follows on the head of the chain. A file that says otherwise, as one
	* that hides a key the change switches off, is refused before anything is signed.
	*/
	public static ProposedKeyChange onTheChain(CommandSpec spec, List<KeyChange> chain, ProposedKeyChange proposed) {
		final KeyChange change = proposed.getChange();
		final Set<String> before = verified(spec, chain).active();
		final Set<String> told = new TreeSet<>(proposed.getBefore());

		require(spec, change.getSequence() == chain.size(), "it is change " + change.getSequence() + " of its"
			+ " chain, which holds " + chain.size());
		require(spec, Arrays.equals(change.getPrevious(), BoardKeyChain.headOf(BoardKeyChain.root(), chain)),
			"it does not follow on the head of the chain of board keys it is given");
		require(spec, told.equals(before), "the keys active before it are " + shortly(before) + ", not "
			+ shortly(told) + " as the change file says");
		return new ProposedKeyChange(new ArrayList<>(before), change);
	}

	private static ActiveKeys verified(CommandSpec spec, List<KeyChange> chain) {
		try {
			return BoardKeyChain.verify(chain);
		} catch (IllegalArgumentException ex) {
			throw refused(spec, "the chain of board keys it follows on does not hold from the root of this"
				+ " machine: " + TerminalText.printable(ex.getMessage()));
		}
	}

	private static void require(CommandSpec spec, boolean holds, String reason) {
		if (!holds) {
			throw refused(spec, reason);
		}
	}

	private static ParameterException refused(CommandSpec spec, String reason) {
		return new ParameterException(spec.commandLine(), "The change of the board keys is refused: " + reason);
	}

	private static String shortly(Set<String> keys) {
		return keys.stream().map(ChangeFile::shortly).collect(Collectors.joining(", "));
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
