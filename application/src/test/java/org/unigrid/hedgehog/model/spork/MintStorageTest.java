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

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.model.Address;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.TestFileOutput;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;

public class MintStorageTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final List<String> STORED = List.of("/spork/spork-0.0.7.db", "/spork/legacy-spork.db");

	private static SporkData with(int number) {
		return with(new Location(new Address("addr"), number));
	}

	private static SporkData with(Location... locations) {
		final SporkData data = new SporkData();

		data.setMints(new HashMap<>());

		for (Location location : locations) {
			data.getMints().put(location, BigDecimal.ONE);
		}

		return data;
	}

	@SneakyThrows
	private static String json(SporkData data) {
		return MAPPER.writeValueAsString(data);
	}

	private static List<String> keysOf(SporkData data) {
		final List<String> keys = new ArrayList<>();

		MAPPER.valueToTree(data).get("mints").fieldNames().forEachRemaining(keys::add);
		return keys;
	}

	@SneakyThrows
	private static SporkData read(String json) {
		return MAPPER.readValue(json, SporkData.class);
	}

	@Example
	public void writesTheNumberOfTheKeyUnsigned() {
		assertThat(keysOf(with(new Location(new Address("a"), -1), new Location(new Address("b"), Integer.MIN_VALUE),
			new Location(new Address("c"), (int) 4_000_000_000L), new Location(new Address("d"), 1_000),
			new Location(new Address("e"), 0), new Location(new Address("f"), Integer.MAX_VALUE))),
			containsInAnyOrder("a/4294967295", "b/2147483648", "c/4000000000", "d/1000", "e/0", "f/2147483647"));
	}

	@Example
	public void readsTheNumberOfTheKeyUnsigned() {
		assertThat(read("{\"mints\":{\"a/4294967295\":1,\"b/2147483648\":2,\"c/4000000000\":3,\"d/1000\":4,\"e/0\":5}}")
			.getMints().keySet(), containsInAnyOrder(new Location(new Address("a"), -1),
			new Location(new Address("b"), Integer.MIN_VALUE), new Location(new Address("c"), (int) 4_000_000_000L),
			new Location(new Address("d"), 1_000), new Location(new Address("e"), 0)));
	}

	@Property
	public void survivesBeingWrittenAndReadAsJson(@ForAll int number) {
		final SporkData data = with(number);

		assertThat(read(json(data)).getMints(), equalTo(data.getMints()));
	}

	@Example
	public void refusesAKeyWithASignOrAValueOfMoreThanThirtyTwoBits() {
		for (String key : List.of("a/-1", "a/+1", "a/4294967296", "a/99999999999", "a/1.5", "a/0x10", "a/", "a/ 1",
			"a/1 ", "a/٣", "a", "")) {

			final JsonMappingException ex = assertThrows(JsonMappingException.class,
				() -> read("{\"mints\":{\"" + key + "\":1}}"));

			assertThat(key, ex.getMessage(), containsString(key));
			assertThat(key, ex.getMessage(), containsString("4294967295"));
		}
	}

	@Example
	public void keepsAddressesWithASlashByTakingTheNumberAfterTheLastOne() {
		final Location location = new Location(new Address("a/b"), 7);

		assertThat(read(json(with(location))).getMints().keySet(), containsInAnyOrder(location));
	}

	@SneakyThrows
	@Example
	public void keepsTheKeysOfSporksStoredBeforeTheNumberWasUnsigned() {
		for (String fixture : STORED) {
			final SporkData data = SporkDatabase.load(Path.of(getClass().getResource(fixture).toURI()))
				.getMintStorage().getData();

			assertThat(fixture, data.getMints().isEmpty(), is(false));
			assertThat(fixture, read(json(data)).getMints(), equalTo(data.getMints()));

			for (Location location : data.getMints().keySet()) {
				assertThat(fixture, location.getHeight(), greaterThanOrEqualTo(0));
				assertThat(fixture, keysOf(data), hasItem(location.getAddress().getWif() + "/" + location.getHeight()));
			}
		}
	}

	@Example
	public void readsTheJsonWrittenBeforeTheNumberWasUnsigned() {
		final SporkData data = read("{\"mints\":{\"UXaddress/1234\":220000,\"UXother/0\":14115}}");

		assertThat(data.getMints().keySet(), containsInAnyOrder(new Location(new Address("UXaddress"), 1234),
			new Location(new Address("UXother"), 0)));

		assertThat(data.getMints().get(new Location(new Address("UXaddress"), 1234)), equalTo(new BigDecimal("220000")));
	}

	@Example
	@SneakyThrows
	public void shouldSerialize() {
		final MintStorage storage = new MintStorage();
		final MintStorage.SporkData data = new MintStorage.SporkData();
		final Map<MintStorage.SporkData.Location, BigDecimal> mints = new HashMap<>();

		storage.setSignature(RandomUtils.nextBytes(16));
		storage.setData(data);
		data.setMints(mints);

		for (int i = 0; i < 10; i++) {
			final Address address = new Address(RandomStringUtils.randomAlphabetic(32) + i);
			mints.put(new MintStorage.SporkData.Location(address, 1000 * i * 42), new BigDecimal(i));
		}

		TestFileOutput.outputJson(storage);
	}
}
