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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.Builder;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import org.unigrid.hedgehog.s3.index.Upload;

/* A lifecycle rule as the sweeper applies it, where a count of zero days stands for an action the rule does not
   take. Days count from a moment rounded up to the next midnight UTC, as S3 counts them. */
@Builder
public record Rule(String id, boolean enabled, Filter filter, int expirationDays, Instant expirationDate,
	boolean expiredDeleteMarkers, int noncurrentDays, int newerNoncurrentVersions, int abortDays) {

	/* Whether the rule expires the version while it is the current version of its key */
	public boolean expires(final ObjectVersion version, final Instant now) {
		return filter.matches(version) && (expirationDays > 0 && due(version.lastModified(), expirationDays, now)
			|| Objects.nonNull(expirationDate) && !now.isBefore(expirationDate));
	}

	/* Whether the rule removes a delete marker that is the only version of its key, which a rule expiring
	   versions after some days does as well */
	public boolean removesMarker(final ObjectVersion marker, final Instant now) {
		return filter.matches(marker) && (expiredDeleteMarkers || expirationDays > 0
			&& due(marker.lastModified(), expirationDays, now));
	}

	/* Whether the rule removes a version that has been noncurrent since the instant, with the given number of
	   noncurrent versions of its key newer than it */
	public boolean expiresNoncurrent(final ObjectVersion version, final Instant since, final int newer,
		final Instant now) {

		return noncurrentDays > 0 && newer >= newerNoncurrentVersions && filter.matches(version)
			&& due(since, noncurrentDays, now);
	}

	/* An upload is aborted by the prefix of the rule alone, since it has no tags or size to filter by yet */
	public boolean aborts(final Upload upload, final Instant now) {
		return abortDays > 0 && upload.key().startsWith(filter.prefix()) && due(upload.initiated(), abortDays, now);
	}

	private static boolean due(final Instant from, final int days, final Instant now) {
		return !now.isBefore(from.truncatedTo(ChronoUnit.DAYS).plus(days + 1L, ChronoUnit.DAYS));
	}
}
