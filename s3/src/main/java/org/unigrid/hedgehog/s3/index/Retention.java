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

import java.time.Instant;
import java.util.Objects;

/* How long Object Lock keeps a version. Compliance yields to nothing before its date, and governance to a request
   that bypasses it. */
public record Retention(RetentionMode mode, Instant until) {
	public Retention {
		Objects.requireNonNull(mode);
		Objects.requireNonNull(until);
	}

	public boolean holds(final Instant at, final boolean bypass) {
		return until.isAfter(at) && (mode == RetentionMode.COMPLIANCE || !bypass);
	}

	/* Whether the retention may be replaced by the next one, where none lifts it. One that holds only grows, and only
	   in its own mode. */
	public boolean yieldsTo(final Retention next, final Instant at, final boolean bypass) {
		return !holds(at, bypass) || Objects.nonNull(next) && next.mode == mode && !next.until.isBefore(until);
	}
}
