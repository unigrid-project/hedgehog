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

import java.nio.file.FileSystemException;
import java.nio.file.Path;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.command.util.RestClientCommand;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class FileFailure {
	private static final String OPERATION = "Cannot access";
	private static final String MOVE = "Cannot write";

	/* The file system names a file that is missing, or that the user may not use, by its path alone. Any other failure
	   has a message of its own. A failure that names two files is that of the move that puts a snapshot in place: the
	   first is the partial file, which the user never gave and which is gone by the time the line is read, and the
	   second is where the snapshot was to go. */
	static String describe(Exception ex) {
		if (ex instanceof FileSystemException failed && failed.getOtherFile() != null) {
			return RestClientCommand.describe(MOVE, Path.of(failed.getOtherFile()), failed);
		}

		if (ex instanceof FileSystemException failed && failed.getFile() != null) {
			return RestClientCommand.describe(OPERATION, Path.of(failed.getFile()), failed);
		}

		return ex.getMessage();
	}
}
