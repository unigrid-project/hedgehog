/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.proxy.chain;

public enum Admission {
	ACCEPTED,
	/* Past a gap in its feed, so it waits for the missing entries */
	AHEAD,
	DUPLICATE,
	REPLAYED,
	BROKEN,
	WRONG_CHAIN,
	TOO_LARGE,
	/* Timestamped further ahead of the local clock than the retention window */
	FUTURE,
	/* Timestamped further back than the retention window, so it would be trimmed at once */
	EXPIRED,
	NOT_AUTHENTIC,
	/* No room: a sealed entry while the chain is at capacity, or any entry the buffer for out-of-order ones cannot
	   take. The host turns it into a pause for the writer */
	FULL
}
