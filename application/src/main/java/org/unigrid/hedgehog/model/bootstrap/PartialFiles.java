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

package org.unigrid.hedgehog.model.bootstrap;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
   A snapshot is written to a partial file beside its destination and moved into place once it is
   checked. A kill or an exit skips the removal of that file, so the files an earlier process left
   in the directory are removed before the next one is made: one can be as large as a snapshot, and
   nothing else would ever remove it.

   A file that is still being written has to stay where it is. The node downloads when it starts
   without a snapshot while the fetch command installs to the same place by default, and the one
   that lost its file would only find out at the end of its transfer. So the name of a file carries
   the id of the process that writes it. A file of another process stays for as long as a process
   of that id runs, and a file of this process for as long as it is written here: an earlier process
   can have had the same id, as a container gives the node the same one at every start. A name
   without an id is that of a release that did not put one in, and such a file is taken for left
   behind. The id says nothing to a process that cannot see the one that writes, on another machine
   or in another container that shares the directory, where a file in use still looks left behind.

   Only a regular file with exactly a name that is made here is removed: the prefix, the id, the
   number Files.createTempFile adds and the suffix. The directory can be one the operator chose,
   in which other programs keep files that merely begin and end alike.
*/
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class PartialFiles {
	private static final String OWNER = "pid";
	/* An id of more digits is none that every platform can look up, so a name with one was not made here */
	private static final String OWNER_AND_NUMBER = "(?:" + OWNER + "([1-9][0-9]{0,8})-)?[0-9]+";
	private static final long THIS_PROCESS = ProcessHandle.current().pid();
	private static final Set<Path> WRITTEN_HERE = new HashSet<>();

	/*
	   The directory is cleared and the file is made and noted in one step, so no other caller in this
	   process clears the directory between the making of a file and its noting.
	*/
	static synchronized Path create(Path directory, String prefix, String suffix) throws IOException {
		final Pattern made = Pattern.compile(Pattern.quote(prefix) + OWNER_AND_NUMBER + Pattern.quote(suffix));

		removeLeftBehind(directory, made);

		final Path partial = Files.createTempFile(directory, prefix + OWNER + THIS_PROCESS + "-", suffix);

		WRITTEN_HERE.add(partial);
		return partial;
	}

	/* Cleanup must never replace the failure that caused it with one about the leftover file. */
	static synchronized void discard(Path partial) {
		WRITTEN_HERE.remove(partial);
		delete(partial);
	}

	private static void removeLeftBehind(Path directory, Pattern made) {
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
			for (final Path entry : entries) {
				if (isLeftBehind(entry, made.matcher(entry.getFileName().toString()))) {
					delete(entry);
				}
			}
		} catch (IOException | DirectoryIteratorException ex) {
			log.atWarn().log("Could not look for partial files in {}: {}", directory, ex.getMessage());
		}
	}

	private static boolean isLeftBehind(Path entry, Matcher name) {
		return name.matches() && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && !isInUse(entry, name);
	}

	/* A name without an id names no process, and nothing else tells whether such a file is in use */
	private static boolean isInUse(Path partial, Matcher name) {
		return Optional.ofNullable(name.group(1)).map(Long::parseLong)
			.filter(process -> isWriting(process, partial)).isPresent();
	}

	private static boolean isWriting(long process, Path partial) {
		return process == THIS_PROCESS ? WRITTEN_HERE.contains(partial)
			: ProcessHandle.of(process).isPresent();
	}

	private static void delete(Path partial) {
		try {
			Files.deleteIfExists(partial);
		} catch (IOException ex) {
			log.atWarn().log("Could not remove the partial file {}: {}", partial, ex.getMessage());
		}
	}
}
