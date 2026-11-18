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
import java.util.HashSet;
import java.util.List;
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
import org.unigrid.hedgehog.model.spork.LegacyWhiteList.SporkData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

/** The legacy whitelist as a spork: its type, its place in the database, and the form of its list. */
public class LegacyWhiteListTest {
	private static final String HASH_PATTERN = "[0-9a-f]{40}";
	private static final short WIRE_VALUE = 1070;

	/** Hash160s as the list holds them, in lower-case hex. */
	public static Arbitrary<String> hashes() {
		return Arbitraries.strings().withChars("0123456789abcdef").ofLength(2 * SporkData.HASH_BYTES);
	}

	/** Lists of hashes well within the cap. */
	public static Arbitrary<Set<String>> cappedSets() {
		return hashes().set().ofMaxSize(300);
	}

	/** As many different hashes as asked for. */
	public static Set<String> distinctHashes(int count) {
		return IntStream.range(0, count).mapToObj(index -> "%040x".formatted(index)).collect(Collectors.toSet());
	}

	/** The data of a list that approves the hashes given. */
	public static SporkData with(Set<String> addresses) {
		final SporkData data = new SporkData();

		data.setApprovedLegacyAddresses(addresses);
		return data;
	}

	/** Whether the list passes the check every node makes. */
	private static boolean accepts(SporkData data) {
		try {
			data.validate();
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** The hashes and one more entry. */
	private static Set<String> plus(Set<String> addresses, String entry) {
		final Set<String> extended = new HashSet<>(addresses);

		extended.add(entry);
		return extended;
	}

	/** Lists of hashes well within the cap. */
	@Provide
	public Arbitrary<Set<String>> sets() {
		return cappedSets();
	}

	/** Entries that are not forty lower-case hex characters: too short, too long, upper case or not hex. */
	@Provide
	public Arbitrary<String> notHashes() {
		return Arbitraries.oneOf(Arbitraries.strings().ofMaxLength(80), hashes().map(String::toUpperCase),
			hashes().map(hash -> hash.substring(1)), hashes().map(hash -> hash + "0"),
			hashes().map(hash -> "g" + hash.substring(1))
		).filter(entry -> !entry.matches(HASH_PATTERN));
	}

	/** A new whitelist approves no one and passes the check. */
	@Example
	public void startsAsAnEmptyListThatIsValid() {
		final SporkData data = new LegacyWhiteList().getData();

		data.validate();
		assertThat(data.getApprovedLegacyAddresses(), is(empty()));
		assertThat(data.empty(), equalTo(data));
	}

	/** The whitelist is spork type 1070, made by its type and governed by the board. */
	@Example
	public void isRegisteredAsAGovernedSporkType() {
		final GridSpork spork = GridSpork.create(GridSpork.Type.LEGACY_WHITELIST);
		final short governed = GridSpork.Flag.GOVERNED.getValue();

		assertThat(GridSpork.Type.get(WIRE_VALUE), equalTo(GridSpork.Type.LEGACY_WHITELIST));
		assertThat(spork, instanceOf(LegacyWhiteList.class));
		assertThat(spork.getType(), equalTo(GridSpork.Type.LEGACY_WHITELIST));
		assertThat(spork.getFlags() & governed, equalTo((int) governed));
	}

	/** The spork database keeps the whitelist apart from every other spork. */
	@Example
	public void isKeptByTheSporkDatabaseInASlotOfItsOwn() {
		final SporkDatabase database = SporkDatabase.builder().build();
		final LegacyWhiteList whitelist = new LegacyWhiteList();

		database.set(whitelist);
		assertThat(database.getLegacyWhiteList(), sameInstance(whitelist));
		assertThat(database.get(GridSpork.Type.LEGACY_WHITELIST), sameInstance(whitelist));
		assertThat(database.get(GridSpork.Type.LEDGER_BLACKLIST), nullValue());
	}

	/** Any list of hashes within the cap passes the check. */
	@Property
	public void acceptsAnySetOfHashes(@ForAll("sets") Set<String> addresses) {
		assertThat(accepts(with(addresses)), is(true));
	}

	/** A list as long as the cap passes and one entry more does not. */
	@Example
	public void acceptsTheCapAndRefusesOneMore() {
		assertThat(accepts(with(distinctHashes(SporkData.MAX_ENTRIES))), is(true));
		assertThat(accepts(with(distinctHashes(SporkData.MAX_ENTRIES + 1))), is(false));
	}

	/** One entry that is no hash160 in lower-case hex fails the whole list. */
	@Property
	public void refusesAnEntryThatIsNotExactlyFortyLowerCaseHexCharacters(@ForAll("sets") Set<String> addresses,
		@ForAll("notHashes") String entry) {

		assertThat(accepts(with(plus(addresses, entry))), is(false));
	}

	/** A missing list or a missing entry fails the check. */
	@Example
	public void refusesAMissingListAndAMissingEntry() {
		final Set<String> withNull = new HashSet<>(List.of("0".repeat(40)));

		withNull.add(null);
		assertThat(accepts(with(null)), is(false));
		assertThat(accepts(with(withNull)), is(false));
	}

	/**
	 * Spork data is told apart by the names of its fields, so the whitelist is read from its own field and the
	 * blacklist's field still reads as the blacklist.
	 */
	@SneakyThrows
	@Example
	public void isFoundByDeductionFromItsOwnFieldAlone() {
		final ObjectMapper mapper = new ObjectMapper();
		final String listed = "{\"approvedLegacyAddresses\":[\"" + "a".repeat(40) + "\"]}";

		assertThat(mapper.readValue("{\"approvedLegacyAddresses\":[]}", ChunkData.class), equalTo(with(Set.of())));
		assertThat(mapper.readValue(listed, ChunkData.class), equalTo(with(Set.of("a".repeat(40)))));
		assertThat(mapper.readValue("{\"legacyAddresses\":[]}", ChunkData.class),
			instanceOf(LedgerBlacklist.SporkData.class));

		final JsonNode written = mapper.valueToTree(with(Set.of("b".repeat(40))));

		assertThat(written.fieldNames().next(), equalTo("approvedLegacyAddresses"));
		assertThat(written.size(), is(1));
	}
}
