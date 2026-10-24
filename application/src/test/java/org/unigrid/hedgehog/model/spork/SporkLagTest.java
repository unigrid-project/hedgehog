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
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

public class SporkLagTest {
	private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
	private static final String PEER = "198.51.100.7";

	/* What the bucket of an inbound peer holds, and what inbound peers may spend on a type in a second */
	private static final int SHARE = VerifySlots.shareOf(SporkLag.Pool.OTHER);
	private static final int CEILING = VerifySlots.ceilingOf(SporkLag.Pool.OTHER, VerifySlots.TYPE_CEILING);

	private static Signature proposer;
	private static Signature cosigner;

	/* How often the current network keys were asked for: once for each spork the lag has to check, as both of its
	   signatures are checked against one reading of the keys */
	private static final int LOOKUPS_PER_CHECK = 1;
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

	/* Co-signing checks both signatures, which a node that hears the spork from a peer has not */
	@SneakyThrows
	private static <S extends GridSpork> S agreed(S spork) {
		spork.cosign(cosigner.getPrivateKey());
		NetworkKey.reset();
		return spork;
	}

	private static MintSupply agreement(Instant stamp) {
		return agreed(proposal(stamp));
	}

	private Instant ago(Duration age) {
		return now.minus(age);
	}

	/* A copy of its own, as each message from a peer is: the same spork object is checked once */
	private static <S extends GridSpork> S heard(S spork) {
		return SerializationUtils.clone(spork);
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
		record(agreed(proposal(new LedgerFee(), ago(Duration.ofMinutes(5)), proposer)));

		assertThat(lag.isBehind(Type.MINT_SUPPLY, database), is(true));
		assertThat(lag.isBehind(Type.LEDGER_FEE, database), is(true));
		assertThat(lag.isBehind(Type.MINT_STORAGE, database), is(false));
		assertThat(lag.isBehind(Type.UNDEFINED, database), is(false));
	}

	/* A proposal waits on the network for its cosignature and changes nothing until it has it */
	@Example
	public void isNotBehindForAProposalUntilItIsCosigned() {
		final MintSupply spork = proposal(ago(Duration.ofMinutes(5)));

		database.set(agreement(ago(Duration.ofHours(2))));
		record(spork);
		assertThat(isBehind(), is(false));

		record(agreed(spork));
		assertThat(isBehind(), is(true));
	}

	@Example
	public void isNotBehindForAProposalOfATypeUnsetInTheDatabase() {
		record(proposal(ago(Duration.ofMinutes(5))));

		assertThat(isBehind(), is(false));
	}

	@Example
	public void staysBehindForAnAgreedSporkWhileALaterProposalWaits() {
		database.set(agreement(ago(Duration.ofHours(2))));
		record(agreement(ago(Duration.ofMinutes(30))));
		record(proposal(ago(Duration.ofMinutes(5))));

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
		final MintSupply spork = agreement(stamp);

		spork.setSignature(new byte[64]);
		return spork;
	}

	@Example
	public void verifiesNothingForASporkThatCannotMakeTheNodeBehind() {
		final MintSupply stored = agreement(ago(Duration.ofHours(1)));
		final MintSupply older = agreement(ago(Duration.ofHours(2)));
		final MintSupply newer = agreement(ago(Duration.ofMinutes(5)));

		database.set(stored);
		keyLookups = 0;

		lag.record(older, database, PEER);
		lag.record(stored, database, PEER);
		assertThat(keyLookups, is(0));

		lag.record(newer, database, PEER);
		assertThat(keyLookups, is(LOOKUPS_PER_CHECK));
	}

	@Example
	public void verifiesOneSporkOfATypeFromASourceAndNoMoreInASecondHoweverManyAreSent() {
		final MintSupply forged = forged(ago(Duration.ofMinutes(9)));

		keyLookups = 0;
		IntStream.range(0, 1000).forEach(n -> lag.record(forged, database, PEER));

		assertThat(keyLookups, is(LOOKUPS_PER_CHECK));
	}

	/* What a source has paid for the checks of the sporks of a type that it sent */
	private double paidBy(Type type, String source) {
		return SHARE - lag.left(type, source);
	}

	/* Forged sporks of the source, each one new, as many as it takes to spend its bucket and more */
	private void spendTheBucketOf(String source) {
		IntStream.range(0, SHARE).forEach(n -> lag.record(forged(ago(Duration.ofMinutes(9))), database, source));
	}

	@Example
	public void checksTheSporksOfASourceAsFarAsItsBucketPaysAndAgainOnceItFillsAndTheTypesCountApart() {
		spendTheBucketOf(PEER);
		assertThat(paidBy(Type.MINT_SUPPLY, PEER), is((double) SHARE));

		/* What an inbound source holds for every type it spent with one, while the type holds checks for others */
		lag.record(agreed(proposal(new LedgerFee(), ago(Duration.ofMinutes(7)), proposer)), database, PEER);
		assertThat(lag.isBehind(Type.LEDGER_FEE, database), is(false));

		lag.record(agreed(proposal(new LedgerFee(), ago(Duration.ofMinutes(7)), proposer)), database, "203.0.113.9");
		assertThat(lag.isBehind(Type.LEDGER_FEE, database), is(true));

		lag.record(agreement(ago(Duration.ofMinutes(5))), database, PEER);
		assertThat(isBehind(), is(false));

		now = now.plus(SporkLag.VERIFY_GAP);
		lag.record(agreement(ago(Duration.ofMinutes(5))), database, PEER);
		assertThat(isBehind(), is(true));
	}

