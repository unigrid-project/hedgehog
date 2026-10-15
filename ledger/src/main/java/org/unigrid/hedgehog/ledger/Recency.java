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

package org.unigrid.hedgehog.ledger;

import java.util.OptionalLong;

/* When something last happened, and whether that was lately. A clock that was set back makes it recent, which is the
   safe side for what is paced by it. */
final class Recency {
	private OptionalLong last = OptionalLong.empty();

	void mark(long now) {
		last = OptionalLong.of(now);
	}

	boolean within(long now, long windowMillis) {
		return last.isPresent() && now - last.getAsLong() < windowMillis;
	}
}
