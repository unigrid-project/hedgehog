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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeProperty;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

public class SporkLagTest {
	private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

	private static Signature proposer;
	private static Signature cosigner;

	private Instant now;
	private SporkLag lag;
	private SporkDatabase database;

	@SneakyThrows
	@BeforeProperty
	public void before() {
		proposer = new Signature();
		cosigner = new Signature();
		now = NOW;
		lag = new SporkLag(() -> now);
		database = SporkDatabase.builder().build();

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
	private static <S extends GridSpork> S proposal(S spork, Instant stamp, Signature signer) {
		spork.setTimeStamp(stamp);
		spork.setPreviousTimeStamp(Instant.EPOCH);
		spork.setPreviousData(spork.<ChunkData>getData().empty());
		spork.sign(signer.getPrivateKey());
		return spork;
	}

	private static MintSupply proposal(Instant stamp) {
		return proposal(new MintSupply(), stamp, proposer);
	}

	@SneakyThrows
	private static <S extends GridSpork> S agreed(S spork) {
		spork.cosign(cosigner.getPrivateKey());
		return spork;
	}

	private static MintSupply agreement(Instant stamp) {
		return agreed(proposal(stamp));
	}

	private Instant ago(Duration age) {
		return now.minus(age);
	}

	private boolean isBehind() {
		return lag.isBehind(Type.MINT_SUPPLY, database);
	}

	@Example
	public void isNotBehindWhenNothingWasSeen() {
		assertThat(isBehind(), is(false));

		database.set(agreement(ago(Duration.ofHours(1))));
		assertThat(isBehind(), is(false));
	}

	@Example
	public void isBehindWhenANewerAgreedSporkIsNotStored() {
		database.set(agreement(ago(Duration.ofHours(2))));
		lag.record(agreement(ago(Duration.ofHours(1))));

		assertThat(isBehind(), is(true));
	}

	@Example
	public void stopsBeingBehindWhenTheDatabaseHoldsTheNewerSpork() {
		final MintSupply newer = agreement(ago(Duration.ofHours(1)));

		database.set(agreement(ago(Duration.ofHours(2))));
		lag.record(newer);
		database.set(newer);

		assertThat(isBehind(), is(false));
	}

	@Example
	public void isNotBehindForASporkOlderThanOrEqualToTheStoredOne() {
		final MintSupply stored = agreement(ago(Duration.ofHours(1)));

		database.set(stored);
		lag.record(agreement(ago(Duration.ofHours(2))));
		lag.record(stored);

		assertThat(isBehind(), is(false));
	}

	@Example
	public void isBehindForATypeUnsetInTheDatabase() {
		lag.record(agreement(ago(Duration.ofHours(1))));

		assertThat(isBehind(), is(true));
	}

	@Example
	public void keepsTheTypesApart() {
		lag.record(agreement(ago(Duration.ofHours(1))));
		lag.record(proposal(new LedgerFee(), ago(Duration.ofMinutes(5)), proposer));

		assertThat(lag.isBehind(Type.MINT_SUPPLY, database), is(true));
		assertThat(lag.isBehind(Type.LEDGER_FEE, database), is(true));
		assertThat(lag.isBehind(Type.MINT_STORAGE, database), is(false));
		assertThat(lag.isBehind(Type.UNDEFINED, database), is(false));
	}

	@Example
	public void isBehindWhileAProposalCanStillBeHeldAndNotAfter() {
		database.set(agreement(ago(Duration.ofHours(2))));
		lag.record(proposal(ago(Duration.ofMinutes(5))));

		now = NOW.plus(PendingSporks.LIFETIME).minus(Duration.ofMinutes(6));
		assertThat(isBehind(), is(true));

		now = NOW.plus(PendingSporks.LIFETIME).minus(Duration.ofMinutes(4));
		assertThat(isBehind(), is(false));
	}

	@Example
	public void ignoresAProposalOlderThanItsLifetime() {
		lag.record(proposal(ago(PendingSporks.LIFETIME.plusMinutes(1))));

		assertThat(isBehind(), is(false));
	}

	@Example
	public void ignoresAProposalFromBeyondItsLifetimeAhead() {
		lag.record(proposal(now.plus(PendingSporks.LIFETIME).plus(Duration.ofMinutes(1))));

		assertThat(isBehind(), is(false));
	}

	@Example
	public void remembersAnAgreedSporkAfterALaterProposalExpires() {
		database.set(agreement(ago(Duration.ofHours(2))));
		lag.record(agreement(ago(Duration.ofMinutes(30))));
		lag.record(proposal(ago(Duration.ofMinutes(5))));

		now = NOW.plus(PendingSporks.LIFETIME);
		assertThat(isBehind(), is(true));
	}

	@Example
	public void remembersAnAgreedSporkUntilTheDatabaseCatchesUp() {
		final MintSupply newer = agreement(ago(Duration.ofHours(1)));

		lag.record(newer);
		now = NOW.plus(Duration.ofDays(365));
		assertThat(isBehind(), is(true));

		database.set(newer);
		assertThat(isBehind(), is(false));
	}

	@Example
	public void ignoresAnUnsignedSpork() {
		final MintSupply spork = new MintSupply();

		spork.setTimeStamp(now);
		lag.record(spork);

		spork.setCosignature(new byte[] { 1, 2, 3 });
		lag.record(spork);

		assertThat(isBehind(), is(false));
	}

	@Example
	public void ignoresASporkWithoutTimeStamp() {
		lag.record(new MintSupply());

		assertThat(isBehind(), is(false));
	}

	@SneakyThrows
	@Example
	public void ignoresASporkSignedByAnUnknownKey() {
		final Signature outsider = new Signature();
		final MintSupply spork = proposal(new MintSupply(), ago(Duration.ofMinutes(5)), outsider);

		lag.record(spork);
		assertThat(isBehind(), is(false));

		spork.setCosignature(new Signature(Optional.of(outsider.getPrivateKey()), Optional.empty())
			.sign(spork.getSignable()));
		lag.record(spork);
		assertThat(isBehind(), is(false));
	}

	@Example
	public void ignoresASporkSignedTwiceByTheSameKey() {
		final MintSupply spork = proposal(ago(Duration.ofHours(1)));

		spork.setCosignature(spork.getSignature().clone());
		lag.record(spork);

		assertThat(isBehind(), is(false));
	}

	@Example
	public void ignoresASporkChangedAfterItWasSigned() {
		final MintSupply spork = agreement(ago(Duration.ofHours(1)));
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.TEN);
		spork.setData(data);
		lag.record(spork);

		assertThat(isBehind(), is(false));
	}

