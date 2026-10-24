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

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signedAnyway;

/* A spork of the board keys that anyone can make costs what checking it costs and no more, which its source pays for
   from its own bucket. The junk here holds the change that adds key 2, with the approvals of keys 0 and 1 copied from
   the board and one of key 2 that is new bytes each time, so it holds up to its last check and is never remembered.
   The release builds in keys 0 and 1. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysJunkTest {
	private static final String JUNK_SOURCE = "203.0.113.9";
	private static final String HONEST_SOURCE = "198.51.100.7";
	private static final SecureRandom RANDOM = new SecureRandom();

	/* What checking the junk costs: the approvals of keys 0 and 1, which verify, and the one of key 2, which does not */
	private static final int CHECKS_OF_JUNK = 3;

	private final PublishSporkChannelHandler handler = new PublishSporkChannelHandler();
	private final SporkDatabase database = SporkDatabase.builder().build();
	private final PendingSporks pending = new PendingSporks();
	private Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
	private final SporkLag lag = new SporkLag(() -> now);
	private KeyChange adding;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		adding = change(rootOf(keys(0, 1)), List.of(), keys(0, 1, 2), keys(0, 1, 2));
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private boolean receive(GridSpork spork, String source) {
		return handler.receive(SerializationUtils.clone(spork), database, pending, lag, source);
	}

	/* The forged approval is a signature in its one form, as one in another form costs no check */
	@SneakyThrows
	private BoardKeys junk() {
		final List<KeyChange.Approval> approvals = new ArrayList<>(adding.getApprovals().subList(0, 2));
		final byte[] other = new byte[Long.BYTES];

		RANDOM.nextBytes(other);
		approvals.add(new KeyChange.Approval(adding.getApprovals().get(2).getKey(), key(2).signInOneForm(other)));
		return signedAnyway(chainOf(new KeyChange(adding.getSequence(), adding.getMadeAt(), adding.getPrevious(),
			new ArrayList<>(adding.getActive()), approvals)), key(0), key(1));
	}

	@Example
	public void shouldChargeASourceWhatCheckingItsSporkCost() {
		receive(junk(), JUNK_SOURCE);
		assertThat(VerifySlots.BURST - lag.left(Type.BOARD_KEYS, JUNK_SOURCE), is((double) CHECKS_OF_JUNK));
	}

	@Example
	public void shouldSpendNoMoreThanTheBucketOfOneSourceThatSendsJunkAndCheckAndTakeAnHonestSpork() {
		final BoardKeys honest = signed(chainOf(adding), key(0), key(1));
		final int seconds = 5;
		int junkChecks = 0;

		for (int second = 0; second < seconds; second++) {
			for (int n = 0; n < 20; n++) {
				final double before = lag.left(Type.BOARD_KEYS, JUNK_SOURCE);

				receive(junk(), JUNK_SOURCE);
				junkChecks += (int) Math.round(before - lag.left(Type.BOARD_KEYS, JUNK_SOURCE));
			}

			receive(honest, HONEST_SOURCE);
			now = now.plusSeconds(1);
		}

		assertThat(junkChecks, lessThanOrEqualTo(VerifySlots.BURST + (seconds - 1) * VerifySlots.SHARE));
		assertThat(database.getBoardKeys(), notNullValue());
		assertThat(database.getBoardKeys().getSignable(), equalTo(honest.getSignable()));
	}

	/* What a genuine spork cost is given back to its source */
	@Example
	public void shouldGiveBackWhatAGenuineSporkCost() {
		assertThat(receive(signed(chainOf(adding), key(0), key(1)), HONEST_SOURCE), is(true));
		assertThat(lag.left(Type.BOARD_KEYS, HONEST_SOURCE), is((double) VerifySlots.BURST));
	}
}
