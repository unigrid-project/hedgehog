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

package org.unigrid.hedgehog.service.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.Fingerprint;
import org.unigrid.hedgehog.model.storage.FingerprintKeys;
import org.unigrid.hedgehog.model.storage.GroupKey;
import org.unigrid.hedgehog.model.storage.StorageLayout;
import org.unigrid.hedgehog.s3.spi.Blob;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobNotFoundException;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;
import org.unigrid.hedgehog.s3.spi.BlobUnavailableException;

public class StorageBlobStoreTest {
	@Provide
	Arbitrary<Tuple2<StorageSpork.SporkData, byte[]>> scenarios() {
		return StorageArbitraries.parameters().flatMap(parameters -> StorageArbitraries.files(parameters)
			.map(file -> Tuple.of(parameters, file)));
	}

	@Provide
	Arbitrary<StorageSpork.SporkData> parameters() {
		return StorageArbitraries.parameters();
	}

	private static StorageFleet fleet(final StorageSpork.SporkData parameters) {
		return new StorageFleet(parameters, parameters.window());
	}

	private static BlobStore store(final StorageFleet fleet) {
		return new StorageBlobStore(fleet.service(new SecureRandom()));
	}

	private static BlobId store(final BlobStore store, final byte[] file) throws Exception {
		return store.store(new ByteArrayInputStream(file));
	}

	private static byte[] read(final Blob blob) throws Exception {
		final ByteArrayOutputStream output = new ByteArrayOutputStream();

		blob.writeTo(output);
		return output.toByteArray();
	}

	@Property(tries = 30)
	public void readsBackWhatItStored(@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario)
		throws Exception {

		final BlobStore store = store(fleet(scenario.get1()));
		final Blob blob = store.open(store(store, scenario.get2()));

		assertThat(blob.size(), equalTo((long) scenario.get2().length));
		assertThat(read(blob), equalTo(scenario.get2()));
	}

	@Property(tries = 20)
	public void forgetsADeletedBlob(@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario)
		throws Exception {

		final BlobStore store = store(fleet(scenario.get1()));
		final BlobId id = store(store, scenario.get2());

		store.delete(id);
		assertThrows(BlobNotFoundException.class, () -> store.open(id));
		assertThrows(BlobNotFoundException.class, () -> store.delete(id));
	}

	@Property(tries = 20)
	public void findsNothingBehindIdsItNeverHandedOut(@ForAll("parameters") final StorageSpork.SporkData parameters) {
		final BlobStore store = store(fleet(parameters));

		assertThrows(BlobNotFoundException.class, () -> store.open(new BlobId("not a fingerprint")));
		assertThrows(BlobNotFoundException.class, () -> store.open(new BlobId(Fingerprint
			.generate(new SecureRandom()).encode())));
	}

	@Property(tries = 20)
	public void reportsTooFewGridnodesOrNoSporkAsUnavailable(@ForAll("parameters") final StorageSpork.SporkData parameters) {
		final StorageFleet small = new StorageFleet(parameters, parameters.layout().guaranteedFragments() - 1);
		final BlobStore disabled = new StorageBlobStore(new StorageService(small.directory("client"),
			small.getTransport(), Optional::empty, new SecureRandom(), Duration.ZERO));

		assertThrows(BlobUnavailableException.class, () -> store(store(small), new byte[1]));
		assertThrows(BlobUnavailableException.class, () -> store(disabled, new byte[1]));
		assertThrows(BlobUnavailableException.class, () -> disabled.open(new BlobId(Fingerprint
			.generate(new SecureRandom()).encode())));
	}

	@Property(tries = 20)
	public void reportsLostDataAsAFailureOfTheStore(@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario,
		@ForAll final long seed) throws Exception {

		final StorageFleet fleet = fleet(scenario.get1());
		final BlobStore store = store(fleet);
		final BlobId id = store(store, scenario.get2());
		final FingerprintKeys keys = new FingerprintKeys(Fingerprint.parse(id.value()));
		final StorageLayout layout = StorageLayout.of(scenario.get1().layout(), scenario.get2().length);

		Assume.that(layout.stripes() > 0);

		final int lost = new Random(seed).nextInt(layout.stripes());

		for (int index = 0; index < layout.dataChunksIn(lost) + layout.parityChunksIn(lost); index++) {
			fleet.forget(new GroupKey(keys.chunkSeed(lost, index)).groupId());
		}

		final BlobStoreException failure = assertThrows(BlobStoreException.class, () -> read(store.open(id)));

		assertThat(failure.getClass(), equalTo(BlobStoreException.class));
		assertThat(failure.getCause(), instanceOf(DataLossException.class));
	}
}
