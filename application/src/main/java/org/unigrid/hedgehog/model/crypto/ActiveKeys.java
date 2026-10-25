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

package org.unigrid.hedgehog.model.crypto;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
* The board keys after a number of changes to them: the keys that sign sporks, and every key that ever did, which
* signature logs may name. A key that is known and no longer active authorizes nothing, but the history it signed
* stays readable.
*/
public record ActiveKeys(Set<String> active, Set<String> known, int length) {
	public ActiveKeys {
		active = Collections.unmodifiableSet(new TreeSet<>(active));
		known = Collections.unmodifiableSet(new TreeSet<>(known));
	}

	/* The keys built into the release, before any change */
	public static ActiveKeys of(Collection<String> builtIn) {
		return new ActiveKeys(new TreeSet<>(builtIn), new TreeSet<>(builtIn), 0);
	}

	public ActiveKeys after(Collection<String> next) {
		final Set<String> everActive = new TreeSet<>(known);

		everActive.addAll(next);
		return new ActiveKeys(new TreeSet<>(next), everActive, length + 1);
	}

	public Set<String> inactive() {
		final Set<String> inactive = new TreeSet<>(known);

		inactive.removeAll(active);
		return Collections.unmodifiableSet(inactive);
	}
}
