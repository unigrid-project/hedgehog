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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.CheckMeter;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;

/* The source of a spork pays for checking it, and nothing more: what the node does once it took the spork, storing it,
   going by its chain and dropping the proposals that it outdates, is the node's own work, which a source whose checks
   run out right then cannot leave half done. The release builds in keys 0 and 1; the node holds a proposal of key 0,
   and the change of the board keys that it takes adds key 2 and switches key 0 off. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class MeteredCommitTest {
	private static final String SOURCE = "198.51.100.7";
	private static final byte[] DATA = "anything".getBytes(StandardCharsets.UTF_8);

	private final PublishSporkChannelHandler handler = new PublishSporkChannelHandler();
	private final SporkDatabase database = SporkDatabase.builder().build();
	private final PendingSporks pending = new PendingSporks();
	private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
	private final SporkLag lag = new SporkLag(() -> now);

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	@SneakyThrows
	private static GridSpork proposalOfKey0() {
		final GridSpork proposal = GridSpork.nextVersion(Type.MINT_SUPPLY, null, Optional.empty());

		proposal.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		proposal.sign(key(0).getPrivateKey());
		return proposal;
	}

	/* What checking that the spork may be taken costs, as a node that holds nothing and has checked none of its
	   signatures checks it */
	private static int checksToTake(BoardKeys spork) {
		final AtomicInteger checks = new AtomicInteger();

		forgetTheChecks();
		CheckMeter.charging(() -> checks.incrementAndGet() > 0, () -> SerializationUtils.clone(spork)
			.canReplace(null, () -> true));
		forgetTheChecks();
		return checks.get();
	}

	private static void forgetTheChecks() {
		NetworkKey.reset();
		LikelySigners.forget();
	}

	/* The source holds as many checks as taking the spork costs and one more, which the decision to check it asks for
	   once its head was checked for the lag, and not two more */
	private void leaveTheSourceWith(int checks) {
		final Signature signer = key(7);

		lag.turnOf(Type.BOARD_KEYS, SOURCE).charging(() -> {
			IntStream.range(0, VerifySlots.shareOf(SporkLag.Pool.OTHER) - checks).forEach(n -> Signature.isSignedBy(
				signer.getPublicKey(), DATA, new byte[0]));
			return true;
		});
	}

	@Example
	public void shouldDropTheOutdatedProposalsOfASporkItTookWhenTheChecksOfTheSourceRunOutRightThen() {
		final BoardKeys switching = signed(chainOf(change(rootOf(keys(0, 1)), List.of(), keys(1, 2),
			keys(0, 1, 2))), key(1), key(2));

		assertThat(pending.offer(proposalOfKey0(), null), is(true));
		leaveTheSourceWith(checksToTake(switching) + 1);

		final boolean isNew = handler.receive(SerializationUtils.clone(switching), database, pending, lag, SOURCE);

		assertThat(database.getBoardKeys().getSignable(), equalTo(switching.getSignable()));
		assertThat(pending.list(), is(empty()));
		assertThat(isNew, is(true));
	}
}
