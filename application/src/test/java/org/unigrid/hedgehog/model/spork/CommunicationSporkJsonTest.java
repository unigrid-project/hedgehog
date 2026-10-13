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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;
import org.unigrid.hedgehog.model.spork.CommunicationSpork.SporkData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

public class CommunicationSporkJsonTest {
	private final ObjectMapper mapper = new JsonConfiguration().getContext(CommunicationSpork.class);

	private Set<String> propertiesOf(Class<?> type) {
		return mapper.getSerializationConfig().introspect(mapper.constructType(type)).findProperties().stream()
			.map(BeanPropertyDefinition::getName).collect(Collectors.toSet());
	}

	private static SporkData holding(int groupSize, int communicationFlags) {
		final SporkData data = new SporkData();

		data.setGroupSize(groupSize);
		data.setCommunicationFlags(communicationFlags);
		return data;
	}

	@Provide
	public Arbitrary<SporkData> sporkData() {
		return CommunicationSporkTest.sporkDataAcrossTheBounds();
	}

	@SneakyThrows
	@Property(tries = 50)
	public void readsBackAsChunkData(@ForAll("sporkData") SporkData data) {
		assertThat(mapper.readValue(mapper.writeValueAsString(data), ChunkData.class), equalTo(data));
	}

	@SneakyThrows
	@Example
	public void readsBackInsideItsSpork() {
		final CommunicationSpork spork = new CommunicationSpork();

		spork.archive();

		final CommunicationSpork read = mapper.readValue(mapper.writeValueAsString(spork), CommunicationSpork.class);
		final SporkData data = read.getData();
		final SporkData previousData = read.getPreviousData();

		assertThat(data, equalTo(spork.getData()));
		assertThat(previousData, equalTo(spork.getPreviousData()));
		assertThat(read.getTimeStamp(), equalTo(spork.getTimeStamp()));
	}

	@SneakyThrows
	@Example
	public void readsBackTheDataAndThePreviousDataAsTheDifferentValuesTheyHeld() {
		final CommunicationSpork spork = new CommunicationSpork();

		spork.setData(holding(5, 1));
		spork.archive();
		spork.setData(holding(7, 2));

		final CommunicationSpork read = mapper.readValue(mapper.writeValueAsString(spork), CommunicationSpork.class);
		final SporkData data = read.getData();
		final SporkData previousData = read.getPreviousData();

		assertThat(data, equalTo(holding(7, 2)));
		assertThat(previousData, equalTo(holding(5, 1)));
	}

	@Example
	public void writesExactlyTheFieldNamesOfTheSporkData() {
		final JsonNode tree = mapper.valueToTree(new SporkData());
		final Set<String> names = new HashSet<>();

		tree.fieldNames().forEachRemaining(names::add);

		assertThat(names, equalTo(Set.of("groupSize", "reorganizePeriodSeconds", "chainsPerGroup", "clientsPerChain",
			"chainCapacityBytes", "retentionSeconds", "maxEntryBytes", "sessionIdleSeconds", "communicationFlags")));
	}

	@Example
	public void sharesNoFieldWithAnotherSporkData() {
		final Set<String> own = propertiesOf(SporkData.class);

		for (JsonSubTypes.Type subtype : ChunkData.class.getAnnotation(JsonSubTypes.class).value()) {
			if (!subtype.value().equals(SporkData.class)) {
				final Set<String> shared = new HashSet<>(own);

				shared.retainAll(propertiesOf(subtype.value()));
				assertThat(subtype.value().getName(), shared, empty());
			}
		}
	}
}
