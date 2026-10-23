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

package org.unigrid.hedgehog.model.spork.content;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.Validate;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.bootstrap.MintEntry;
import org.unigrid.hedgehog.model.spork.ChunkData;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.SporkAmount;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.spork.VestingStorage;
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData.Vesting;

/* What a spork may hold beyond what its wire form bounds, by its type. The command line checks a spork by these rules
   before it signs it, and a node checks one it is handed before it holds it or sends it on, so a change that no node
   can act on is refused when it is made, not found out by every node when it is due. Of a spork with entries, only
   the entries that the new version adds or changes are judged: those of the stored version were taken when it was
   made, under the rules of that time, and are no part of the change. The answer is an IllegalArgumentException that
   says what is wrong. The mints are judged as the ledger reads them, which the snapshot model knows, so the rules
   sit above both the sporks and that model. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SporkContent {
	private static final String AMOUNT_BOUNDS = "at most " + SporkAmount.MAX_DECIMALS + " decimals and "
		+ SporkAmount.MAX_INTEGER_DIGITS + " digits before the point";

	/* A switch over every type outgrows the complexity limit of a method, so each type lists its check here */
	private static final Map<Type, BiConsumer<ChunkData, ChunkData>> CHECKS = Map.of(
		Type.MINT_STORAGE, SporkContent::mintStorage,
		Type.MINT_SUPPLY, (data, stored) -> mintSupply((MintSupply.SporkData) data),
		Type.VESTING_STORAGE, SporkContent::vestingStorage,
		Type.STORAGE, (data, stored) -> ((StorageSpork.SporkData) data).validate(),
		Type.LEDGER_BLACKLIST, (data, stored) -> ((LedgerBlacklist.SporkData) data).validate(),
		Type.LEDGER_FEE, (data, stored) -> ((LedgerFee.SporkData) data).validate()
	);

	public static void validate(GridSpork spork, GridSpork stored) {
		Validate.isTrue(Objects.nonNull(spork.getData()), "A spork of type %s holds no data", spork.getType());
		CHECKS.getOrDefault(spork.getType(), SporkContent::unchecked).accept(spork.getData(),
			Objects.isNull(stored) ? null : stored.getData());
	}

	/* The public key of the statistics is checked by those who use it */
	private static void unchecked(ChunkData data, ChunkData stored) {
		/* Empty on purpose */
	}

	/* The entries that are new to the stored data or differ from what it holds */
	private static <K, V, D> Stream<Map.Entry<K, V>> changed(Map<K, V> entries, D stored, Function<D, Map<K, V>> of) {
		final Map<K, V> before = Objects.isNull(stored) ? Map.of() : of.apply(stored);

		return entries.entrySet().stream()
			.filter(entry -> !Objects.equals(entry.getValue(), before.get(entry.getKey())));
	}

	private static void mintStorage(ChunkData data, ChunkData stored) {
		final MintStorage.SporkData mints = (MintStorage.SporkData) data;

		mints.validate();
		changed(mints.getMints(), (MintStorage.SporkData) stored, MintStorage.SporkData::getMints)
			.forEach(mint -> requireMintable(mint.getKey(), mint.getValue()));
	}

	/* The amount is judged first, by the bounds of the wire: an exponent could write out as gigabytes */
	private static void requireMintable(Location location, BigDecimal amount) {
		final String wif = Objects.isNull(location.getAddress()) ? null : location.getAddress().getWif();
		final String mint = "The mint of " + wif + " at " + Integer.toUnsignedString(location.getHeight()) + ": ";

		Validate.isTrue(Objects.nonNull(amount) && SporkAmount.isWithinBounds(amount), "%san amount has %s", mint,
			AMOUNT_BOUNDS);

		final MintEntry entry = MintEntry.of(location, amount).orElseThrow(() -> new IllegalArgumentException(mint
			+ "the address is neither an old-style Unigrid address nor a ledger account of 64 lower-case "
			+ "hexadecimal characters"));

		try {
			MintEntry.heightOf(wif, entry.number());
		} catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException(mint + ex.getMessage(), ex);
		}

		Validate.isTrue(entry.units().isPresent(), "%sthe amount is below one unit (0.00000001) or above what the "
			+ "ledger counts (92233720368.54775807)", mint);
	}

	private static void mintSupply(MintSupply.SporkData data) {
		final BigDecimal maxSupply = data.getMaxSupply();

		Validate.isTrue(Objects.nonNull(maxSupply) && maxSupply.signum() >= 0
			&& SporkAmount.isWithinBounds(maxSupply), "A maximum supply is not negative and has %s",
			AMOUNT_BOUNDS);
	}

	private static void vestingStorage(ChunkData data, ChunkData stored) {
		final VestingStorage.SporkData vestings = (VestingStorage.SporkData) data;

		vestings.validate();
		changed(vestings.getVestingAddresses(), (VestingStorage.SporkData) stored,
			VestingStorage.SporkData::getVestingAddresses).forEach(entry -> requireVestable(entry.getKey(),
			entry.getValue()));
	}

	/* The start and the duration have no empty form on the wire, so a vesting without them cannot be signed */
	private static void requireVestable(Address address, Vesting vesting) {
		final String of = "The vesting of " + address.getWif() + " ";

		Validate.isTrue(Objects.nonNull(vesting.getAmount()) && SporkAmount.isWithinBounds(vesting.getAmount()),
			"%shas an amount of %s", of, AMOUNT_BOUNDS);
		Validate.isTrue(Objects.nonNull(vesting.getStart()), "%shas a start", of);
		Validate.isTrue(Objects.nonNull(vesting.getDuration()), "%shas a duration", of);
	}
}
