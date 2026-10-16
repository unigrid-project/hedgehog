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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Address;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.LedgerAuthority;
import org.unigrid.hedgehog.ledger.Mint;
import org.unigrid.hedgehog.ledger.MintSchedule;
import org.unigrid.hedgehog.ledger.Transfer;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.bootstrap.MintEntry;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;

/* What this node demands of the ledger, read from the sporks. It is policy and so may differ between nodes: the
   ledger asks it only of what is not certified, and a block that most validators signed stands whatever it says.

   An entry of the mint spork is a mint whose address string is written in one of two forms, read by MintEntry:
   a legacy base58 address, whose owner claims the amount later, or an Ed25519 account of the ledger in 64
   lower-case hex characters. Anything else is skipped and logged once. A mint is made once, by its reference.

   A node that does not know a policy answers no, which only delays a block: a spork that is not set, one that a
   newer spork is known to replace (SporkLag), or any trouble reading. A spork can
   change at any time, so each call reads the database once, and a spork is read only after the lag has been asked
   about its type. No exception leaves, as the ledger would only see a refusal.

   One thing is kept between calls: the entries of the mint spork, decoded (base58, the hash of the reference), as
   the ledger asks every second and decoding them all each time would cost what the number of entries is. It is one
   slot, kept for the spork object it was made from and found again only by that same object. That is safe because a
   spork is replaced whole when a newer one is accepted (SporkDatabase.set) and never changed in place; a spork that
   is changed in place is not a supported case and would be read as it was. The schedule does not change for an
   authority, so the due time of an entry is part of what is kept.

   The maximum supply holds for a block as a whole: a mint is judged on the supply of the state it would be made
   on, which the ledger passes in and which holds the mints before it in the same block. */
@Slf4j
@RequiredArgsConstructor
public final class SporkLedgerAuthority implements LedgerAuthority {
	private static final HexFormat HEX = HexFormat.of();
	private static final Predicate<String> ANYWHERE = address -> true;
	private static final int MAX_REPORTED = 256;
	private static final int SHOWN_CHARACTERS = 80;
	private static final BigInteger LONGEST = BigInteger.valueOf(Long.MAX_VALUE);

	@NonNull private final Supplier<SporkDatabase> sporks;
	@NonNull private final SporkLag lag;
	@NonNull private final MintSchedule schedule;
	/* Only for listing what is due: a mint asked about is judged on the supply it is told */
	@NonNull private final LongSupplier committedSupply;
	private final Set<String> reported = ConcurrentHashMap.newKeySet();
	private final AtomicInteger decodings = new AtomicInteger();
	private volatile Decoded decoded = new Decoded(null, List.of());

	/* An entry of the mint spork that can be made some time: where it is written, when it is due and the mint */
	private record Prepared(String address, long dueMillis, Mint mint) {
	}

	/* The entries that can be made, in the order of their references, and the spork object they were made from */
	private record Decoded(GridSpork spork, List<Prepared> entries) {
	}

	@Override
	public List<Mint> due(long time) {
		return guarded("listing the due mints", List.of(), () -> dueMints(time));
	}

	@Override
	public boolean authorizes(Mint mint, long time, long supply) {
		return guarded("authorizing a mint", false, () -> mintAllowed(mint, time, supply));
	}

	@Override
	public boolean authorizes(Claim claim) {
		return guarded("authorizing a claim", false, () -> claimAllowed(claim));
	}

	@Override
	public boolean authorizes(Transfer transfer) {
		return guarded("authorizing a transfer", false, () -> transferAllowed(transfer));
	}

	/* Every due entry is listed, as the ledger leaves out the mints already made and cuts the block itself: cutting
	   the list here would starve the entries after the first block's worth once those are made. Each must fit
	   alone under the maximum supply on top of what is committed; the ledger, which asks again with the supply
	   after the mints before it in the block, keeps the ones that fit together. A mint that never fits is left out
	   so that the ledger does not see work in it every second. */
	private List<Mint> dueMints(long time) {
		final SporkDatabase database = sporks.get();

		if (Objects.isNull(database)) {
			return List.of();
		}

		final Optional<Long> room = room(database, committedSupply.getAsLong());

		return room.stream().flatMap(limit -> dueFrom(database, time, ANYWHERE)
			.filter(mint -> mint.amount() <= limit)).toList();
	}

	private boolean mintAllowed(Mint mint, long time, long supply) {
		final SporkDatabase database = sporks.get();

		return Objects.nonNull(database)
			&& room(database, supply).filter(limit -> mint.amount() <= limit).isPresent()
			&& dueFrom(database, time, at(wifOf(mint.recipient()))).anyMatch(mint::equals);
	}

	private boolean claimAllowed(Claim claim) {
		final SporkDatabase database = sporks.get();

		return Objects.nonNull(database) && known(database, Type.LEDGER_BLACKLIST)
			.map(spork -> spork.<LedgerBlacklist.SporkData>getData())
			.map(LedgerBlacklist.SporkData::getLegacyAddresses)
			.filter(listed -> !listed.contains(HEX.formatHex(claim.address().bytes()))).isPresent();
	}

