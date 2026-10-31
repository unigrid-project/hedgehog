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

package org.unigrid.hedgehog.command;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/* What a command exits with, so that a script can tell how it went. Picocli exits with UNUSABLE on a usage error. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ExitCode {
	public static final int OK = 0;

	/* The command could not do what it was asked: the node or a check refused it, the node could not be reached, a
	   read came back short, or there was nothing to act on */
	public static final int FAILED = 1;

	/* What the command was given cannot be used: a file that cannot be read or written, an argument that names
	   nothing, or a key that nothing would accept */
	public static final int UNUSABLE = 2;
}
