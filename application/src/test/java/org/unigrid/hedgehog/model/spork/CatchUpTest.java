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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.spork.SporkLag.Pool;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signedAnyway;

/* A node that starts fresh takes the longest chain of the board from a dialed peer that sends it every three minutes,
   as peers send what they hold, while inbound peers send junk without end and copies of other bytes push out what the
   node remembers of whole changes: what verified of the chain is remembered signature by signature, so each time it
   comes the dialed peer pays for as much more of it as its share holds. The junk is the chain with the last change
   approved by a signature of key 2 over other data. The release builds in keys 0 and 1, and the board adds key 2 and
   switches it off again, change after change. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class CatchUpTest {
	private static final String DIALED = "198.51.100.7";
	private static final List<String> INBOUND = IntStream.range(0, 8).mapToObj(n -> "203.0.113." + n).toList();
	private static final Duration RESENT = Duration.ofMinutes(3);
	private static final int MOST_TIMES = 20;

	private final PublishSporkChannelHandler handler = new PublishSporkChannelHandler();
	private final SporkDatabase database = SporkDatabase.builder().build();
	private final PendingSporks pending = new PendingSporks();
	private Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
	private final SporkLag lag = new SporkLag(() -> now);
	private List<KeyChange> chain;
	private int junkMade;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		chain = longestChain();
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
		BoardKeyChain.forget();
	}

	private static List<KeyChange> longestChain() {
		final byte[] root = rootOf(keys(0, 1));
		final List<KeyChange> chain = new ArrayList<>();

		while (chain.size() < BoardKeyChain.MAX_CHANGES) {
			chain.add(chain.size() % 2 == 0 ? change(root, chain, keys(0, 1, 2), keys(0, 1, 2))
				: change(root, chain, keys(0, 1), keys(0, 1)));
		}

		return chain;
	}

	@SneakyThrows
	private BoardKeys junk() {
		final KeyChange last = chain.getLast();
		final List<KeyChange.Approval> approvals = new ArrayList<>(last.getApprovals());
		final byte[] other = ("junk " + junkMade++).getBytes(StandardCharsets.UTF_8);

		approvals.set(1, new KeyChange.Approval(approvals.get(1).getKey(), key(2).signInOneForm(other)));

		final List<KeyChange> forged = new ArrayList<>(chain.subList(0, chain.size() - 1));

		forged.add(new KeyChange(last.getSequence(), last.getMadeAt(), last.getPrevious(),
			new ArrayList<>(last.getActive()), approvals));
		return signedAnyway(chainOf(forged.toArray(KeyChange[]::new)), key(0), key(1));
	}

	@Example
	public void shouldTakeTheLongestChainFromADialedPeerThatSendsItEveryFewMinutesWhileInboundPeersSendJunk() {
		final BoardKeys honest = signedAnyway(chainOf(chain.toArray(KeyChange[]::new)), key(0), key(1));
		int times = 0;

		NetworkKey.reset();

		while (database.getBoardKeys() == null && times < MOST_TIMES) {
			INBOUND.forEach(source -> IntStream.range(0, 4).forEach(n -> handler.receive(junk(), database, pending,
				lag, source, Pool.OTHER)));
			BoardKeyChain.forget();
			handler.receive(SerializationUtils.clone(honest), database, pending, lag, DIALED, Pool.TRUSTED);
			now = now.plus(RESENT);
			times++;
		}

		assertThat(times, lessThan(MOST_TIMES));
		assertThat(database.getBoardKeys(), notNullValue());
		assertThat(database.getBoardKeys().getSignable(), equalTo(honest.getSignable()));
		assertThat(NetworkKey.getActiveKeys().active(), is(Set.of(publicKeysOf(keys(0, 1)))));
		assertThat(NetworkKey.getActiveKeys().length(), is(BoardKeyChain.MAX_CHANGES));
	}
}