	private boolean transferAllowed(Transfer transfer) {
		final SporkDatabase database = sporks.get();

		return Objects.nonNull(database) && known(database, Type.LEDGER_FEE)
			.map(spork -> spork.<LedgerFee.SporkData>getData()).map(LedgerFee.SporkData::units)
			.filter(minimum -> transfer.fee() >= minimum).isPresent();
	}

	/* The spork, or nothing when it is not set or a newer one is known. The lag is asked first: a spork stored
	   in between is then read in its newer form, which the lag has no reason to hold back. */
	private Optional<GridSpork> known(SporkDatabase database, Type type) {
		return lag.isBehind(type, database) ? Optional.empty() : Optional.ofNullable(database.get(type));
	}

	/* How many units more may be made on this supply before the maximum supply is passed, not known while the
	   spork is not. The maximum is cut at eight decimals, so it is never above what the spork says, and a maximum
	   that a long cannot count is more than there can be. */
	private Optional<Long> room(SporkDatabase database, long supply) {
		return known(database, Type.MINT_SUPPLY).map(spork -> spork.<MintSupply.SporkData>getData())
			.map(MintSupply.SporkData::getMaxSupply).map(SporkLedgerAuthority::unitsOf)
			.map(maximum -> Math.max(0, maximum - supply));
	}

	private static long unitsOf(BigDecimal coins) {
		return MintEntry.unitsOf(coins).min(LONGEST).longValueExact();
	}

	/* In the order of the references, which the decoding sorted them in */
	private Stream<Mint> dueFrom(SporkDatabase database, long time, Predicate<String> wanted) {
		return known(database, Type.MINT_STORAGE).map(this::decodedFrom).stream().flatMap(List::stream)
			.filter(entry -> entry.dueMillis() <= time && wanted.test(entry.address())).map(Prepared::mint);
	}

	private List<Prepared> decodedFrom(GridSpork spork) {
		final Decoded held = decoded;

		if (held.spork() == spork) {
			return held.entries();
		}

		final List<Prepared> fresh = decode(Optional.ofNullable(spork.<MintStorage.SporkData>getData())
			.map(MintStorage.SporkData::getMints));

		decoded = new Decoded(spork, fresh);
		return fresh;
	}

	private List<Prepared> decode(Optional<Map<Location, BigDecimal>> mints) {
		decodings.incrementAndGet();

		return mints.stream().flatMap(entries -> entries.entrySet().stream())
			.filter(mint -> Objects.nonNull(mint.getKey()))
			.flatMap(mint -> preparedOf(mint.getKey(), mint.getValue()).stream())
			.sorted(Comparator.comparing(prepared -> prepared.mint().reference())).toList();
	}

	private Optional<Prepared> preparedOf(Location location, BigDecimal amount) {
		final Optional<MintEntry> entry = entryOf(location, amount);
		final OptionalLong due = entry.map(held -> held.dueMillis(schedule)).orElse(OptionalLong.empty());

		return due.isEmpty() ? Optional.empty() : mintOf(entry.get())
			.map(mint -> new Prepared(location.getAddress().getWif(), due.getAsLong(), mint));
	}

	/* For the tests of this package: how many times the entries of the mint spork were decoded */
	int decodings() {
		return decodings.get();
	}

	private Optional<MintEntry> entryOf(Location location, BigDecimal amount) {
		final Optional<MintEntry> entry = MintEntry.of(location, amount);

		if (entry.isEmpty()) {
			reportOnce("Skipped the mint entry of " + shown(location) + ": it has no amount, or its address is "
				+ "of neither kind");
		}

		return entry;
	}

	private Optional<Mint> mintOf(MintEntry entry) {
		final OptionalLong units = entry.units();

		if (units.isEmpty()) {
			reportOnce("Skipped the mint entry of " + entry.recipient() + " at " + entry.number() + ": "
				+ StringUtils.abbreviate(String.valueOf(entry.amount()), SHOWN_CHARACTERS)
				+ " is not an amount of whole units of 8 decimals");

			return Optional.empty();
		}

		return Optional.of(new Mint(entry.recipient(), units.getAsLong(), entry.reference()));
	}

	/* The address string that an entry for this recipient is written with, so that an entry is looked for by its
	   string and only the ones that can match are decoded */
	private static String wifOf(Address recipient) {
		return recipient instanceof AccountKey ? HEX.formatHex(recipient.bytes())
			: LegacyAddress.encode(recipient.bytes());
	}

	private static Predicate<String> at(String wif) {
		return wif::equals;
	}

	private static String shown(Location location) {
		return StringUtils.abbreviate(Optional.ofNullable(location.getAddress()).map(address -> address.getWif())
			.orElse(null), SHOWN_CHARACTERS);
	}

	private <T> T guarded(String task, T refusal, Supplier<T> decision) {
		try {
			return decision.get();
		} catch (RuntimeException ex) {
			reportOnce("Answered no while " + task + ": " + ex.getClass().getName(), ex);
			return refusal;
		}
	}

	/* A cause is told once, as the ledger asks again every second and a spork that stays as it is would fill the log */
	private void reportOnce(String cause) {
		reportOnce(cause, null);
	}

	private void reportOnce(String cause, Throwable error) {
		if (reported.size() < MAX_REPORTED && reported.add(cause)) {
			log.atDebug().setCause(error).log(cause);
		}
	}
}
