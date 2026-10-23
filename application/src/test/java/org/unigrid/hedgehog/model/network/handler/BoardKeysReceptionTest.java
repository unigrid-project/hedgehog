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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.BoardKeysAuthority;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signedAnyway;

/* The board keys as a node takes them from its peers. The release builds in keys 0 and 1; the first change adds key 2,
   and the second switches keys 0 and 1 off and adds key 3. Another first change adds key 4 instead of key 2. */
public class BoardKeysReceptionTest {
	private static final byte[] ROOT = rootOf(keys(0, 1));
	private static final KeyChange ADDING = change(ROOT, List.of(), keys(0, 1, 2), keys(0, 1, 2));
	private static final KeyChange SWITCHING_OFF = change(ROOT, List.of(ADDING), keys(2, 3), keys(0, 2, 3));
	private static final KeyChange FORKING = change(ROOT, List.of(), keys(0, 1, 4), keys(0, 1, 4));

	private final PublishSporkChannelHandler handler = new PublishSporkChannelHandler();
	private SporkDatabase database;
	private PendingSporks pending;
	private SporkLag lag;
	private int peers;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		database = SporkDatabase.builder().build();
		pending = new PendingSporks();
		lag = new SporkLag();
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	/* Each from a peer of its own, which has a turn of its own to have its signatures checked */
	private boolean receive(GridSpork spork) {
		return handler.receive(SerializationUtils.clone(spork), database, pending, lag, "peer" + peers++);
	}

	@SneakyThrows
	private static BoardKeys later(BoardKeys.SporkData chain, int signer, int cosigner) {
		Thread.sleep(2);
		return signed(chain, key(signer), key(cosigner));
	}

	@SneakyThrows
	private static MintSupply supply(Signature signer, Signature cosigner) {
		final MintSupply spork = new MintSupply();

		spork.archive();
		spork.setTimeStamp(Instant.now().minusSeconds(60));
		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		spork.sign(signer.getPrivateKey());
		spork.setCosignature(cosigner.sign(spork.getSignable()));
		return spork;
	}

	private static Set<String> active() {
		return NetworkKey.getActiveKeys().active();
	}

	@Example
	public void shouldGoByEachChangeItTakesAndTheKeysThatItAdds() {
		assertThat(receive(later(chainOf(ADDING), 0, 1)), is(true));
		assertThat(active(), equalTo(Set.of(publicKeysOf(keys(0, 1, 2)))));

		assertThat(receive(later(chainOf(ADDING, SWITCHING_OFF), 2, 3)), is(true));
		assertThat(active(), equalTo(Set.of(publicKeysOf(keys(2, 3)))));
		assertThat(database.getBoardKeys().<BoardKeys.SporkData>getData().getChanges().size(), is(2));

		assertThat(receive(supply(key(0), key(1))), is(false));
		assertThat(receive(supply(key(2), key(3))), is(true));
		assertThat(database.getMintSupply().isCounting(), is(true));
	}

	/* Keys 0 and 1 were active at the end of the first change and sign it as anyone who holds them can */
	@Example
	public void shouldRefuseAShorterChainThatKeysItSwitchedOffSigned() {
		final BoardKeys held = later(chainOf(ADDING, SWITCHING_OFF), 2, 3);

		assertThat(receive(held), is(true));
		assertThat(receive(later(chainOf(ADDING), 0, 1)), is(false));
		assertThat(database.getBoardKeys().getTimeStamp(), equalTo(held.getTimeStamp()));
		assertThat(active(), equalTo(Set.of(publicKeysOf(keys(2, 3)))));
	}

	@Example
	public void shouldKeepTheChainItHoldsWhenAnotherForksFromItAndSaySo() {
		final ListAppender<ILoggingEvent> log = new ListAppender<>();
		final Logger logger = (Logger) LoggerFactory.getLogger(BoardKeysAuthority.class);
		final BoardKeys held = later(chainOf(ADDING), 0, 1);

		log.start();
		logger.addAppender(log);

		try {
			assertThat(receive(held), is(true));
			assertThat(receive(later(chainOf(FORKING), 0, 4)), is(false));
			assertThat(database.getBoardKeys().getTimeStamp(), equalTo(held.getTimeStamp()));
			assertThat(active(), equalTo(Set.of(publicKeysOf(keys(0, 1, 2)))));
			assertThat(log.list.stream().map(ILoggingEvent::getLevel).toList(), contains(Level.WARN));
			assertThat(log.list.get(0).getFormattedMessage(), containsString("fork"));
		} finally {
			logger.detachAppender(log);
		}
	}

	@Example
	public void shouldRefuseAChainRootedInOtherKeysOrWithAForgedApproval() {
		final byte[] elsewhere = rootOf(keys(0, 5));
		final KeyChange forged = SerializationUtils.clone(ADDING);

		forged.getApprovals().get(1).setSignature(forged.getApprovals().get(0).getSignature());

		assertThat(receive(signedAnyway(chainOf(change(elsewhere, List.of(), keys(0, 1, 5), keys(0, 1, 5))), key(0),
			key(1))), is(false));
		assertThat(receive(signedAnyway(chainOf(forged), key(0), key(1))), is(false));
		assertThat(database.getBoardKeys(), nullValue());
		assertThat(active(), equalTo(Set.of(publicKeysOf(keys(0, 1)))));
	}

	@Example
	@SneakyThrows
	public void shouldDropTheProposalsOfKeysThatItSwitchesOff() {
		final MintSupply proposal = supply(key(0), key(1));

		proposal.setCosignature(null);
		proposal.setTimeStamp(Instant.now());
		proposal.sign(key(0).getPrivateKey());

		assertThat(receive(proposal), is(true));
		assertThat(pending.list(), hasItem(proposal));
		assertThat(receive(later(chainOf(ADDING, SWITCHING_OFF), 2, 3)), is(true));
		assertThat(pending.proposalOf(Type.MINT_SUPPLY).isPresent(), is(false));
	}

	@Example
	public void shouldInstallTheChainOfWhatItStores() {
		final BoardKeys held = later(chainOf(ADDING), 0, 1);

		assertThat(BoardKeysAuthority.accept(held, database, pending, () -> true), is(true));
		assertThat(database.getBoardKeys(), sameInstance(held));
		assertThat(active(), equalTo(Set.of(publicKeysOf(keys(0, 1, 2)))));
	}
}
