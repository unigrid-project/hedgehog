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

package org.unigrid.hedgehog.service.ledger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.LegacySignature;
import org.unigrid.hedgehog.ledger.Mint;
import org.unigrid.hedgehog.ledger.MintSchedule;
import org.unigrid.hedgehog.ledger.Reference;
import org.unigrid.hedgehog.ledger.Transfer;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.spork.ChunkData;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

public class SporkLedgerAuthorityTest {
	private static final HexFormat HEX = HexFormat.of();
	private static final long TIP_HEIGHT = 1_000;
	private static final long TIP_TIME = 1_700_000_000_000L;
	private static final MintSchedule SCHEDULE = new MintSchedule(TIP_HEIGHT, TIP_TIME);
	private static final long UNITS_PER_COIN = 100_000_000L;
	private static final long COINS_OF_AN_ENTRY = 10;
	private static final long UNITS_OF_AN_ENTRY = COINS_OF_AN_ENTRY * UNITS_PER_COIN;
	private static final int SECONDS_OF_AN_ENTRY = 100;
	private static final long DUE_OF_AN_ENTRY = SECONDS_OF_AN_ENTRY * 1000L;
	private static final long FAR_FUTURE = Long.MAX_VALUE;

	/* One authority over a database the test fills in, a lag that says what the test tells it to, and the readiness
	   and supply it is given. Every read of the database is counted. */
	private static final class Rig {
		private final Set<Type> behind = ConcurrentHashMap.newKeySet();
		private final List<SporkDatabase> asked = new CopyOnWriteArrayList<>();
		private final AtomicInteger reads = new AtomicInteger();
		private volatile RuntimeException unreadable;
		private volatile RuntimeException unaskable;
		private volatile RuntimeException unsupplied;
		private volatile RuntimeException unminted;
		private volatile SporkDatabase database = SporkDatabase.builder().build();
		private volatile long supply;
		private final Map<org.unigrid.hedgehog.ledger.Address, Long> minted = new ConcurrentHashMap<>();
		private final SporkLedgerAuthority real = new SporkLedgerAuthority(this::read, lag(), SCHEDULE,
			this::supplied, this::mintedTo);
		private final Asked authority = new Asked();

		/* The questions of the ledger, a mint being asked on the supply and the minted totals of the rig */
		private final class Asked {
			List<Mint> due(long time) {
				return real.due(time);
			}

			boolean authorizes(Mint mint, long time) {
				return real.authorizes(mint, time, supply, mintedTo(mint.recipient()));
			}

			boolean authorizes(Claim claim) {
				return real.authorizes(claim);
			}

			boolean authorizes(Transfer transfer) {
				return real.authorizes(transfer);
			}

			long leastNewAccountAmount() {
				return real.leastNewAccountAmount();
			}
		}

		/* Every spork is set, none is behind, and the one entry of the mint spork is due at 100 seconds */
		static Rig open() {
			final Rig rig = new Rig();

			rig.store(grant(accountEntry(1, SECONDS_OF_AN_ENTRY), "" + COINS_OF_AN_ENTRY));
			rig.cap("1000000");
			rig.fee(0);
			rig.blacklist(Set.of());
			return rig;
		}

		private SporkDatabase read() {
			reads.incrementAndGet();

			if (unreadable != null) {
				throw unreadable;
			}

			return database;
		}

		private long supplied() {
			if (unsupplied != null) {
				throw unsupplied;
			}

			return supply;
		}

		private long mintedTo(org.unigrid.hedgehog.ledger.Address recipient) {
			if (unminted != null) {
				throw unminted;
			}

			return minted.getOrDefault(recipient, 0L);
		}

		private SporkLag lag() {
			return new SporkLag() {
				@Override
				public boolean isBehind(Type type, SporkDatabase asking) {
					asked.add(asking);

					if (unaskable != null) {
						throw unaskable;
					}

					return behind.contains(type);
				}
			};
		}

		void store(Map<Location, BigDecimal> mints) {
			database.setMintStorage(mintSpork(mints));
		}

		void cap(String coins) {
			database.setMintSupply(supplySpork(new BigDecimal(coins)));
		}

		void fee(long units) {
			database.setLedgerFee(feeSpork(BigDecimal.valueOf(units, 8)));
		}

		void blacklist(Set<String> hashes) {
			database.setLedgerBlacklist(blacklistSpork(hashes));
		}
	}

	private static <S extends GridSpork> S withData(S spork, ChunkData data) {
		spork.setData(data);
		return spork;
	}

	private static MintStorage mintSpork(Map<Location, BigDecimal> mints) {
		final MintStorage spork = new MintStorage();

		spork.<MintStorage.SporkData>getData().setMints(mints);
		return spork;
	}

	private static MintSupply supplySpork(BigDecimal maximum) {
		final MintSupply spork = new MintSupply();

		spork.<MintSupply.SporkData>getData().setMaxSupply(maximum);
		return spork;
	}

	private static LedgerFee feeSpork(BigDecimal minimum) {
		final LedgerFee spork = new LedgerFee();

		spork.<LedgerFee.SporkData>getData().setMinimumTransferFee(minimum);
		return spork;
	}

	private static LedgerBlacklist blacklistSpork(Set<String> hashes) {
		final LedgerBlacklist spork = new LedgerBlacklist();

		spork.<LedgerBlacklist.SporkData>getData().setLegacyAddresses(hashes);
		return spork;
	}

	private static byte[] bytesOf(long seed, int size) {
		return ByteBuffer.allocate(size).putLong(seed).array();
	}

	private static String accountHex(long seed) {
		return HEX.formatHex(bytesOf(seed, AccountKey.SIZE));
	}

	private static String oldAddress(long seed) {
		return LegacyAddress.encode(bytesOf(seed, org.unigrid.hedgehog.ledger.LegacyAddress.SIZE));
	}

	private static Location locationOf(String address, int number) {
		return Location.builder().address(new Address(address)).height(number).build();
	}

	private static Location accountEntry(long seed, int seconds) {
		return locationOf(accountHex(seed), seconds);
	}

	private static Location oldEntry(long seed, int height) {
		return locationOf(oldAddress(seed), height);
	}

	private static Map<Location, BigDecimal> grant(Location location, String coins) {
		return Map.of(location, new BigDecimal(coins));
	}

	private static Mint accountMint(long seed, long seconds, long units) {
		final AccountKey recipient = AccountKey.fromHex(accountHex(seed));

		return new Mint(recipient, units, Reference.ofSpork(recipient, seconds));
	}

