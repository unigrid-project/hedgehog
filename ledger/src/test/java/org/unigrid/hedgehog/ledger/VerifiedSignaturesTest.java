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

import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class VerifiedSignaturesTest {
	@Example
	public void shouldRunTheCheckOnceForATransactionThatPassed() {
		final VerifiedSignatures cache = new VerifiedSignatures();
		final AtomicInteger runs = new AtomicInteger();

		assertThat(cache.check(mint(1, 5), () -> runs.incrementAndGet() > 0), is(true));
		assertThat(cache.check(mint(1, 5), () -> runs.incrementAndGet() > 0), is(true));
		assertThat(runs.get(), equalTo(1));
	}

	@Example
	public void shouldRunTheCheckAgainForATransactionThatFailed() {
		final VerifiedSignatures cache = new VerifiedSignatures();
		final AtomicInteger runs = new AtomicInteger();

		assertThat(cache.check(mint(1, 5), () -> runs.incrementAndGet() < 0), is(false));
		assertThat(cache.check(mint(1, 5), () -> runs.incrementAndGet() < 0), is(false));
		assertThat(runs.get(), equalTo(2));
		assertThat(cache.size(), equalTo(0));
	}

	@Example
	public void shouldForgetTheOldestOnceFull() {
		final VerifiedSignatures cache = new VerifiedSignatures();
		final AtomicInteger runs = new AtomicInteger();

		for (int i = 0; i <= VerifiedSignatures.CAPACITY; i++) {
			cache.check(mint(i, 1), () -> true);
		}

		assertThat(cache.size(), equalTo(VerifiedSignatures.CAPACITY));
		cache.check(mint(0, 1), () -> runs.incrementAndGet() > 0);
		assertThat(runs.get(), equalTo(1));
	}
}
