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

package org.unigrid.hedgehog.proxy.chain;

public enum Link {
	/* The next sequence number, linked to the head and not older than the entry before it. A feed started from
	   a chosen head never saw that entry's time, so the first entry after the start may be of any age */
	NEXT,
	/* Past the next sequence number, so at least one entry is missing in between */
	AHEAD,
	/* The head itself, seen again */
	DUPLICATE,
	/* Older than the head */
	REPLAYED,
	/* At the next sequence number or the head, but not linked to what came before or running back in time, or
	   the last sequence number, which nothing can follow */
	BROKEN,
	/* Signed by another writer */
	FOREIGN
}
