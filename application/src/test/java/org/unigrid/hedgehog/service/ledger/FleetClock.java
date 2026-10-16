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

package org.unigrid.hedgehog.service.ledger;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/* The clock of every node of a fleet: real time, which goes on by itself, and a step a test adds to make what is due
   come sooner than the test could wait for it. The nodes share it, so a step is the same moment for all of them. */
public final class FleetClock implements LongSupplier {
	private final AtomicLong step = new AtomicLong();

	@Override
	public long getAsLong() {
		return System.currentTimeMillis() + step.get();
	}

	public long now() {
		return getAsLong();
	}

	public void advance(final Duration by) {
		step.addAndGet(by.toMillis());
	}
}