	private static Mint oldMint(long seed, long height, long units) {
		final var recipient = new org.unigrid.hedgehog.ledger.LegacyAddress(
			bytesOf(seed, org.unigrid.hedgehog.ledger.LegacyAddress.SIZE));

		return new Mint(recipient, units, Reference.ofSpork(recipient, height));
	}

	private static Mint theMintOfAnEntry() {
		return accountMint(1, SECONDS_OF_AN_ENTRY, UNITS_OF_AN_ENTRY);
	}

	private static Claim claimOfKey(int seed) {
		final byte[] key = new byte[33];

		key[0] = 2;
		key[1] = (byte) seed;
		key[2] = (byte) (seed >> 8);
		return new Claim(key, AccountKey.fromHex(accountHex(seed)), new byte[LegacySignature.SIZE]);
	}

	private static String hashOf(Claim claim) {
		return HEX.formatHex(claim.address().bytes());
	}

	private static Transfer transferWithFee(long fee) {
		return new Transfer(AccountKey.fromHex(accountHex(1)), AccountKey.fromHex(accountHex(2)), 1, fee, 0,
			new byte[Ed25519.SIGNATURE_SIZE]);
	}

	@Provide
	public Arbitrary<BigDecimal> amounts() {
		final BigDecimal limit = new BigDecimal("92233720369");

		return Arbitraries.integers().between(0, 12).flatMap(scale -> Arbitraries.bigDecimals()
			.between(BigDecimal.ZERO, limit).ofScale(scale));
	}

	@Provide
	public Arbitrary<Set<Long>> seeds() {
		return Arbitraries.longs().between(1, 1_000_000).set().ofMinSize(2).ofMaxSize(40);
	}

	@Example
	public void shouldListAnEntryOfAnAccountAtItsTimeAndNotOneMillisecondBefore() {
		final Rig rig = Rig.open();

		assertThat(rig.authority.due(DUE_OF_AN_ENTRY - 1), is(empty()));
		assertThat(rig.authority.due(DUE_OF_AN_ENTRY), contains(theMintOfAnEntry()));
		assertThat(rig.authority.due(DUE_OF_AN_ENTRY + 1), contains(theMintOfAnEntry()));
	}

