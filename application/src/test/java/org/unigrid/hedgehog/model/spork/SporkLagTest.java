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
import java.util.concurrent.atomic.AtomicLong;
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
	private static final String PEER = "198.51.100.7";

	private static Signature proposer;
	private static Signature cosigner;

	/* How often the current network keys were asked for: once for each signature the lag has to check */
	private static int keyLookups;

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

		keyLookups = 0;

		new MockUp<NetworkKey>() {
			@Mock public static String[] getPublicKeys() {
				keyLookups++;
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

	/* A spork a second after the one before it, as the lag takes one a second for each type at most */
	private void record(GridSpork spork) {
		lag.record(spork, database, PEER);
		now = now.plus(SporkLag.VERIFY_GAP);
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
		record(agreement(ago(Duration.ofHours(1))));

		assertThat(isBehind(), is(true));
	}

	@Example
	public void stopsBeingBehindWhenTheDatabaseHoldsTheNewerSpork() {
		final MintSupply newer = agreement(ago(Duration.ofHours(1)));

		database.set(agreement(ago(Duration.ofHours(2))));
		record(newer);
		database.set(newer);

		assertThat(isBehind(), is(false));
	}

	@Example
	public void isNotBehindForASporkOlderThanOrEqualToTheStoredOne() {
		final MintSupply stored = agreement(ago(Duration.ofHours(1)));

		database.set(stored);
		record(agreement(ago(Duration.ofHours(2))));
		record(stored);

		assertThat(isBehind(), is(false));
	}

	@Example
	public void isBehindForATypeUnsetInTheDatabase() {
		record(agreement(ago(Duration.ofHours(1))));

		assertThat(isBehind(), is(true));
	}

	@Example
	public void keepsTheTypesApart() {
		record(agreement(ago(Duration.ofHours(1))));
		record(proposal(new LedgerFee(), ago(Duration.ofMinutes(5)), proposer));

		assertThat(lag.isBehind(Type.MINT_SUPPLY, database), is(true));
		assertThat(lag.isBehind(Type.LEDGER_FEE, database), is(true));
		assertThat(lag.isBehind(Type.MINT_STORAGE, database), is(false));
		assertThat(lag.isBehind(Type.UNDEFINED, database), is(false));
	}

	@Example
	public void isBehindWhileAProposalCanStillBeHeldAndNotAfter() {
		database.set(agreement(ago(Duration.ofHours(2))));
		record(proposal(ago(Duration.ofMinutes(5))));

		now = NOW.plus(PendingSporks.LIFETIME).minus(Duration.ofMinutes(6));
		assertThat(isBehind(), is(true));

		now = NOW.plus(PendingSporks.LIFETIME).minus(Duration.ofMinutes(4));
		assertThat(isBehind(), is(false));
	}

	@Example
	public void ignoresAProposalOlderThanItsLifetime() {
		record(proposal(ago(PendingSporks.LIFETIME.plusMinutes(1))));

		assertThat(isBehind(), is(false));
	}

	@Example
	public void ignoresAProposalFromBeyondItsLifetimeAhead() {
		record(proposal(now.plus(PendingSporks.LIFETIME).plus(Duration.ofMinutes(1))));

		assertThat(isBehind(), is(false));
	}

	@Example
	public void remembersAnAgreedSporkAfterALaterProposalExpires() {
		database.set(agreement(ago(Duration.ofHours(2))));
		record(agreement(ago(Duration.ofMinutes(30))));
		record(proposal(ago(Duration.ofMinutes(5))));

		now = NOW.plus(PendingSporks.LIFETIME);
		assertThat(isBehind(), is(true));
	}

	@Example
	public void remembersAnAgreedSporkUntilTheDatabaseCatchesUp() {
		final MintSupply newer = agreement(ago(Duration.ofHours(1)));

		record(newer);
		now = NOW.plus(Duration.ofDays(365));
		assertThat(isBehind(), is(true));

		database.set(newer);
		assertThat(isBehind(), is(false));
	}

	@Example
	public void ignoresAnUnsignedSpork() {
		final MintSupply spork = new MintSupply();

		spork.setTimeStamp(now);
		record(spork);

		spork.setCosignature(new byte[] { 1, 2, 3 });
		record(spork);

		assertThat(isBehind(), is(false));
	}

	@Example
	public void ignoresASporkWithoutTimeStamp() {
		record(new MintSupply());

		assertThat(isBehind(), is(false));
	}

	@SneakyThrows
	@Example
	public void ignoresASporkSignedByAnUnknownKey() {
		final Signature outsider = new Signature();
		final MintSupply spork = proposal(new MintSupply(), ago(Duration.ofMinutes(5)), outsider);

		record(spork);
		assertThat(isBehind(), is(false));

		spork.setCosignature(new Signature(Optional.of(outsider.getPrivateKey()), Optional.empty())
			.sign(spork.getSignable()));
		record(spork);
		assertThat(isBehind(), is(false));
	}

	@Example
	public void ignoresASporkSignedTwiceByTheSameKey() {
		final MintSupply spork = proposal(ago(Duration.ofHours(1)));

		spork.setCosignature(spork.getSignature().clone());
		record(spork);

		assertThat(isBehind(), is(false));
	}

	@Example
	public void ignoresASporkChangedAfterItWasSigned() {
		final MintSupply spork = agreement(ago(Duration.ofHours(1)));
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.TEN);
		spork.setData(data);
		record(spork);

		assertThat(isBehind(), is(false));
	}

	@SneakyThrows
	@Example
	public void ignoresAProposalSignedByAnUnknownKey() {
		record(proposal(new MintSupply(), ago(Duration.ofMinutes(5)), new Signature()));

		assertThat(isBehind(), is(false));
	}

	@Example
	public void recordsAFarFutureTimeStampOnlyTenMinutesAhead() {
		record(agreement(now.plus(Duration.ofDays(365))));

		database.set(agreement(now.plus(Duration.ofMinutes(5))));
		assertThat(isBehind(), is(true));

		database.set(agreement(now.plus(Duration.ofMinutes(11))));
		assertThat(isBehind(), is(false));
	}

	@Example
	public void recordsATimeStampWithinTheAllowanceAsItIs() {
		record(agreement(now.plus(Duration.ofMinutes(9))));

		database.set(agreement(now.plus(Duration.ofMinutes(8))));
		assertThat(isBehind(), is(true));

		database.set(agreement(now.plus(Duration.ofMinutes(9))));
		assertThat(isBehind(), is(false));
	}

	private static MintSupply forged(Instant stamp) {
		final MintSupply spork = proposal(stamp);

		spork.setSignature(new byte[64]);
		return spork;
	}

	@Example
	public void verifiesNothingForASporkThatCannotMakeTheNodeBehind() {
		final MintSupply stored = agreement(ago(Duration.ofHours(1)));
		final MintSupply older = agreement(ago(Duration.ofHours(2)));
		final MintSupply newer = proposal(ago(Duration.ofMinutes(5)));

		database.set(stored);
		keyLookups = 0;

		lag.record(older, database, PEER);
		lag.record(stored, database, PEER);
		assertThat(keyLookups, is(0));

		lag.record(newer, database, PEER);
		assertThat(keyLookups, is(1));
	}

	@Example
	public void verifiesOneSporkOfATypeFromASourceAndNoMoreInASecondHoweverManyAreSent() {
		final MintSupply forged = forged(ago(Duration.ofMinutes(9)));

		keyLookups = 0;
		IntStream.range(0, 1000).forEach(n -> lag.record(forged, database, PEER));

		assertThat(keyLookups, is(1));
	}

	@Example
	public void verifiesAgainWhenASecondHasPassedAndTheTypesCountApart() {
		keyLookups = 0;
		lag.record(forged(ago(Duration.ofMinutes(9))), database, PEER);
		lag.record(forged(ago(Duration.ofMinutes(8))), database, PEER);
		assertThat(keyLookups, is(1));

		lag.record(proposal(new LedgerFee(), ago(Duration.ofMinutes(7)), proposer), database, PEER);
		assertThat(keyLookups, is(2));

		now = now.plus(SporkLag.VERIFY_GAP).minusMillis(1);
		lag.record(forged(ago(Duration.ofMinutes(6))), database, PEER);
		assertThat(keyLookups, is(2));

		now = now.plusMillis(1);
		lag.record(forged(ago(Duration.ofMinutes(5))), database, PEER);
		assertThat(keyLookups, is(3));
	}

	@Example
	public void doesNotRememberASporkThatWasTurnedAwayForComingTooSoonButTheNextOneIsHeard() {
		lag.record(forged(ago(Duration.ofMinutes(9))), database, PEER);
		lag.record(proposal(ago(Duration.ofMinutes(5))), database, PEER);
		assertThat(isBehind(), is(false));

		now = now.plus(SporkLag.VERIFY_GAP);
		lag.record(proposal(ago(Duration.ofMinutes(5))), database, PEER);
		assertThat(isBehind(), is(true));
	}

	@Example
	public void hearsAGenuineSporkFromAnotherSourceAfterABurstOfForgedOnesFromOne() {
		final MintSupply forged = forged(ago(Duration.ofMinutes(9)));

		IntStream.range(0, 1000).forEach(n -> lag.record(forged, database, PEER));
		assertThat(isBehind(), is(false));

		lag.record(proposal(ago(Duration.ofMinutes(5))), database, "203.0.113.9");
		assertThat(isBehind(), is(true));
	}

	@Example
	public void verifiesAtMostTheCeilingOfSporksOfATypeInASecondHoweverManySourcesSendThem() {
		final MintSupply forged = forged(ago(Duration.ofMinutes(9)));

		keyLookups = 0;
		IntStream.range(0, 1000).forEach(n -> lag.record(forged, database, "2001:db8:" + n + "::/64"));
		assertThat(keyLookups, is(VerifySlots.CEILING));

		now = now.plus(SporkLag.VERIFY_GAP);
		IntStream.range(0, 1000).forEach(n -> lag.record(forged, database, "2001:db8:" + n + "::/64"));
		assertThat(keyLookups, is(2 * VerifySlots.CEILING));
	}

	@Example
	public void countsTheCeilingForEachTypeApart() {
		final MintSupply supply = forged(ago(Duration.ofMinutes(9)));

		keyLookups = 0;
		IntStream.range(0, 2 * VerifySlots.CEILING).forEach(n -> lag.record(supply, database, "source-" + n));
		lag.record(proposal(new LedgerFee(), ago(Duration.ofMinutes(7)), proposer), database, "source-0");

		assertThat(keyLookups, is(VerifySlots.CEILING + 1));
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
		final AtomicLong seconds = new AtomicLong();

		/* Every look at the clock is a second later, so that none of the writers is turned away for coming soon */
		lag = new SporkLag(() -> NOW.plusSeconds(seconds.incrementAndGet()));
		Collections.shuffle(sporks);
		sporks.forEach(spork -> work.add(pool.submit(() -> lag.record(spork, database, PEER))));
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
