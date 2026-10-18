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

import java.time.Clock;
import java.time.Duration;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class NamespaceConfig {
	private static final long MEBIBYTE = 1L << 20;

	/* A flush follows once the memtable holds this much or its oldest write is this old, whichever comes first.
	   The journal only lives on this node, so the interval bounds the writes a lost node takes with it. */
	@Builder.Default private final long flushBytes = 16 * MEBIBYTE;
	@Builder.Default private final Duration flushInterval = Duration.ofSeconds(30);

	/* How often the background maintenance looks for work, where zero leaves it to whoever calls maintain() */
	@Builder.Default private final Duration maintenanceInterval = Duration.ofSeconds(5);

	@Builder.Default private final int pageBytes = 256 * 1024;
	@Builder.Default private final int bloomBitsPerKey = 10;
	@Builder.Default private final long cacheBytes = 32 * MEBIBYTE;

	/* The largest run page, run index or manifest a read accepts, so a damaged or forged blob cannot make the index
	   allocate without bound. It must stay above the page size plus the largest record. */
	@Builder.Default private final long maxBlockBytes = 256 * MEBIBYTE;

	/* How long a superseded run, manifest or released object blob survives, which must outlast the longest read
	   that may still be using it */
	@Builder.Default private final Duration grace = Duration.ofHours(1);

	/* Above this many runs a compaction merges all of them, whatever their sizes */
	@Builder.Default private final int maxRuns = 12;

	@Builder.Default private final Clock clock = Clock.systemUTC();
}
