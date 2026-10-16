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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist.SporkData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class LedgerBlacklistTest {
	private static final String HASH_PATTERN = "[0-9a-f]{40}";

	public static Arbitrary<String> hashes() {
		return Arbitraries.strings().withChars("0123456789abcdef").ofLength(2 * SporkData.HASH_BYTES);
	}

	public static Arbitrary<Set<String>> cappedSets() {
		return hashes().set().ofMaxSize(300);
	}

	public static Set<String> distinctHashes(int count) {
		return IntStream.range(0, count).mapToObj(index -> "%040x".formatted(index)).collect(Collectors.toSet());
	}

	public static Set<String> plus(Set<String> addresses, String entry) {
		final Set<String> extended = new HashSet<>(addresses);

		extended.add(entry);
		return extended;
	}

	public static SporkData with(Set<String> addresses) {
		final SporkData data = new SporkData();

		data.setLegacyAddresses(addresses);
		return data;
	}

	public static boolean accepts(SporkData data) {
		try {
			data.validate();
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	@Provide
	public Arbitrary<Set<String>> sets() {
		return cappedSets();
	}

	@Provide
	public Arbitrary<String> notHashes() {
		return Arbitraries.oneOf(Arbitraries.strings().ofMaxLength(80), hashes().map(String::toUpperCase),
			hashes().map(hash -> hash.substring(1)), hashes().map(hash -> hash + "0"),
			hashes().map(hash -> "g" + hash.substring(1))
		).filter(entry -> !entry.matches(HASH_PATTERN));
	}

	@Example
	public void startsAsAnEmptyListThatIsValid() {
		final SporkData data = new LedgerBlacklist().getData();

		data.validate();
		assertThat(data.getLegacyAddresses(), is(empty()));
		assertThat(data.empty(), equalTo(data));
	}

	@Example
	public void isRegisteredAsAGovernedSporkType() {
		final GridSpork spork = GridSpork.create(GridSpork.Type.LEDGER_BLACKLIST);
		final short governed = GridSpork.Flag.GOVERNED.getValue();

		assertThat(GridSpork.Type.get((short) 1040), equalTo(GridSpork.Type.LEDGER_BLACKLIST));
		assertThat(spork, instanceOf(LedgerBlacklist.class));
		assertThat(spork.getType(), equalTo(GridSpork.Type.LEDGER_BLACKLIST));
		assertThat(spork.getFlags() & governed, equalTo((int) governed));
	}

	private static List<GridSpork.Type> sporkTypes() {
		return Arrays.stream(GridSpork.Type.values()).filter(type -> type != GridSpork.Type.UNDEFINED).toList();
	}

	@Example
	public void givesEverySporkTypeItsOwnNumberAndFactory() {
		final List<GridSpork.Type> types = sporkTypes();

		assertThat(types.stream().map(GridSpork.Type::getValue).distinct().count(), equalTo((long) types.size()));

		types.forEach(type -> {
			assertThat(GridSpork.Type.get(type.getValue()), equalTo(type));
			assertThat(GridSpork.create(type).getType(), equalTo(type));
		});

		assertThat(GridSpork.Type.get((short) 0), equalTo(GridSpork.Type.UNDEFINED));
		assertThat(GridSpork.Type.get((short) 1041), equalTo(GridSpork.Type.UNDEFINED));
		assertThrows(IllegalArgumentException.class, () -> GridSpork.create(GridSpork.Type.UNDEFINED));
	}

	@Example
	public void isKeptByTheSporkDatabaseInASlotOfItsOwn() {
		sporkTypes().forEach(type -> {
			final SporkDatabase database = SporkDatabase.builder().build();
			final GridSpork spork = GridSpork.create(type);

			database.set(spork);
			assertThat(type.name(), database.get(type), sameInstance(spork));

			sporkTypes().stream().filter(other -> other != type)
				.forEach(other -> assertThat(other.name(), database.get(other), nullValue()));
		});

		final SporkDatabase database = SporkDatabase.builder().build();
		final LedgerBlacklist blacklist = new LedgerBlacklist();
		final GridSpork undefined = new GridSpork();

		undefined.setType((short) 0);
		database.set(blacklist);
		assertThat(database.getLedgerBlacklist(), sameInstance(blacklist));
		assertThrows(IllegalArgumentException.class, () -> database.get(GridSpork.Type.UNDEFINED));
		assertThrows(IllegalArgumentException.class, () -> database.set(undefined));
	}

	@Property
	public void acceptsAnySetOfHashes(@ForAll("sets") Set<String> addresses) {
		assertThat(accepts(with(addresses)), is(true));
	}

	@Example
	public void acceptsTheCapAndRefusesOneMore() {
		assertThat(accepts(with(distinctHashes(SporkData.MAX_ENTRIES))), is(true));
		assertThat(accepts(with(distinctHashes(SporkData.MAX_ENTRIES + 1))), is(false));
	}

	@Property
	public void refusesAnEntryThatIsNotExactlyFortyLowerCaseHexCharacters(@ForAll("sets") Set<String> addresses,
		@ForAll("notHashes") String entry) {

		assertThat(accepts(with(plus(addresses, entry))), is(false));
	}

	@Example
	public void refusesAMissingListAndAMissingEntry() {
		final Set<String> withNull = new HashSet<>(List.of("0".repeat(40)));

		withNull.add(null);
		assertThat(accepts(with(null)), is(false));
		assertThat(accepts(with(withNull)), is(false));
	}

	@SneakyThrows
	@Example
	public void isFoundByDeductionFromItsOwnFieldAlone() {
		final ObjectMapper mapper = new ObjectMapper();
		final String empty = "{\"legacyAddresses\":[]}";
		final String listed = "{\"legacyAddresses\":[\"" + "a".repeat(40) + "\"]}";

		assertThat(mapper.readValue(empty, ChunkData.class), equalTo(with(Set.of())));
		assertThat(mapper.readValue(listed, ChunkData.class), equalTo(with(Set.of("a".repeat(40)))));

		final JsonNode written = mapper.valueToTree(with(Set.of("b".repeat(40))));

		assertThat(written.fieldNames().next(), equalTo("legacyAddresses"));
		assertThat(written.size(), is(1));
	}

	@SneakyThrows
	@Example
	public void leavesTheDeductionOfTheOtherSporkDataAlone() {
		final Map<String, Class<? extends ChunkData>> others = Map.of(
			"{\"maxSupply\":1}", MintSupply.SporkData.class,
			"{\"mints\":{}}", MintStorage.SporkData.class,
			"{\"vestingAddresses\":{}}", VestingStorage.SporkData.class,
			"{\"maxBytesPerNode\":1}", StorageSpork.SporkData.class
		);

		for (Map.Entry<String, Class<? extends ChunkData>> other : others.entrySet()) {
			assertThat(new ObjectMapper().readValue(other.getKey(), ChunkData.class).getClass(),
				equalTo(other.getValue()));
		}
	}
}