	@Example
	public void shouldAuthorizeAnEntryOfAnAccountFromItsTimeAndNotOneMillisecondBefore() {
		final Rig rig = Rig.open();

		assertThat(rig.authority.authorizes(theMintOfAnEntry(), DUE_OF_AN_ENTRY - 1), is(false));
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), DUE_OF_AN_ENTRY), is(true));
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), DUE_OF_AN_ENTRY + 1), is(true));
	}

	@Example
	public void shouldTimeAnEntryOfTheOldChainByTheScheduleOfTheSnapshot() {
		final Rig rig = Rig.open();
		final long due = SCHEDULE.dueTime(TIP_HEIGHT + 3);
		final Mint mint = oldMint(7, TIP_HEIGHT + 3, UNITS_OF_AN_ENTRY);

		rig.store(grant(oldEntry(7, (int) TIP_HEIGHT + 3), "10"));

		assertThat(rig.authority.due(due - 1), is(empty()));
		assertThat(rig.authority.due(due), contains(mint));
		assertThat(rig.authority.authorizes(mint, due - 1), is(false));
		assertThat(rig.authority.authorizes(mint, due), is(true));
	}

	@Example
	public void shouldTimeAnEntryOfAnAccountByTheSecondsWhateverTheTipIs() {
		final Rig rig = Rig.open();

		rig.store(grant(accountEntry(2, 5), "10"));

		assertThat(rig.authority.due(4_999), is(empty()));
		assertThat(rig.authority.due(5_000), contains(accountMint(2, 5, UNITS_OF_AN_ENTRY)));
	}

	@Example
	public void shouldReadTheSecondsOfAnAccountAsUnsignedThirtyTwoBits() {
		final Rig rig = Rig.open();
		final long seconds = 4_000_000_000L;

		rig.store(grant(accountEntry(2, (int) seconds), "10"));

		assertThat(rig.authority.due(seconds * 1000 - 1), is(empty()));
		assertThat(rig.authority.due(seconds * 1000), contains(accountMint(2, seconds, UNITS_OF_AN_ENTRY)));
	}

	@Example
	public void shouldMakeAnotherGrantOfAnEntryForTheSameAccountAtAnotherTime() {
		final Rig rig = Rig.open();
		final Map<Location, BigDecimal> mints = new LinkedHashMap<>();

		mints.put(accountEntry(2, 5), BigDecimal.ONE);
		mints.put(accountEntry(2, 6), BigDecimal.ONE);
		rig.store(mints);

		assertThat(rig.authority.due(FAR_FUTURE), containsInAnyOrder(accountMint(2, 5, UNITS_PER_COIN),
			accountMint(2, 6, UNITS_PER_COIN)));
	}

	@Property(tries = 50)
	public void shouldNeverListOrAuthorizeAnEntryOfTheOldChainAtOrBelowTheTip(
		@ForAll("heightsAtOrBelowTheTip") int height) {

		final Rig rig = Rig.open();

		rig.store(grant(oldEntry(7, height), "10"));

		assertThat(rig.authority.due(FAR_FUTURE), is(empty()));
		assertThat(rig.authority.authorizes(oldMint(7, height, UNITS_OF_AN_ENTRY), FAR_FUTURE), is(false));
	}

	@Provide
	public Arbitrary<Integer> heightsAtOrBelowTheTip() {
		return Arbitraries.integers().between(0, (int) TIP_HEIGHT);
	}

	@Example
	public void shouldListAnEntryOfTheOldChainOneAboveTheTip() {
		final Rig rig = Rig.open();

		rig.store(grant(oldEntry(7, (int) TIP_HEIGHT + 1), "10"));

		assertThat(rig.authority.due(SCHEDULE.dueTime(TIP_HEIGHT + 1)),
			contains(oldMint(7, TIP_HEIGHT + 1, UNITS_OF_AN_ENTRY)));
	}

	@Property(tries = 50)
	public void shouldListTheSameMintsInTheSameOrderWhateverTheOrderOfTheEntries(@ForAll("seeds") Set<Long> seeds,
		@ForAll long shuffle) {

		final List<Long> inserted = new ArrayList<>(seeds);
		final Rig rig = Rig.open();
		final Rig other = Rig.open();

		rig.store(entriesOf(inserted));
		Collections.shuffle(inserted, new Random(shuffle));
		other.store(entriesOf(inserted));

		final List<Mint> listed = rig.authority.due(FAR_FUTURE);

		assertThat(listed, hasSize(seeds.size()));
		assertThat(other.authority.due(FAR_FUTURE), equalTo(listed));
		assertThat(listed.stream().map(Mint::reference).toList(),
			equalTo(listed.stream().map(Mint::reference).sorted().toList()));
	}

	private static Map<Location, BigDecimal> entriesOf(List<Long> seeds) {
		final Map<Location, BigDecimal> entries = new LinkedHashMap<>();

		seeds.forEach(seed -> entries.put(seed % 2 == 0 ? accountEntry(seed, 5) : oldEntry(seed, (int) TIP_HEIGHT + 5),
			BigDecimal.TEN));

		return entries;
	}

	@Property(tries = 50)
	public void shouldAuthorizeExactlyWhatItListsAsDue(@ForAll("seeds") Set<Long> seeds, @ForAll("times") long time) {
		final Rig rig = Rig.open();
		final List<Mint> everyMint = seeds.stream().map(seed -> seed % 2 == 0 ? accountMint(seed, 5, UNITS_OF_AN_ENTRY)
			: oldMint(seed, TIP_HEIGHT + 5, UNITS_OF_AN_ENTRY)).toList();

		rig.store(entriesOf(new ArrayList<>(seeds)));

		final List<Mint> due = rig.authority.due(time);

		everyMint.forEach(mint -> assertThat(rig.authority.authorizes(mint, time), is(due.contains(mint))));
	}

	@Provide
	public Arbitrary<Long> times() {
		return Arbitraries.of(0L, 4_999L, 5_000L, SCHEDULE.dueTime(TIP_HEIGHT + 5) - 1,
			SCHEDULE.dueTime(TIP_HEIGHT + 5), FAR_FUTURE, Long.MIN_VALUE);
	}

	@Example
	public void shouldListEveryDueEntryAndLeaveTheCuttingOfTheBlockToTheLedger() {
		final Rig rig = Rig.open();
		final List<Long> seeds = LongStream.rangeClosed(1, 1_200).boxed().toList();

		rig.store(entriesOf(seeds));

		assertThat(rig.authority.due(FAR_FUTURE), hasSize(1_200));
	}

	/* The ledger judges every due mint once for each tick of the node and once for each block it checks, so a
	   judgement that looks through all the entries makes thousands of them take seconds and the node falls behind.
	   Four times the entries may take about four times as long, never the sixteen times of a scan per judgement; the
	   two are timed side by side, so a busy machine slows both and no bound in milliseconds is needed. */
	@Example
	public void shouldJudgeThousandsOfDueMintsInTimeThatDoesNotGrowWithTheSquareOfTheirNumber() {
		final long few = fastestJudgementOfAll(2_000);
		final long many = fastestJudgementOfAll(8_000);

		assertThat(many, lessThan(10 * few + Duration.ofMillis(20).toNanos()));
	}

	private static long fastestJudgementOfAll(int entries) {
		final Rig rig = Rig.open();

		rig.store(entriesOf(LongStream.rangeClosed(1, entries).boxed().toList()));

		final List<Mint> due = rig.authority.due(FAR_FUTURE);

		assertThat(due, hasSize(entries));

		return LongStream.range(0, 5).map(round -> {
			final long start = System.nanoTime();
			final long judged = due.stream().filter(mint -> rig.authority.authorizes(mint, FAR_FUTURE)).count();

			assertThat(judged, is((long) due.size()));
			return System.nanoTime() - start;
		}).min().orElseThrow();
	}

	@Property(tries = 200)
	public void shouldFloorEveryAmountAtEightDecimals(@ForAll("amounts") BigDecimal amount) {
		final Rig rig = Rig.open();
		final BigInteger units = amount.movePointRight(8).setScale(0, RoundingMode.DOWN).toBigInteger();
		final boolean isUnits = units.signum() > 0 && units.bitLength() < Long.SIZE;

		rig.cap("1E+12");
		rig.store(Map.of(accountEntry(2, 5), amount));

		assertThat(rig.authority.due(FAR_FUTURE).stream().map(Mint::amount).toList(),
			equalTo(isUnits ? List.of(units.longValueExact()) : List.of()));
	}

	@Example
	public void shouldCutAnAmountOfNineDecimalsInsteadOfRoundingItUp() {
		final Rig rig = Rig.open();

		rig.store(Map.of(accountEntry(2, 5), new BigDecimal("0.123456789")));

		assertThat(rig.authority.due(FAR_FUTURE), contains(accountMint(2, 5, 12_345_678)));
		assertThat(rig.authority.authorizes(accountMint(2, 5, 12_345_679), FAR_FUTURE), is(false));
		assertThat(rig.authority.authorizes(accountMint(2, 5, 12_345_678), FAR_FUTURE), is(true));
	}

	@Example
	public void shouldSkipAmountsThatAreNoUnitsOrDoNotFitALong() {
		final Rig rig = Rig.open();
		final Map<Location, BigDecimal> entries = new LinkedHashMap<>();

		entries.put(accountEntry(1, 5), new BigDecimal("92233720368.54775807"));
		entries.put(accountEntry(2, 5), new BigDecimal("92233720368.54775808"));
		entries.put(accountEntry(3, 5), new BigDecimal("1E+400"));
		entries.put(accountEntry(4, 5), new BigDecimal("1E+1000000000"));
		entries.put(accountEntry(5, 5), new BigDecimal("1E-1000000000"));
		entries.put(accountEntry(6, 5), new BigDecimal("0.000000009"));
		entries.put(accountEntry(7, 5), BigDecimal.ZERO);
		entries.put(accountEntry(8, 5), BigDecimal.valueOf(-1));
		entries.put(accountEntry(9, 5), null);
		rig.store(entries);
		rig.cap("1E+11");

		assertThat(rig.authority.due(FAR_FUTURE), contains(accountMint(1, 5, Long.MAX_VALUE)));
	}

	@Example
	public void shouldHoldTheMintsBackWhileTheMintSporkIsBehind() {
		final Rig rig = Rig.open();

		rig.behind.add(Type.MINT_STORAGE);
		assertThat(rig.authority.due(FAR_FUTURE), is(empty()));
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));

		rig.behind.clear();
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(true));
	}

	@Example
	public void shouldHoldTheMintsBackWhileTheSupplySporkIsBehind() {
		final Rig rig = Rig.open();

		rig.behind.add(Type.MINT_SUPPLY);

		assertThat(rig.authority.due(FAR_FUTURE), is(empty()));
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));
	}

	@Example
	public void shouldLetOnlyTheSporkItDependsOnHoldItBack() {
		final Rig rig = Rig.open();
		final Claim claim = claimOfKey(3);
		final Transfer transfer = transferWithFee(0);

		rig.behind.add(Type.LEDGER_BLACKLIST);
		assertThat(rig.authority.authorizes(claim), is(false));
		assertThat(rig.authority.authorizes(transfer), is(true));
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(true));

		rig.behind.clear();
		rig.behind.add(Type.LEDGER_FEE);
		assertThat(rig.authority.authorizes(claim), is(true));
		assertThat(rig.authority.authorizes(transfer), is(false));
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(true));
	}

	@Example
	public void shouldAskTheLagAboutTheDatabaseItReadAndNoOther() {
		final Rig rig = Rig.open();

		rig.authority.due(FAR_FUTURE);
		rig.authority.authorizes(claimOfKey(3));

		assertThat(rig.asked, is(not(empty())));
		assertThat(rig.asked.stream().allMatch(asked -> asked == rig.database), is(true));
	}

	@Example
	public void shouldRefuseAClaimOfAnAddressOnTheBlacklistAndAllowAnyOther() {
		final Rig rig = Rig.open();
		final Claim listed = claimOfKey(3);
		final Claim other = claimOfKey(4);

		rig.blacklist(new TreeSet<>(Set.of(hashOf(listed), "ab".repeat(20))));

		assertThat(rig.authority.authorizes(listed), is(false));
		assertThat(rig.authority.authorizes(other), is(true));
	}

	@Example
	public void shouldAllowAClaimWhenTheBlacklistIsEmptyAndRefuseItWhenTheSporkIsNotSet() {
		final Rig rig = Rig.open();
		final Claim claim = claimOfKey(3);

		assertThat(rig.authority.authorizes(claim), is(true));

		rig.database.setLedgerBlacklist(null);
		assertThat(rig.authority.authorizes(claim), is(false));
	}

	/* The board switched off a key that signed the sporks, which then decide nothing until it signs them again */
	@Example
	public void shouldDecideNothingByASporkThatDoesNotCount() {
		final Rig rig = Rig.open();

		new MockUp<GridSpork>() {
			@Mock public boolean isCounting() {
				return false;
			}
		};

		assertThat(rig.authority.authorizes(claimOfKey(3)), is(false));
		assertThat(rig.authority.leastNewAccountAmount(), is(0L));
		assertThat(rig.authority.due(FAR_FUTURE), empty());
	}

	/* The sporks of these tests are signed by no one, and stand for sporks that count */
	@BeforeTry
	public void countEverySpork() {
		new MockUp<GridSpork>() {
			@Mock public boolean isCounting() {
				return true;
			}
		};
	}

	@Example
	public void shouldTakeAMintUpToTheMaximumSupplyAndNotOneUnitMore() {
		final Rig rig = Rig.open();

		rig.cap("100");
		rig.supply = 100 * UNITS_PER_COIN - UNITS_OF_AN_ENTRY;
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(true));
		assertThat(rig.authority.due(FAR_FUTURE), contains(theMintOfAnEntry()));

		rig.supply++;
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));
		assertThat(rig.authority.due(FAR_FUTURE), is(empty()));
	}

	@Example
	public void shouldCutTheMaximumSupplyAtEightDecimalsInsteadOfRoundingItUp() {
		final Rig rig = Rig.open();

		rig.cap("100.000000009");
		rig.supply = 100 * UNITS_PER_COIN - UNITS_OF_AN_ENTRY + 1;

		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));
	}

	@Example
	public void shouldRefuseEveryMintWhenTheMaximumSupplyIsNotSet() {
		final Rig rig = Rig.open();

		rig.database.setMintSupply(null);

		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));
		assertThat(rig.authority.due(FAR_FUTURE), is(empty()));
	}

	@Example
	public void shouldRefuseEveryMintWhenTheMaximumSupplyIsZeroOrBelow() {
		final Rig rig = Rig.open();

		rig.cap("0");
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));

		rig.cap("-5");
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));

		rig.cap("1E-1000000000");
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));
	}

	@Example
	public void shouldTakeAMintWhenTheMaximumSupplyIsMoreThanALongCanCount() {
		final Rig rig = Rig.open();

		rig.cap("1E+1000000000");
		rig.supply = Long.MAX_VALUE - UNITS_OF_AN_ENTRY;
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(true));

		rig.cap("92233720368.54775808");
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(true));
	}

	@Example
	public void shouldRefuseAMintWhenTheSupplyIsAlreadyPastTheMaximum() {
		final Rig rig = Rig.open();

		rig.cap("100");
		rig.supply = Long.MAX_VALUE;

		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));
		assertThat(rig.authority.due(FAR_FUTURE), is(empty()));
	}

	@Property(tries = 100)
	public void shouldTakeATransferExactlyFromTheMinimumFeeUp(@ForAll @LongRange(min = 0, max = 100_000_000L)
		long minimum, @ForAll @IntRange(min = -2, max = 2) int offset) {

		final Rig rig = Rig.open();
		final long fee = Math.max(0, minimum + offset);

		rig.fee(minimum);

		assertThat(rig.authority.authorizes(transferWithFee(fee)), is(fee >= minimum));
	}

	@Example
	public void shouldTakeTheMinimumFeeAndNotOneUnitLess() {
		final Rig rig = Rig.open();

		rig.fee(1_000);

		assertThat(rig.authority.authorizes(transferWithFee(999)), is(false));
		assertThat(rig.authority.authorizes(transferWithFee(1_000)), is(true));
		assertThat(rig.authority.authorizes(transferWithFee(Long.MAX_VALUE)), is(true));
	}

	@Example
	public void shouldRefuseEveryTransferWhenTheFeeSporkIsNotSet() {
		final Rig rig = Rig.open();

		rig.database.setLedgerFee(null);

		assertThat(rig.authority.authorizes(transferWithFee(Long.MAX_VALUE)), is(false));
	}

	@Example
	public void shouldTakeATransferWhateverItsFeeWhenTheMinimumIsZero() {
		final Rig rig = Rig.open();

		assertThat(rig.authority.authorizes(transferWithFee(0)), is(true));
	}

	@Example
	public void shouldDemandTheAmountToANewAccountThatTheFeeSporkNames() {
		final Rig rig = Rig.open();
		final LedgerFee spork = feeSpork(BigDecimal.ZERO);

		assertThat(rig.authority.leastNewAccountAmount(), is(0L));

		spork.<LedgerFee.SporkData>getData().setMinimumNewAccountAmount(new BigDecimal("1.5"));
		rig.database.setLedgerFee(spork);
		assertThat(rig.authority.leastNewAccountAmount(), is(150_000_000L));

		rig.database.setLedgerFee(null);
		assertThat(rig.authority.leastNewAccountAmount(), is(0L));
	}

	@Example
	public void shouldRequireTheRecipientTheAmountAndTheReferenceOfTheEntry() {
		final Rig rig = Rig.open();
		final Mint exact = theMintOfAnEntry();
		final AccountKey stranger = AccountKey.fromHex(accountHex(9));

		assertThat(rig.authority.authorizes(exact, FAR_FUTURE), is(true));
		assertThat(rig.authority.authorizes(accountMint(1, SECONDS_OF_AN_ENTRY, exact.amount() - 1), FAR_FUTURE),
			is(false));

		assertThat(rig.authority.authorizes(accountMint(1, SECONDS_OF_AN_ENTRY, exact.amount() + 1), FAR_FUTURE),
			is(false));

		assertThat(rig.authority.authorizes(accountMint(1, SECONDS_OF_AN_ENTRY + 1, exact.amount()), FAR_FUTURE),
			is(false));

		assertThat(rig.authority.authorizes(new Mint(stranger, exact.amount(), exact.reference()), FAR_FUTURE),
			is(false));

		assertThat(rig.authority.authorizes(accountMint(9, SECONDS_OF_AN_ENTRY, exact.amount()), FAR_FUTURE),
			is(false));
	}

	@Example
	public void shouldNotMistakeTheMintOfAnOldAddressForOneOfAnAccountWithTheSameBytes() {
		final Rig rig = Rig.open();
		final Mint old = oldMint(1, SECONDS_OF_AN_ENTRY, UNITS_OF_AN_ENTRY);

		assertThat(rig.authority.authorizes(old, FAR_FUTURE), is(false));
	}

	@Example
	public void shouldFollowTheSporkAtEveryCallAndKeepNothing() {
		final Rig rig = Rig.open();

		assertThat(rig.authority.due(FAR_FUTURE), hasSize(1));

		rig.store(Map.of());
		assertThat(rig.authority.due(FAR_FUTURE), is(empty()));
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));

		rig.store(grant(accountEntry(1, SECONDS_OF_AN_ENTRY), "10"));
		assertThat(rig.authority.due(FAR_FUTURE), hasSize(1));

		final Claim claim = claimOfKey(3);

		assertThat(rig.authority.authorizes(claim), is(true));
		rig.blacklist(Set.of(hashOf(claim)));
		assertThat(rig.authority.authorizes(claim), is(false));

		rig.fee(5);
		assertThat(rig.authority.authorizes(transferWithFee(4)), is(false));
		rig.fee(4);
		assertThat(rig.authority.authorizes(transferWithFee(4)), is(true));
	}

	@Example
	public void shouldReadTheDatabaseOnceInEveryCall() {
		final Rig rig = Rig.open();
		final Claim claim = claimOfKey(3);
		final Runnable[] calls = {
			() -> rig.authority.due(FAR_FUTURE),
			() -> rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE),
			() -> rig.authority.authorizes(claim),
			() -> rig.authority.authorizes(transferWithFee(1))
		};

		for (final Runnable call : calls) {
			rig.reads.set(0);
			call.run();
			assertThat(rig.reads.get(), is(1));
		}
	}

	@Example
	public void shouldSkipEntriesItCannotReadAndGoOnWithTheOthers() {
		final Rig rig = Rig.open();
		final Map<Location, BigDecimal> entries = new LinkedHashMap<>();

		entries.put(locationOf("garbage", 5), BigDecimal.ONE);
		entries.put(locationOf("", 5), BigDecimal.ONE);
		entries.put(locationOf(accountHex(0xab).toUpperCase(), 5), BigDecimal.ONE);
		entries.put(locationOf(null, 5), BigDecimal.ONE);
		entries.put(new Location(null, 5), BigDecimal.ONE);
		entries.put(null, BigDecimal.ONE);
		entries.put(accountEntry(1, SECONDS_OF_AN_ENTRY), BigDecimal.TEN);
		rig.store(entries);

		assertThat(rig.authority.due(FAR_FUTURE), contains(theMintOfAnEntry()));
	}

	@Example
	public void shouldTellOnceWhichEntryItSkips() {
		final Rig rig = Rig.open();

		rig.store(grant(locationOf("garbage-address", 5), "1"));

		try (DebugLog log = new DebugLog(SporkLedgerAuthority.class)) {
			rig.authority.due(FAR_FUTURE);
			rig.authority.due(FAR_FUTURE);
			rig.authority.authorizes(accountMint(1, 5, 1), FAR_FUTURE);

			final List<ILoggingEvent> told = log.events();

			assertThat(told, hasSize(1));
			assertThat(told.get(0).getLevel(), is(Level.DEBUG));
			assertThat(told.get(0).getFormattedMessage(), containsString("garbage-address"));
		}
	}

	@Example
	public void shouldTellOnceWhichAmountItSkips() {
		final Rig rig = Rig.open();

		rig.store(grant(accountEntry(2, 5), "0.000000009"));

		try (DebugLog log = new DebugLog(SporkLedgerAuthority.class)) {
			rig.authority.due(FAR_FUTURE);
			rig.authority.due(FAR_FUTURE);

			assertThat(log.events(), hasSize(1));
			assertThat(log.events().get(0).getFormattedMessage(), containsString(accountHex(2)));
		}
	}

	@Example
	public void shouldNotTellOfAnEntryAtOrBelowTheTipOfTheSnapshot() {
		final Rig rig = Rig.open();

		rig.store(grant(oldEntry(7, 5), "10"));

		try (DebugLog log = new DebugLog(SporkLedgerAuthority.class)) {
			rig.authority.due(FAR_FUTURE);

			assertThat(log.events(), is(empty()));
		}
	}

	@Example
	public void shouldAnswerNoToEverythingWhenTheDatabaseCannotBeRead() {
		final Rig rig = Rig.open();

		rig.unreadable = new IllegalStateException("The database is being replaced");

		try (DebugLog log = new DebugLog(SporkLedgerAuthority.class)) {
			for (int call = 0; call < 3; call++) {
				assertNothingIsAllowed(rig);
			}

			assertThat(log.events(), hasSize(4));
			assertThat(log.events().stream().allMatch(event -> event.getLevel() == Level.DEBUG), is(true));
		}
	}

	@Example
	public void shouldAnswerNoToEverythingWhenTheLagCannotBeAsked() {
		final Rig rig = Rig.open();

		rig.unaskable = new IllegalStateException("The lag is being replaced");
		assertNothingIsAllowed(rig);
	}

	private static void assertNothingIsAllowed(Rig rig) {
		assertThat(rig.authority.due(FAR_FUTURE), is(empty()));
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));
		assertThat(rig.authority.authorizes(claimOfKey(3)), is(false));
		assertThat(rig.authority.authorizes(transferWithFee(1)), is(false));
	}

	@Example
	public void shouldAnswerNoToEverythingWithoutADatabaseOrWithAnEmptyOne() {
		final Rig rig = new Rig();

		rig.database = null;
		assertNothingIsAllowed(rig);

		rig.database = SporkDatabase.builder().build();
		assertNothingIsAllowed(rig);
	}

	@Example
	public void shouldAnswerNoToWhatTheSporksHoldNothingUsefulFor() {
		final Rig rig = Rig.open();
		final MintStorage noMap = mintSpork(null);
		final MintStorage noData = withData(new MintStorage(), null);
		final MintStorage strange = withData(new MintStorage(), new MintSupply.SporkData());

		for (final MintStorage spork : List.of(noMap, noData, strange)) {
			rig.database.setMintStorage(spork);
			assertThat(rig.authority.due(FAR_FUTURE), is(empty()));
			assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));
		}
	}

	@Example
	public void shouldAnswerNoToAClaimWhenTheBlacklistHoldsNothingUsefulOrIsMalformed() {
		final Rig rig = Rig.open();
		final Claim claim = claimOfKey(3);

		rig.database.setLedgerBlacklist(blacklistSpork(null));
		assertThat(rig.authority.authorizes(claim), is(false));

		rig.database.setLedgerBlacklist(withData(new LedgerBlacklist(), null));
		assertThat(rig.authority.authorizes(claim), is(false));

		rig.database.setLedgerBlacklist(withData(new LedgerBlacklist(), new LedgerFee.SporkData()));
		assertThat(rig.authority.authorizes(claim), is(false));
	}

	@Example
	public void shouldAnswerNoToATransferWhenTheFeeIsNothingUsefulOrOutOfRange() {
		final Rig rig = Rig.open();

		rig.database.setLedgerFee(feeSpork(null));
		assertThat(rig.authority.authorizes(transferWithFee(1)), is(false));

		rig.database.setLedgerFee(feeSpork(BigDecimal.valueOf(-1)));
		assertThat(rig.authority.authorizes(transferWithFee(1)), is(false));

		rig.database.setLedgerFee(feeSpork(new BigDecimal("1E+1000000000")));
		assertThat(rig.authority.authorizes(transferWithFee(Long.MAX_VALUE)), is(false));

		rig.database.setLedgerFee(withData(new LedgerFee(), null));
		assertThat(rig.authority.authorizes(transferWithFee(1)), is(false));
	}

	@Example
	public void shouldAnswerNoToAMintWhenTheSupplySporkHoldsNothingUsefulOrIsMalformed() {
		final Rig rig = Rig.open();

		rig.database.setMintSupply(supplySpork(null));
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));

		rig.database.setMintSupply(withData(new MintSupply(), null));
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));

		rig.database.setMintSupply(withData(new MintSupply(), new LedgerFee.SporkData()));
		assertThat(rig.authority.due(FAR_FUTURE), is(empty()));
	}

	@Example
	public void shouldListNoMintsWhenTheCommittedSupplyCannotBeRead() {
		final Rig rig = Rig.open();

		rig.unsupplied = new IllegalStateException("The ledger is closed");

		assertThat(rig.authority.due(FAR_FUTURE), is(empty()));
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(true));
		assertThat(rig.authority.authorizes(claimOfKey(3)), is(true));
		assertThat(rig.authority.authorizes(transferWithFee(1)), is(true));
	}

	@Example
	public void shouldJudgeAMintBySupplyItIsToldAndNotByTheCommittedOne() {
		final Rig rig = Rig.open();
		final long room = 100 * UNITS_PER_COIN - UNITS_OF_AN_ENTRY;

		rig.cap("100");
		rig.supply = 100 * UNITS_PER_COIN;
		assertThat(rig.real.authorizes(theMintOfAnEntry(), FAR_FUTURE, 0, 0), is(true));

		rig.supply = 0;
		assertThat(rig.real.authorizes(theMintOfAnEntry(), FAR_FUTURE, room, 0), is(true));
		assertThat(rig.real.authorizes(theMintOfAnEntry(), FAR_FUTURE, room + 1, 0), is(false));
		assertThat(rig.real.authorizes(theMintOfAnEntry(), FAR_FUTURE, Long.MAX_VALUE, 0), is(false));
	}

	@Example
	public void shouldCountTheMintsBeforeItInTheSameBlockAgainstTheMaximumSupply() {
		final Rig rig = Rig.open();
		final Mint first = accountMint(2, 5, UNITS_OF_AN_ENTRY);
		final Mint second = accountMint(3, 5, UNITS_OF_AN_ENTRY);
		final Map<Location, BigDecimal> entries = new LinkedHashMap<>();

		entries.put(accountEntry(2, 5), BigDecimal.TEN);
		entries.put(accountEntry(3, 5), BigDecimal.TEN);
		rig.store(entries);
		rig.cap("15");

		assertThat(rig.real.authorizes(first, FAR_FUTURE, 0, 0), is(true));
		assertThat(rig.real.authorizes(second, FAR_FUTURE, 0, 0), is(true));
		assertThat(rig.real.authorizes(second, FAR_FUTURE, first.amount(), 0), is(false));
	}

	@Example
	public void shouldGrantAnAddressTheSumOfAllItsEntriesInTheSpork() {
		final Rig rig = Rig.open();
		final Map<Location, BigDecimal> entries = new LinkedHashMap<>();

		entries.put(accountEntry(2, 5), BigDecimal.ONE);
		entries.put(accountEntry(2, 6), new BigDecimal("2"));
		entries.put(accountEntry(2, 7), new BigDecimal("3"));
		rig.store(entries);

		assertThat(rig.real.authorizes(accountMint(2, 5, UNITS_PER_COIN), FAR_FUTURE, 0, 5 * UNITS_PER_COIN),
			is(true));
		assertThat(rig.real.authorizes(accountMint(2, 5, UNITS_PER_COIN), FAR_FUTURE, 0, 5 * UNITS_PER_COIN + 1),
			is(false));
		assertThat(rig.real.authorizes(accountMint(2, 7, 3 * UNITS_PER_COIN), FAR_FUTURE, 0, 3 * UNITS_PER_COIN),
			is(true));
		assertThat(rig.real.authorizes(accountMint(2, 7, 3 * UNITS_PER_COIN), FAR_FUTURE, 0,
			3 * UNITS_PER_COIN + 1), is(false));
	}

	@Example
	public void shouldRefuseTheMintOfAReplacedEntryOnceTheAddressHasWhatTheSporkGrants() {
		final Rig rig = Rig.open();
		final long hundred = 100 * UNITS_PER_COIN;

		rig.store(grant(accountEntry(2, 6), "100"));

		assertThat(rig.real.authorizes(accountMint(2, 6, hundred), FAR_FUTURE, 0, 0), is(true));
		assertThat(rig.real.authorizes(accountMint(2, 6, hundred), FAR_FUTURE, 0, hundred), is(false));
		assertThat(rig.real.authorizes(accountMint(2, 6, hundred), FAR_FUTURE, 0, 1), is(false));

		rig.store(Map.of(accountEntry(2, 5), new BigDecimal("100"), accountEntry(2, 6), new BigDecimal("50")));

		assertThat(rig.real.authorizes(accountMint(2, 6, 50 * UNITS_PER_COIN), FAR_FUTURE, 0, hundred), is(true));
		assertThat(rig.real.authorizes(accountMint(2, 6, 50 * UNITS_PER_COIN), FAR_FUTURE, 0, hundred + 1),
			is(false));
	}

	@Example
	public void shouldRefuseEveryMintOfAnAddressThatHasMoreThanTheSporkNowGrants() {
		final Rig rig = Rig.open();

		rig.store(grant(accountEntry(2, 6), "10"));

		assertThat(rig.real.authorizes(accountMint(2, 6, 1), FAR_FUTURE, 0, 11 * UNITS_PER_COIN), is(false));
		assertThat(rig.real.authorizes(accountMint(2, 6, 1), FAR_FUTURE, 0, Long.MAX_VALUE), is(false));
	}

	@Example
	public void shouldGrantAnOldAddressOnlyWhatItsEntriesAboveTheTipMint() {
		final Rig rig = Rig.open();
		final Map<Location, BigDecimal> entries = new LinkedHashMap<>();

		entries.put(oldEntry(7, (int) TIP_HEIGHT), new BigDecimal("40"));
		entries.put(oldEntry(7, (int) TIP_HEIGHT - 5), new BigDecimal("30"));
		entries.put(oldEntry(7, (int) TIP_HEIGHT + 5), new BigDecimal("2"));
		entries.put(oldEntry(7, (int) TIP_HEIGHT + 6), new BigDecimal("1"));
		rig.store(entries);

		assertThat(rig.real.authorizes(oldMint(7, TIP_HEIGHT + 5, 2 * UNITS_PER_COIN), FAR_FUTURE, 0,
			UNITS_PER_COIN), is(true));
		assertThat(rig.real.authorizes(oldMint(7, TIP_HEIGHT + 5, 2 * UNITS_PER_COIN), FAR_FUTURE, 0,
			UNITS_PER_COIN + 1), is(false));
		assertThat(rig.real.authorizes(oldMint(7, TIP_HEIGHT, 40 * UNITS_PER_COIN), FAR_FUTURE, 0, 0), is(false));
		assertThat(rig.real.authorizes(oldMint(7, TIP_HEIGHT - 5, 30 * UNITS_PER_COIN), FAR_FUTURE, 0, 0),
			is(false));
	}

	@Example
	public void shouldJudgeAnAddressByItsOwnGrantAndNotByTheGrantsOfOthers() {
		final Rig rig = Rig.open();
		final Map<Location, BigDecimal> entries = new LinkedHashMap<>();

		entries.put(accountEntry(2, 5), new BigDecimal("5"));
		entries.put(accountEntry(3, 5), BigDecimal.TEN);
		rig.store(entries);

		assertThat(rig.real.authorizes(accountMint(3, 5, UNITS_OF_AN_ENTRY), FAR_FUTURE, 0, 0), is(true));
		assertThat(rig.real.authorizes(accountMint(3, 5, UNITS_OF_AN_ENTRY), FAR_FUTURE, 0, 1), is(false));
		assertThat(rig.real.authorizes(accountMint(2, 5, 5 * UNITS_PER_COIN), FAR_FUTURE, 0, 1), is(false));
		assertThat(rig.real.authorizes(accountMint(2, 5, 5 * UNITS_PER_COIN), FAR_FUTURE, 0, 0), is(true));
	}

	@Example
	public void shouldCountAGrantThatALongCannotHoldAsTheLargestAndStillAnswer() {
		final Rig rig = Rig.open();
		final Map<Location, BigDecimal> entries = new LinkedHashMap<>();
		final String largest = "92233720368.54775807";

		entries.put(accountEntry(2, 5), new BigDecimal(largest));
		entries.put(accountEntry(2, 6), new BigDecimal(largest));
		rig.store(entries);
		rig.cap(largest);

		assertThat(rig.authority.due(FAR_FUTURE), hasSize(2));
		assertThat(rig.real.authorizes(accountMint(2, 5, Long.MAX_VALUE), FAR_FUTURE, 0, 0), is(true));
		assertThat(rig.real.authorizes(accountMint(2, 6, Long.MAX_VALUE), FAR_FUTURE, 0, 1), is(false));
	}

	@Example
	public void shouldNotListAMintThatPassesWhatIsAlreadyMintedToItsAddress() {
		final Rig rig = Rig.open();
		final Mint replaced = accountMint(2, 6, 100 * UNITS_PER_COIN);
		final Mint other = accountMint(3, 6, UNITS_OF_AN_ENTRY);
		final Map<Location, BigDecimal> entries = new LinkedHashMap<>();

		entries.put(accountEntry(2, 6), new BigDecimal("100"));
		entries.put(accountEntry(3, 6), BigDecimal.TEN);
		rig.store(entries);

		assertThat(rig.authority.due(FAR_FUTURE), containsInAnyOrder(replaced, other));

		rig.minted.put(replaced.recipient(), 1L);

		assertThat(rig.authority.due(FAR_FUTURE), contains(other));
		assertThat(rig.authority.authorizes(replaced, FAR_FUTURE), is(false));

		rig.minted.put(replaced.recipient(), 100 * UNITS_PER_COIN);
		rig.minted.put(other.recipient(), UNITS_OF_AN_ENTRY);

		assertThat(rig.authority.due(FAR_FUTURE), is(empty()));
	}

	@Example
	public void shouldListNoMintsWhenTheMintedTotalsCannotBeRead() {
		final Rig rig = Rig.open();

		rig.unminted = new IllegalStateException("The ledger is closed");

		assertThat(rig.authority.due(FAR_FUTURE), is(empty()));
	}

	@Example
	public void shouldListEachDueMintThatFitsAloneAndLeaveTheSumOfABlockToTheLedger() {
		final Rig rig = Rig.open();
		final Map<Location, BigDecimal> entries = new LinkedHashMap<>();

		entries.put(accountEntry(2, 5), BigDecimal.TEN);
		entries.put(accountEntry(3, 5), BigDecimal.TEN);
		rig.store(entries);
		rig.cap("15");

		assertThat(rig.authority.due(FAR_FUTURE), containsInAnyOrder(accountMint(2, 5, UNITS_OF_AN_ENTRY),
			accountMint(3, 5, UNITS_OF_AN_ENTRY)));
	}

	@Example
	@SneakyThrows
	public void shouldAnswerFromWholeDatabasesWhileTheyAreSwappedBetweenCalls() {
		final Rig rig = Rig.open();
		final SporkDatabase listing = rig.database;
		final SporkDatabase empty = SporkDatabase.builder().build();
		final SporkDatabase broken = SporkDatabase.builder().mintStorage(withData(new MintStorage(), null)).build();
		final List<Mint> listed = List.of(theMintOfAnEntry());
		final CountDownLatch done = new CountDownLatch(1);
		final List<Throwable> failures = new CopyOnWriteArrayList<>();
		final Thread writer = new Thread(() -> {
			final SporkDatabase[] databases = { listing, empty, broken, null };

			for (int turn = 0; !Thread.currentThread().isInterrupted() && done.getCount() > 0; turn++) {
				rig.database = databases[turn % databases.length];
				listing.setLedgerFee(feeSpork(BigDecimal.valueOf(turn % 3, 8)));
				listing.setLedgerBlacklist(turn % 2 == 0 ? null : blacklistSpork(Set.of(hashOf(claimOfKey(3)))));
			}
		});

		writer.start();

		final List<Thread> readers = IntStream.range(0, 3).mapToObj(index -> new Thread(() -> {
			try {
				for (int round = 0; round < 3_000; round++) {
					final List<Mint> due = rig.authority.due(FAR_FUTURE);

					assertThat(due.isEmpty() || due.equals(listed), is(true));
					rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE);
					rig.authority.authorizes(claimOfKey(3));
					rig.authority.authorizes(transferWithFee(1));
				}
			} catch (Throwable thrown) {
				failures.add(thrown);
			}
		})).collect(Collectors.toList());

		readers.forEach(Thread::start);

		for (final Thread reader : readers) {
			reader.join();
		}

		done.countDown();
		writer.join();
		assertThat(failures, is(empty()));
	}

	@Example
	public void shouldDecodeTheEntriesOnceForAsLongAsTheSporkIsTheSameObject() {
		final Rig rig = Rig.open();

		for (int round = 0; round < 200; round++) {
			assertThat(rig.authority.due(DUE_OF_AN_ENTRY + round), equalTo(List.of(theMintOfAnEntry())));
			assertThat(rig.authority.authorizes(theMintOfAnEntry(), DUE_OF_AN_ENTRY + round), is(true));
		}

		assertThat(rig.real.decodings(), is(1));
	}

	@Example
	public void shouldDecodeAgainWhenTheSporkIsReplacedByAnotherObjectAndAnswerTheSame() {
		final Rig rig = Rig.open();

		rig.authority.due(DUE_OF_AN_ENTRY);
		rig.store(grant(accountEntry(1, SECONDS_OF_AN_ENTRY), "" + COINS_OF_AN_ENTRY));

		assertThat(rig.authority.due(DUE_OF_AN_ENTRY), equalTo(List.of(theMintOfAnEntry())));
		assertThat(rig.real.decodings(), is(2));
	}

	@Example
	public void shouldFollowAReplacedSporkToItsOtherEntriesAndForgetTheOnesItHad() {
		final Rig rig = Rig.open();

		assertThat(rig.authority.due(FAR_FUTURE), equalTo(List.of(theMintOfAnEntry())));

		rig.store(grant(accountEntry(2, SECONDS_OF_AN_ENTRY), "5"));

		assertThat(rig.authority.due(FAR_FUTURE), equalTo(List.of(accountMint(2, SECONDS_OF_AN_ENTRY,
			5 * UNITS_PER_COIN))));
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));
		assertThat(rig.authority.authorizes(accountMint(2, SECONDS_OF_AN_ENTRY, 5 * UNITS_PER_COIN), FAR_FUTURE),
			is(true));
	}

	@Example
	public void shouldDecodeNothingWhileTheLagHoldsTheSporkBackAndKeepWhatItHadAfterwards() {
		final Rig rig = Rig.open();

		rig.authority.due(FAR_FUTURE);
		rig.behind.add(Type.MINT_STORAGE);

		assertThat(rig.authority.due(FAR_FUTURE), is(empty()));
		assertThat(rig.authority.authorizes(theMintOfAnEntry(), FAR_FUTURE), is(false));

		rig.behind.clear();

		assertThat(rig.authority.due(FAR_FUTURE), equalTo(List.of(theMintOfAnEntry())));
		assertThat(rig.real.decodings(), is(1));
	}

	@Example
	public void shouldTakeTheDueTimeOfAnEntryFromTheScheduleOfTheCachedDecoding() {
		final Rig rig = Rig.open();

		rig.store(grant(oldEntry(7, (int) TIP_HEIGHT + 3), "1"));

		final long due = SCHEDULE.dueTime(TIP_HEIGHT + 3);

		assertThat(rig.authority.due(due - 1), is(empty()));
		assertThat(rig.authority.due(due), hasSize(1));
		assertThat(rig.authority.due(due - 1), is(empty()));
		assertThat(rig.real.decodings(), is(1));
	}
}