	@SneakyThrows
	@Example
	public void ignoresAProposalSignedByAnUnknownKey() {
		lag.record(proposal(new MintSupply(), ago(Duration.ofMinutes(5)), new Signature()));

		assertThat(isBehind(), is(false));
	}

	@Example
	public void recordsAFarFutureTimeStampOnlyTenMinutesAhead() {
		lag.record(agreement(now.plus(Duration.ofDays(365))));

		database.set(agreement(now.plus(Duration.ofMinutes(5))));
		assertThat(isBehind(), is(true));

		database.set(agreement(now.plus(Duration.ofMinutes(11))));
		assertThat(isBehind(), is(false));
	}

	@Example
	public void recordsATimeStampWithinTheAllowanceAsItIs() {
		lag.record(agreement(now.plus(Duration.ofMinutes(9))));

		database.set(agreement(now.plus(Duration.ofMinutes(8))));
		assertThat(isBehind(), is(true));

		database.set(agreement(now.plus(Duration.ofMinutes(9))));
		assertThat(isBehind(), is(false));
	}

	@SneakyThrows
	@Example
	public void staysConsistentUnderConcurrentWritersAndReaders() {
		final List<MintSupply> sporks = new ArrayList<>(IntStream.range(0, 24)
			.mapToObj(minutes -> agreement(ago(Duration.ofMinutes(60 - minutes)))).toList());
		final MintSupply newest = sporks.getLast();
		final MintSupply runnerUp = sporks.get(sporks.size() - 2);
		final ExecutorService pool = Executors.newFixedThreadPool(8);
		final List<Future<?>> work = new ArrayList<>();

		Collections.shuffle(sporks);
		sporks.forEach(spork -> work.add(pool.submit(() -> lag.record(spork))));
		IntStream.range(0, 100).forEach(i -> work.add(pool.submit(() -> isBehind())));

		for (Future<?> task : work) {
			task.get(1, TimeUnit.MINUTES);
		}

		pool.shutdown();
		database.set(runnerUp);
		assertThat(isBehind(), is(true));

		database.set(newest);
		assertThat(isBehind(), is(false));
	}
}
