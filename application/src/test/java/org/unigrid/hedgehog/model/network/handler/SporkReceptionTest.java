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
import java.util.stream.IntStream;
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
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;

public class SporkReceptionTest {
	private static final String PEER = "198.51.100.7";
	private static final String ANOTHER_PEER = "203.0.113.9";

	/* A flood comes within the second of one turn, or two when it happens to cross into the next */
	private static final int MOST_TURNS = 2;

	/* How often the current network keys were asked for, which is once for each signature that is checked: twice
	   for the lag and twice for the stored spork when a received spork is signed twice */
	private static final int LOOKUPS_PER_TURN = 4;

	private static Signature proposer;
	private static Signature cosigner;
	private static long versions;
	private static int keyLookups;

	private final PublishSporkChannelHandler handler = new PublishSporkChannelHandler();
	private SporkDatabase sporkDatabase;
	private PendingSporks pendingSporks;
	private SporkLag lag;

	@SneakyThrows
	@BeforeProperty
	public void before() {
		proposer = new Signature();
		cosigner = new Signature();
		sporkDatabase = SporkDatabase.builder().build();
		pendingSporks = new PendingSporks();
		lag = new SporkLag();

		new MockUp<NetworkKey>() {
			@Mock public static String[] getPublicKeys() {
				keyLookups++;
				return new String[] { proposer.getPublicKey(), cosigner.getPublicKey() };
			}
		};
	}

	private boolean receive(GridSpork spork) {
		return handler.receive(spork, sporkDatabase, pendingSporks, lag, PEER);
	}

	@SneakyThrows
	private static MintSupply forged(MintSupply spork) {
		spork.sign(new Signature().getPrivateKey());
		return spork;
	}

	private int lookupsOfAFlood(MintSupply forged) {
		keyLookups = 0;
		IntStream.range(0, 1000).forEach(n -> receive(forged));
		return keyLookups;
	}

	@Example
	public void shouldCheckAFloodOfForgedProposalsFromOnePeerNoMoreThanItsTurnsAllow() {
		final MintSupply forged = forged(proposal(new MintSupply()));

		assertThat(lookupsOfAFlood(forged), lessThanOrEqualTo(MOST_TURNS * LOOKUPS_PER_TURN));
		assertThat(pendingSporks.list(), empty());
	}

	@Example
	public void shouldCheckAFloodOfForgedSporksFromOnePeerNoMoreThanItsTurnsAllow() {
		final MintSupply forged = forged(proposal(new MintSupply()));

		forged.setCosignature(forged.getSignature());

		assertThat(lookupsOfAFlood(forged), lessThanOrEqualTo(MOST_TURNS * LOOKUPS_PER_TURN));
		assertThat(sporkDatabase.getMintSupply(), is(nullValue()));
	}

	@Example
	public void shouldTakeAGenuineSporkFromAnotherPeerDuringAFloodFromOne() {
		final MintSupply forged = forged(proposal(new MintSupply()));
		final MintSupply genuine = cosigned(proposal(new MintSupply()));

		forged.setCosignature(forged.getSignature());
		lookupsOfAFlood(forged);

		assertThat(handler.receive(genuine, sporkDatabase, pendingSporks, lag, ANOTHER_PEER), is(true));
		assertThat(sporkDatabase.getMintSupply(), equalTo(genuine));
	}

	@Example
	public void shouldTakeTheGenuineSporksOfOnePeerOneAfterAnother() {
		final MintSupply first = cosigned(proposal(new MintSupply()));
		final MintSupply second = cosigned(proposal(first));
		final MintSupply third = proposal(second);

		assertThat(receive(first), is(true));
		assertThat(receive(second), is(true));
		assertThat(receive(third), is(true));
		assertThat(sporkDatabase.getMintSupply(), equalTo(second));
		assertThat(pendingSporks.list(), contains(third));
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

		assertThat(receive(spork), is(true));
		assertThat(pendingSporks.list(), contains(spork));
		assertThat(sporkDatabase.getMintSupply(), is(nullValue()));
	}

	@Example
	public void shouldNotPassOnAProposalItAlreadyHolds() {
		final MintSupply spork = proposal(new MintSupply());

		receive(spork);
		assertThat(receive(spork), is(false));
	}

	@Example
	public void shouldStoreACosignedProposalAndDropIt() {
		final MintSupply spork = proposal(new MintSupply());
		final MintSupply cosigned = cosigned(spork);

		receive(spork);

		assertThat(receive(cosigned), is(true));
		assertThat(sporkDatabase.getMintSupply(), equalTo(cosigned));
		assertThat(pendingSporks.list(), empty());
	}

	@Example
	public void shouldKeepAProposalOnTopOfTheStoredSpork() {
		final MintSupply stored = cosigned(proposal(new MintSupply()));
		final MintSupply spork = proposal(stored);

		receive(spork);
		receive(stored);

		assertThat(sporkDatabase.getMintSupply(), equalTo(stored));
		assertThat(pendingSporks.list(), contains(spork));
	}

	@Example
	public void shouldPassOnNothingOlderThanTheStoredSpork() {
		final MintSupply olderProposal = proposal(new MintSupply());
		final MintSupply older = cosigned(proposal(new MintSupply()));
		final MintSupply stored = cosigned(proposal(new MintSupply()));

		sporkDatabase.set(stored);
		assertThat(receive(stored), is(false));
		assertThat(receive(older), is(false));
		assertThat(receive(olderProposal), is(false));
		assertThat(sporkDatabase.getMintSupply(), equalTo(stored));
		assertThat(lag.isBehind(Type.MINT_SUPPLY, sporkDatabase), is(false));
	}

	@Example
	public void shouldNotBeBehindWhileAProposalIsHeld() {
		receive(proposal(new MintSupply()));

		assertThat(lag.isBehind(Type.MINT_SUPPLY, sporkDatabase), is(false));
	}

	@Example
	public void shouldNotBeBehindOnceTheCosignedSporkIsStored() {
		final MintSupply spork = proposal(new MintSupply());

		receive(spork);
		receive(cosigned(spork));

		assertThat(lag.isBehind(Type.MINT_SUPPLY, sporkDatabase), is(false));
	}

	@Example
	public void shouldBeBehindOnACosignedSporkItRejects() {
		final MintSupply first = cosigned(proposal(new MintSupply()));
		final MintSupply second = cosigned(proposal(first));
		final MintSupply stored = cosigned(proposal(second));
		final MintSupply rival = cosigned(proposal(first));

		sporkDatabase.set(stored);

		assertThat(receive(rival), is(false));
		assertThat(sporkDatabase.getMintSupply(), equalTo(stored));
		assertThat(lag.isBehind(Type.MINT_SUPPLY, sporkDatabase), is(true));
	}

	@SneakyThrows
	@Example
	public void shouldNotBeBehindOnAProposalOfAnUnknownKey() {
		final MintSupply spork = proposal(new MintSupply());

		spork.sign(new Signature().getPrivateKey());
		receive(spork);

		assertThat(lag.isBehind(Type.MINT_SUPPLY, sporkDatabase), is(false));
	}
}