	@Example
	public void doesNotRememberASporkThatItsSourceCouldNotPayForButTheNextOneIsHeard() {
		spendTheBucketOf(PEER);
		lag.record(agreement(ago(Duration.ofMinutes(5))), database, PEER);
		assertThat(isBehind(), is(false));

		now = now.plus(SporkLag.VERIFY_GAP);
		lag.record(agreement(ago(Duration.ofMinutes(5))), database, PEER);
		assertThat(isBehind(), is(true));
	}

	@Example
	public void hearsAGenuineSporkFromAnotherSourceAfterABurstOfForgedOnesFromOne() {
		final MintSupply forged = forged(ago(Duration.ofMinutes(9)));

		IntStream.range(0, 1000).forEach(n -> lag.record(forged, database, PEER));
		assertThat(isBehind(), is(false));

		lag.record(agreement(ago(Duration.ofMinutes(5))), database, "203.0.113.9");
		assertThat(isBehind(), is(true));
	}

	@Example
	public void paysForAtMostTheCeilingOfChecksOfATypeInASecondHoweverManySourcesSendThem() {
		final MintSupply forged = forged(ago(Duration.ofMinutes(9)));
		final List<String> sources = IntStream.range(0, 1000).mapToObj(n -> "2001:db8:" + n + "::/64").toList();

		sources.forEach(source -> lag.record(heard(forged), database, source));
		assertThat(sources.stream().mapToDouble(source -> paidBy(Type.MINT_SUPPLY, source)).sum(),
			is((double) CEILING));

		now = now.plus(SporkLag.VERIFY_GAP);
		keyLookups = 0;
		sources.forEach(source -> lag.record(heard(forged), database, source));
		assertThat(keyLookups > 0, is(true));
		assertThat(keyLookups <= CEILING, is(true));
	}

	@Example
	public void leavesWhatThisNodeProposesItselfUnlimitedAndHearsEachOfIt() {
		final MintSupply forged = forged(ago(Duration.ofMinutes(9)));

		keyLookups = 0;
		IntStream.range(0, 5).forEach(n -> lag.record(heard(forged), database, SporkLag.LOCAL));
		assertThat(keyLookups, is(5 * LOOKUPS_PER_CHECK));

		lag.record(agreement(ago(Duration.ofMinutes(5))), database, SporkLag.LOCAL);
		assertThat(isBehind(), is(true));
	}

	/* What a genuine spork cost its source is given back whole, however many checks it took */
	@Example
	public void chargesATurnForEachCheckItPaysForAndGivesItAllBack() {
		final SporkLag.Turn turn = lag.turnOf(Type.MINT_SUPPLY, PEER);

		assertThat(turn.charging(agreement(ago(Duration.ofMinutes(5)))::isDoublySigned), is(true));
		assertThat(paidBy(Type.MINT_SUPPLY, PEER) >= 2, is(true));

		turn.giveBack();
		assertThat(paidBy(Type.MINT_SUPPLY, PEER), is(0.0));
	}

	@Example
	public void takesNoSlotForATurnThatNoCheckAsksFor() {
		lag.turnOf(Type.MINT_SUPPLY, PEER).giveBack();
		lag.turnOf(Type.MINT_SUPPLY, PEER);

		assertThat(lag.turnOf(Type.MINT_SUPPLY, PEER).getAsBoolean(), is(true));
	}

	@Example
	public void givesBackNothingForChecksThatWereNotPaidFor() {
		spendTheBucketOf(PEER);

		final SporkLag.Turn refused = lag.turnOf(Type.MINT_SUPPLY, PEER);

		assertThat(refused.getAsBoolean(), is(false));
		assertThat(refused.charging(agreement(ago(Duration.ofMinutes(5)))::isDoublySigned), is(false));

		refused.giveBack();
		assertThat(paidBy(Type.MINT_SUPPLY, PEER), is((double) SHARE));
	}

	@Example
	public void countsTheCeilingForEachTypeApart() {
		final MintSupply supply = forged(ago(Duration.ofMinutes(9)));

		IntStream.range(0, 2 * CEILING).forEach(n -> lag.record(heard(supply), database,
			"source-" + n));
		assertThat(lag.turnOf(Type.MINT_SUPPLY, "another").getAsBoolean(), is(false));

		lag.record(agreed(proposal(new LedgerFee(), ago(Duration.ofMinutes(7)), proposer)), database, "source-0");
		assertThat(lag.isBehind(Type.LEDGER_FEE, database), is(true));
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
