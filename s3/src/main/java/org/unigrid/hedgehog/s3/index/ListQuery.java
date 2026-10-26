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

package org.unigrid.hedgehog.s3.index;

import java.util.Objects;
import lombok.Builder;

/* Lists the keys under the prefix, rolled up into common prefixes at the first delimiter past the prefix when a
   delimiter is given. The listing starts after the key marker, or with an id marker inside the versions or uploads
   of that key after the one the id names, and holds at most limit entries and common prefixes together. Empty
   strings mean none. */
@Builder(toBuilder = true)
public record ListQuery(String prefix, String delimiter, String keyMarker, String idMarker, int limit) {
	public ListQuery {
		prefix = Objects.requireNonNullElse(prefix, "");
		delimiter = Objects.requireNonNullElse(delimiter, "");
		keyMarker = Objects.requireNonNullElse(keyMarker, "");
		idMarker = Objects.requireNonNullElse(idMarker, "");
	}
}
