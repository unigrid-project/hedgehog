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

package org.unigrid.hedgehog.model.producer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.statistics.Statistics;
import org.apache.commons.lang3.reflect.FieldUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.spork.StorageSpork.SporkData;
import org.unigrid.hedgehog.model.spork.StorageSporkTest;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.service.storage.InMemoryTransport;
import org.unigrid.hedgehog.service.storage.StorageArbitraries;
import org.unigrid.hedgehog.service.storage.StorageDisabledException;
import org.unigrid.hedgehog.service.storage.StorageFleet;
import org.unigrid.hedgehog.service.storage.StorageService;
import org.unigrid.hedgehog.service.storage.StorageTestData;

public class StorageProducerTest {
	@SneakyThrows
	private static StorageProducer producerOf(final SporkDatabase database) {
		final StorageProducer producer = new StorageProducer();

		FieldUtils.writeField(producer, "sporkDatabase", database, true);
		return producer;
	}

	private static Optional<SporkData> storageSporkOf(final SporkDatabase database) {
		return producerOf(database).storageSpork();
	}

	@Provide
	public Arbitrary<SporkData> sporkData() {
		return StorageSporkTest.sporkDataAcrossTheBounds();
	}

	@Property
	public void exposesOnlyValidStorageSporks(@ForAll("sporkData") final SporkData data) {
		final StorageSpork spork = new StorageSpork();
		final boolean valid = StorageSporkTest.accepts(data);

		spork.setData(data);
		Statistics.collect(valid);
		assertThat(storageSporkOf(SporkDatabase.builder().storageSpork(spork).build()),
			equalTo(valid ? Optional.of(data) : Optional.empty()));

		Statistics.coverage(coverage -> {
			coverage.check(true).count(count -> count > 0);
			coverage.check(false).count(count -> count > 0);
		});
	}

	@Example
	public void disablesStorageWithoutAStorageSpork() {
		assertThat(storageSporkOf(SporkDatabase.builder().build()), equalTo(Optional.empty()));
	}

	@Example
	public void producesAStorageServiceThatFollowsTheSpork() {
		final StorageProducer producer = producerOf(SporkDatabase.builder().build());

		assertThrows(StorageDisabledException.class, () -> producer.storageService(
			new TopologyGridnodeDirectory(List::of, () -> "self"), new InMemoryTransport())
			.store(new ByteArrayInputStream(new byte[1])));
	}

	@Example
	public void producesARepairerThatRestsWithoutAStorageSpork() {
		final StorageFleet fleet = new StorageFleet(StorageTestData.parameters(), 1);

		assertThat(producerOf(SporkDatabase.builder().build()).groupRepairer(fleet.getStores().get("gridnode-0"),
			fleet.directory("gridnode-0"), fleet.getTransport()).currentEpoch(), equalTo(OptionalLong.empty()));
	}

	@Provide
	public Arbitrary<SporkData> storageParameters() {
		return StorageArbitraries.parameters();
	}

	@SneakyThrows
	@Property(tries = 10)
	public void producesAStorageServiceThatStoresAndRetrieves(@ForAll("storageParameters") final SporkData parameters,
		@ForAll @Size(max = 256) final byte[] file) {

		final StorageSpork spork = new StorageSpork();
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());
		final ByteArrayOutputStream output = new ByteArrayOutputStream();

		spork.setData(parameters);

		final StorageService service = producerOf(SporkDatabase.builder().storageSpork(spork).build())
			.storageService(fleet.directory("client"), fleet.getTransport());

		service.retrieve(service.store(new ByteArrayInputStream(file)), output);
		assertThat(output.toByteArray(), equalTo(file));
	}
}
