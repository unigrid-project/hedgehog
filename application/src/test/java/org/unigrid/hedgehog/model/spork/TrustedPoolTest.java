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
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.network.handler.RemoteAddress;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.SporkLag.Pool;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.KEYS;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;

/* Anyone can send sporks to a node from as many addresses as they like, while the peers that it dialed are of its own
   choosing. Inbound peers that send junk without end, each spork the stored data stamped a millisecond later with
   signatures that no key made, spend the quarter of the checks that inbound peers share and no more: an honest version
   from a dialed peer is still checked, taken and sent on, and tells the node that it is behind. All eight keys of the
   pool are built in, so each junk spork is tried against eight keys. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class TrustedPoolTest {
	private static final String DIALED = "198.51.100.7";
	private static final List<String> INBOUND = IntStream.range(0, 8).mapToObj(n -> "203.0.113." + n).toList();
	private static final SecureRandom RANDOM = new SecureRandom();

	private final PublishSporkChannelHandler handler = new PublishSporkChannelHandler();
	private final SporkDatabase database = SporkDatabase.builder().build();
	private final PendingSporks pending = new PendingSporks();
	private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
	private final SporkLag lag = new SporkLag(() -> now);
	private GridSpork stored;
	private GridSpork honest;

	@BeforeTry
	public void before() {
		buildIn(KEYS);
		stored = version(null, 10, now.minusSeconds(60));
		honest = version(stored, 20, now.minusSeconds(30));
		NetworkKey.reset();
		database.set(stored);
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	@SneakyThrows
	private static GridSpork version(GridSpork over, long maxSupply, Instant at) {
		final GridSpork spork = GridSpork.nextVersion(Type.MINT_SUPPLY, over, Optional.empty());

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(maxSupply));
		spork.setTimeStamp(at);
		spork.sign(key(6).getPrivateKey());
		spork.cosign(key(7).getPrivateKey());
		return spork;
	}

	private GridSpork junk(int n) {
		final GridSpork junk = SerializationUtils.clone(stored);

		junk.setTimeStamp(stored.getTimeStamp().plusMillis(1 + n));
		junk.setSignature(forged());
		junk.setCosignature(forged());
		return junk;
	}

	private byte[] forged() {
		final byte[] forged = new byte[stored.getSignature().length];

		RANDOM.nextBytes(forged);
		return forged;
	}

	/* Each inbound peer sends far more junk in the second than its share pays for */
	private void flood() {
		floodFrom(INBOUND, Pool.OTHER);
	}

	private void floodFrom(List<String> sources, Pool pool) {
		int n = 0;

		for (String source : sources) {
			for (int i = 0; i < 40; i++) {
				handler.receive(junk(n++), database, pending, lag, source, pool);
			}
		}
	}

	private static String groupOf(String address) {
		return RemoteAddress.groupOf(new InetSocketAddress(address, 1));
	}

	@Example
	public void shouldCheckTakeAndSendOnAnHonestVersionFromADialedPeerWhileInboundPeersSendJunk() {
		flood();

		final GridSpork heard = SerializationUtils.clone(honest);

		assertThat(handler.receive(heard, database, pending, lag, DIALED, Pool.TRUSTED), is(true));
		assertThat(database.getMintSupply(), sameInstance(heard));
	}

	@Example
	public void shouldLearnThatItIsBehindFromADialedPeerWhileInboundPeersSendJunk() {
		flood();
		lag.record(SerializationUtils.clone(honest), database, lag.turnOf(Type.MINT_SUPPLY, DIALED, Pool.TRUSTED));

		assertThat(lag.isBehind(Type.MINT_SUPPLY, database), is(true));
	}

	/* An attacker that announces addresses in four networks is dialed once in each, and those peers run junk at full
	   rate on the shares of their network groups, while honest dialed peers in other networks, and a seed in a network
	   of the attacker, which has a share of its own, still have a version taken */
	@Example
	public void shouldTakeHonestVersionsFromDialedPeersWhileTheDialedPeersOfAnAttackerSendJunk() {
		final List<String> hostile = IntStream.range(0, 4).mapToObj(n -> groupOf("45." + n + ".0.1")).toList();
		final int share = VerifySlots.shareOf(Pool.TRUSTED);

		floodFrom(hostile, Pool.TRUSTED);

		final double spent = hostile.stream().mapToDouble(group -> share - lag.left(Type.MINT_SUPPLY, Pool.TRUSTED,
			group)).sum();
		final GridSpork fromAnotherNetwork = SerializationUtils.clone(honest);
		final GridSpork fromTheSeed = SerializationUtils.clone(version(honest, 30, now.minusSeconds(20)));

		assertThat(spent, lessThanOrEqualTo((double) hostile.size() * share));
		assertThat(handler.receive(fromAnotherNetwork, database, pending, lag, groupOf("80.1.0.1"), Pool.TRUSTED),
			is(true));
		assertThat(handler.receive(fromTheSeed, database, pending, lag, "45.0.250.1", Pool.TRUSTED), is(true));
		assertThat(database.getMintSupply(), sameInstance(fromTheSeed));
	}

	/* A first version of the type with signatures that no key made of it, which each node it reaches tries: of the
	   board keys in the one form of a signature, as they are refused in any other before they are tried */
	@SneakyThrows
	private GridSpork junkOf(Type type, int n) {
		final GridSpork junk = GridSpork.nextVersion(type, null, Optional.empty());
		final byte[] forged = type == Type.BOARD_KEYS ? key(0).signInOneForm(forged()) : forged();

		junk.setTimeStamp(now.minusSeconds(10).plusMillis(n));
		junk.setSignature(forged);
		junk.setCosignature(forged.clone());
		return junk;
	}

	/* Two dialed peers in networks of their own send junk of every type at once at full rate, which spends no more of
	   what the dialed peers share on the node than their two accounts hold, and an honest version from a peer in a third
	   network is still checked and taken */
	@Example
	public void shouldTakeAnHonestVersionFromADialedPeerWhileTwoOthersSendJunkOfEveryType() {
		final List<String> flooding = List.of(groupOf("45.0.0.1"), groupOf("45.1.0.1"));
		int n = 0;

		for (Type type : Type.values()) {
			for (String source : flooding) {
				for (int i = 0; i < 40 && type != Type.UNDEFINED; i++) {
					handler.receive(junkOf(type, n++), database, pending, lag, source, Pool.TRUSTED);
				}
			}
		}

		final GridSpork heard = SerializationUtils.clone(honest);

		assertThat(handler.receive(heard, database, pending, lag, groupOf("80.1.0.1"), Pool.TRUSTED), is(true));
		assertThat(database.getMintSupply(), sameInstance(heard));
	}

	/* What inbound peers spend in a second, however many they are */
	@Example
	public void shouldSpendNoMoreOnInboundPeersInASecondThanTheirQuarter() {
		flood();

		final double spent = INBOUND.stream().mapToDouble(source -> VerifySlots.shareOf(Pool.OTHER)
			- lag.left(Type.MINT_SUPPLY, Pool.OTHER, source)).sum();

		assertThat(spent, lessThanOrEqualTo((double) VerifySlots.ceilingOf(Pool.OTHER, VerifySlots.TYPE_CEILING)));
	}
}
