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

package org.unigrid.hedgehog.model.network.handler;

import java.math.BigDecimal;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

public class SporkReceptionTest {
	private static Signature proposer;
	private static Signature cosigner;
	private static long versions;

	private final PublishSporkChannelHandler handler = new PublishSporkChannelHandler();
	private SporkDatabase sporkDatabase;
	private PendingSporks pendingSporks;

	@SneakyThrows
	@BeforeProperty
	public void before() {
		proposer = new Signature();
		cosigner = new Signature();
		sporkDatabase = SporkDatabase.builder().build();
		pendingSporks = new PendingSporks();

		new MockUp<NetworkKey>() {
			@Mock public static String[] getPublicKeys() {
				return new String[] { proposer.getPublicKey(), cosigner.getPublicKey() };
			}

			@Mock public static String[] getRetiredPublicKeys() {
				return new String[0];
			}
		};
	}

	@SneakyThrows
	private static MintSupply proposal(MintSupply base) {
		final MintSupply spork = SerializationUtils.clone(base);
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.TEN);
		spork.archive();

		/* Versions made within one millisecond would otherwise not be newer than the version they replace */
		spork.setTimeStamp(spork.getTimeStamp().plusMillis(++versions));
		spork.setData(data);
		spork.sign(proposer.getPrivateKey());
		return spork;
	}

	@SneakyThrows
	private static MintSupply cosigned(MintSupply proposal) {
		final MintSupply spork = SerializationUtils.clone(proposal);

		spork.cosign(cosigner.getPrivateKey());
		return spork;
	}

	@Example
	public void shouldHoldAProposalWithoutStoringIt() {
		final MintSupply spork = proposal(new MintSupply());

		assertThat(handler.receive(spork, sporkDatabase, pendingSporks), is(true));
		assertThat(pendingSporks.list(), contains(spork));
		assertThat(sporkDatabase.getMintSupply(), is(nullValue()));
	}

	@Example
	public void shouldNotPassOnAProposalItAlreadyHolds() {
		final MintSupply spork = proposal(new MintSupply());

		handler.receive(spork, sporkDatabase, pendingSporks);
		assertThat(handler.receive(spork, sporkDatabase, pendingSporks), is(false));
	}

	@Example
	public void shouldStoreACosignedProposalAndDropIt() {
		final MintSupply spork = proposal(new MintSupply());
		final MintSupply cosigned = cosigned(spork);

		handler.receive(spork, sporkDatabase, pendingSporks);

		assertThat(handler.receive(cosigned, sporkDatabase, pendingSporks), is(true));
		assertThat(sporkDatabase.getMintSupply(), equalTo(cosigned));
		assertThat(pendingSporks.list(), empty());
	}

	@Example
	public void shouldKeepAProposalOnTopOfTheStoredSpork() {
		final MintSupply stored = cosigned(proposal(new MintSupply()));
		final MintSupply spork = proposal(stored);

		handler.receive(spork, sporkDatabase, pendingSporks);
		handler.receive(stored, sporkDatabase, pendingSporks);

		assertThat(sporkDatabase.getMintSupply(), equalTo(stored));
		assertThat(pendingSporks.list(), contains(spork));
	}

	@Example
	public void shouldPassOnNothingOlderThanTheStoredSpork() {
		final MintSupply olderProposal = proposal(new MintSupply());
		final MintSupply older = cosigned(proposal(new MintSupply()));
		final MintSupply stored = cosigned(proposal(new MintSupply()));

		sporkDatabase.set(stored);
		assertThat(handler.receive(stored, sporkDatabase, pendingSporks), is(false));
		assertThat(handler.receive(older, sporkDatabase, pendingSporks), is(false));
		assertThat(handler.receive(olderProposal, sporkDatabase, pendingSporks), is(false));
		assertThat(sporkDatabase.getMintSupply(), equalTo(stored));
	}
}
