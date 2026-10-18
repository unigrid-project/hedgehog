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

package org.unigrid.hedgehog.model.gridnode;

import java.util.List;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.crypto.Signature;
import static org.unigrid.hedgehog.model.gridnode.GridnodeFixtures.copyOf;
import static org.unigrid.hedgehog.model.gridnode.GridnodeFixtures.signed;

public class GridnodeSignatureTest {
	private static final long NOW = 1_800_000_000_000L;
	private static final long MAX_AGE = GridnodeSignature.MAX_AGE.toMillis();
	private static final long MAX_SKEW = GridnodeSignature.MAX_SKEW.toMillis();

	@Example
	public void shouldVerifyAnEntryItsOwnerSigned() throws Exception {
		assertThat(GridnodeSignature.verifies(signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW)),
			is(true));
	}

	@Example
	public void shouldRejectAnEntryWithoutSignature() throws Exception {
		final Gridnode entry = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);

		assertThat(GridnodeSignature.verifies(copyOf(entry, g -> g.setSignature(null))), is(false));
	}

	@Example
	public void shouldRejectEveryChangedField() throws Exception {
		final Gridnode entry = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);
		final String otherId = new Signature().getPublicKey();
		final List<Gridnode> changed = List.of(
			copyOf(entry, g -> g.setStatus(Gridnode.Status.INACTIVE)),
			copyOf(entry, g -> g.setHostName("10.0.0.2:1")),
			copyOf(entry, g -> g.setTimestamp(NOW + 1)),
			copyOf(entry, g -> g.setId(otherId))
		);

		changed.forEach(g -> assertThat(GridnodeSignature.verifies(g), is(false)));
	}

	@Example
	public void shouldRejectASignatureOfAnotherKey() throws Exception {
		final Gridnode entry = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);
		final Gridnode foreign = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);

		assertThat(GridnodeSignature.verifies(copyOf(entry, g -> g.setSignature(foreign.getSignature()))), is(false));
	}

	@Example
	public void shouldTellFreshFromExpiredAndFutureEntries() throws Exception {
		final Signature key = new Signature();

		assertThat(GridnodeSignature.isFresh(signed(key, Gridnode.Status.ACTIVE, "h:1", NOW), NOW), is(true));
		assertThat(GridnodeSignature.isFresh(signed(key, Gridnode.Status.ACTIVE, "h:1", NOW - MAX_AGE + 1), NOW),
			is(true));
		assertThat(GridnodeSignature.isFresh(signed(key, Gridnode.Status.ACTIVE, "h:1", NOW - MAX_AGE), NOW),
			is(false));
		assertThat(GridnodeSignature.isExpired(signed(key, Gridnode.Status.ACTIVE, "h:1", NOW - MAX_AGE), NOW),
			is(true));
		assertThat(GridnodeSignature.isFresh(signed(key, Gridnode.Status.ACTIVE, "h:1", NOW + MAX_SKEW), NOW),
			is(true));
		assertThat(GridnodeSignature.isFresh(signed(key, Gridnode.Status.ACTIVE, "h:1", NOW + MAX_SKEW + 1), NOW),
			is(false));
	}
}
