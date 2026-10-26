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

import java.time.Duration;
import java.time.Instant;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class RetentionTest {
	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	private static Retention retention(final RetentionMode mode, final long seconds) {
		return new Retention(mode, NOW.plusSeconds(seconds));
	}

	private static ObjectVersion version(final Retention retention, final boolean legalHold) {
		return ObjectVersion.builder().bucket("bucket").key("key").lastModified(Instant.EPOCH).retention(retention)
			.legalHold(legalHold).build();
	}

	@Property
	public void holdsUntilItsDateWhereOnlyGovernanceYieldsToABypass(@ForAll final RetentionMode mode,
		@ForAll @LongRange(min = -1000, max = 1000) final long seconds, @ForAll final boolean bypass) {

		final boolean running = seconds > 0;
		final boolean holds = running && (mode == RetentionMode.COMPLIANCE || !bypass);

		assertThat(retention(mode, seconds).holds(NOW, bypass), equalTo(holds));
		assertThat(version(retention(mode, seconds), false).locked(NOW, bypass), equalTo(holds));
	}

	@Property
	public void locksAVersionUnderLegalHoldWhateverItsRetention(@ForAll final boolean bypass) {
		assertThat(version(null, true).locked(NOW, bypass), equalTo(true));
		assertThat(version(retention(RetentionMode.GOVERNANCE, -1), true).locked(NOW, bypass), equalTo(true));
		assertThat(version(null, false).locked(NOW, bypass), equalTo(false));
	}

	/* A running retention only grows, in its own mode, unless it is governance and the request bypasses it */
	@Property
	public void yieldsWhileRunningOnlyToALongerRetentionOfTheSameMode(@ForAll final RetentionMode mode,
		@ForAll final RetentionMode next, @ForAll @LongRange(min = -100, max = 100) final long change,
		@ForAll final boolean bypass) {

		final Retention current = retention(mode, Duration.ofDays(1).toSeconds());
		final Retention replacement = new Retention(next, current.until().plusSeconds(change));
		final boolean bypassed = bypass && mode == RetentionMode.GOVERNANCE;

		assertThat(current.yieldsTo(replacement, NOW, bypass), equalTo(bypassed || next == mode && change >= 0));
		assertThat(current.yieldsTo(null, NOW, bypass), equalTo(bypassed));
		assertThat(retention(mode, -1).yieldsTo(null, NOW, bypass), equalTo(true));
		assertThat(retention(mode, 0).yieldsTo(replacement, NOW, bypass), equalTo(true));
	}
}
