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

package org.unigrid.hedgehog.s3.lifecycle;

import java.util.Map;
import org.unigrid.hedgehog.s3.index.ObjectVersion;

/* The versions a lifecycle rule applies to: those under the prefix that carry all the tags, with a size strictly
   between the bounds */
public record Filter(String prefix, Map<String, String> tags, long greaterThan, long lessThan) {
	public static final Filter ALL = new Filter("", Map.of(), -1, Long.MAX_VALUE);

	public Filter {
		tags = Map.copyOf(tags);
	}

	public boolean matches(final ObjectVersion version) {
		return version.key().startsWith(prefix) && version.tags().entrySet().containsAll(tags.entrySet())
			&& version.size() > greaterThan && version.size() < lessThan;
	}
}
