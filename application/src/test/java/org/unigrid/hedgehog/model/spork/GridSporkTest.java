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

package org.unigrid.hedgehog.model.spork;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;

public class GridSporkTest extends BaseMockedWeldTest {
	final GridSpork gridSpork = new GridSpork();

	@BeforeProperty
	public void before() {
		gridSpork.setTimeStamp(Instant.now());
	}

	@Example
	public void shouldBeNewerIfOtherIsNull() {
		assertThat(gridSpork.isNewerThan(null), is(true));
	}

	@Example
	public void shouldBeNewerIfOtherTimeStampIsNull() {
		final GridSpork otherGridSpork = new GridSpork();

		assertThat(gridSpork.isNewerThan(otherGridSpork), is(true));
	}

	@Example
	public void shouldBeNewerIfOtherIsOlder() {
		final GridSpork otherGridSpork = new GridSpork();
		otherGridSpork.setTimeStamp(gridSpork.getTimeStamp().minusSeconds(60));

		assertThat(gridSpork.isNewerThan(otherGridSpork), is(true));
	}

	@Example
	public void shouldNotBeNewerIfTimeStampIsNull() {
		final GridSpork otherGridSpork = new GridSpork();
		otherGridSpork.setTimeStamp(gridSpork.getTimeStamp());
		gridSpork.setTimeStamp(null);

		assertThat(gridSpork.isNewerThan(otherGridSpork), is(false));
	}

	@Example
	public void shouldNotBeNewerIfOtherIsNewer() {
		final GridSpork otherGridSpork = new GridSpork();
		otherGridSpork.setTimeStamp(gridSpork.getTimeStamp().plusSeconds(60));

		assertThat(gridSpork.isNewerThan(otherGridSpork), is(false));
	}

	@Example
	public void shouldNotBeNewerIfAllTimeStampsNull() {
		final GridSpork otherGridSpork = new GridSpork();
		otherGridSpork.setTimeStamp(null);
		gridSpork.setTimeStamp(null);

		assertThat(gridSpork.isNewerThan(otherGridSpork), is(false));
	}

	@Example
	public void shouldOnlyMoveTimeStampForwardOnRenewal() {
		final Instant signedAt = gridSpork.getTimeStamp().minusSeconds(60);
		final Instant previouslySignedAt = signedAt.minusSeconds(60);
		final MintSupply.SporkData data = new MintSupply.SporkData();

		gridSpork.setTimeStamp(signedAt);
		gridSpork.setPreviousTimeStamp(previouslySignedAt);
		gridSpork.setData(data);
		gridSpork.renew();

		assertThat(gridSpork.getTimeStamp(), greaterThan(signedAt));
		assertThat(gridSpork.getPreviousTimeStamp(), equalTo(previouslySignedAt));
		assertThat(gridSpork.getData(), sameInstance(data));
		assertThat(gridSpork.getPreviousData(), nullValue());
	}

	private static MintSupply supplyOf(long maxSupply, Instant timeStamp) {
		final MintSupply spork = new MintSupply();

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(maxSupply));
		spork.setTimeStamp(timeStamp);
		return spork;
	}

	@Example
	public void shouldStartTheFirstVersionOfASporkFromNothing() {
		final GridSpork first = GridSpork.nextVersion(GridSpork.Type.LEDGER_FEE, null, Optional.empty());

		assertThat(first, instanceOf(LedgerFee.class));
		assertThat(first.getPreviousTimeStamp(), equalTo(Instant.EPOCH));
		assertThat(first.getPreviousData(), equalTo(new LedgerFee.SporkData()));
		assertThat(first.getTimeStamp(), greaterThan(Instant.now().minusSeconds(60)));
	}

	@Example
	public void shouldStartTheNextVersionFromACopyOfTheStoredOne() {
		final Instant storedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
		final MintSupply stored = supplyOf(10, storedAt);
		final GridSpork next = GridSpork.nextVersion(GridSpork.Type.MINT_SUPPLY, stored, Optional.empty());

		assertThat(next, not(sameInstance(stored)));
		assertThat(next.getData(), equalTo(stored.getData()));
		assertThat(next.getData(), not(sameInstance(stored.getData())));
		assertThat(next.getPreviousData(), equalTo(stored.getData()));
		assertThat(next.getPreviousTimeStamp(), equalTo(storedAt));
		assertThat(next.getTimeStamp(), greaterThan(storedAt));
		assertThat(stored.getTimeStamp(), equalTo(storedAt));
	}

	/* Several changes can be proposed in a row and co-signed once */
	@Example
	public void shouldCarryOverTheDataOfAProposalThatAwaitsItsCosignature() {
		final MintSupply stored = supplyOf(10, Instant.now().minusSeconds(60));
		final MintSupply proposal = supplyOf(20, Instant.now().minusSeconds(30));
		final GridSpork next = GridSpork.nextVersion(GridSpork.Type.MINT_SUPPLY, stored, Optional.of(proposal));

		assertThat(next.getData(), equalTo(proposal.getData()));
		assertThat(next.getData(), not(sameInstance(proposal.getData())));
		assertThat(next.getPreviousData(), equalTo(stored.getData()));
	}

	/* A version made within the millisecond of the one it follows would not be newer, and no node would take it */
	@Example
	public void shouldBeNewerThanWhatItFollowsEvenWhenMadeInTheSameMillisecond() {
		final Instant ahead = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
		final MintSupply stored = supplyOf(10, ahead);
		final MintSupply proposal = supplyOf(20, ahead.plusMillis(5));

		assertThat(GridSpork.nextVersion(GridSpork.Type.MINT_SUPPLY, stored, Optional.empty()).getTimeStamp(),
			equalTo(ahead.plusMillis(1)));
		assertThat(GridSpork.nextVersion(GridSpork.Type.MINT_SUPPLY, stored, Optional.of(proposal)).getTimeStamp(),
			equalTo(ahead.plusMillis(6)));
	}
}
