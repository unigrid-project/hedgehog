/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;
import net.jqwik.api.constraints.IntRange;
import org.bitcoinj.core.Base58;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.ChunkGroups;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageLayout;
import org.unigrid.hedgehog.model.storage.crypto.ChunkCipher;
import org.unigrid.hedgehog.model.storage.crypto.Fingerprint;
import org.unigrid.hedgehog.model.storage.crypto.FingerprintKeys;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
import org.unigrid.hedgehog.model.storage.erasure.ReedSolomon;

public class StorageServiceTest {
	@Provide
	Arbitrary<Tuple2<StorageSpork.SporkData, byte[]>> scenarios() {
		return StorageArbitraries.parameters().flatMap(parameters -> StorageArbitraries.files(parameters)
			.map(file -> Tuple.of(parameters, file)));
	}

	@Provide
	Arbitrary<Tuple2<StorageSpork.SporkData, StorageSpork.SporkData>> sporkChanges() {
		return Combinators.combine(StorageArbitraries.parameters(), StorageArbitraries.parameters()).as(Tuple::of);
	}

	@Provide
	Arbitrary<StorageSpork.SporkData> parameters() {
		return StorageArbitraries.parameters();
	}

	@SneakyThrows
	private static Fingerprint store(final StorageService service, final byte[] file) {
		return service.store(new ByteArrayInputStream(file));
	}

	@SneakyThrows
	private static byte[] retrieve(final StorageService service, final Fingerprint fingerprint) {
		final ByteArrayOutputStream output = new ByteArrayOutputStream();

		service.retrieve(fingerprint, output);
		return output.toByteArray();
	}

	private static StorageFleet fleet(final StorageSpork.SporkData parameters, final Random random) {
		return new StorageFleet(parameters, parameters.window() + random.nextInt(8));
	}

	private static byte[] bytes(final Random random, final int size) {
		final byte[] bytes = new byte[size];
		random.nextBytes(bytes);
		return bytes;
	}

	@Property(tries = 60)
	public void roundTripsAnyFileUnderAnyLayout(@ForAll("scenarios") Tuple2<StorageSpork.SporkData, byte[]> scenario,
		@ForAll final long seed) {

		final StorageService service = fleet(scenario.get1(), new Random(seed)).service(new SecureRandom());

		assertThat(retrieve(service, store(service, scenario.get2())), equalTo(scenario.get2()));
	}

	@Property(tries = 40)
	public void roundTripsBoundarySizes(@ForAll("parameters") final StorageSpork.SporkData parameters,
		@ForAll @IntRange(min = 0, max = 4) final int pick, @ForAll final long seed) {

		final int payload = parameters.layout().payloadSize();
		final int stripe = payload * parameters.getMaxOuterDataChunks();
		final int size = new int[] { 0, payload, payload + 1, stripe, stripe + 1 }[pick];
		final Random random = new Random(seed);
		final StorageService service = fleet(parameters, random).service(new SecureRandom());
		final byte[] file = bytes(random, size);

		assertThat(retrieve(service, store(service, file)), equalTo(file));
	}

	@Property(tries = 60)
	public void survivesAnyLossWithinInnerParity(@ForAll("scenarios") Tuple2<StorageSpork.SporkData, byte[]> scenario,
		@ForAll final long seed) {

		final Random random = new Random(seed);
		final StorageFleet fleet = fleet(scenario.get1(), random);
		final StorageService service = fleet.service(new SecureRandom());
		final Fingerprint fingerprint = store(service, scenario.get2());
		final List<Gridnode> victims = new ArrayList<>(fleet.getGridnodes());

		Collections.shuffle(victims, random);
		victims.subList(0, random.nextInt(scenario.get1().layout().parityFragments() + 1))
			.forEach(gridnode -> fleet.getTransport().offline(gridnode.getId()));

		assertThat(retrieve(service, fingerprint), equalTo(scenario.get2()));
	}

	@Property(tries = 60)
	public void neverReturnsWrongBytes(@ForAll("scenarios") Tuple2<StorageSpork.SporkData, byte[]> scenario,
		@ForAll final long seed) {

		final Random random = new Random(seed);
		final StorageFleet fleet = fleet(scenario.get1(), random);
		final StorageService service = fleet.service(new SecureRandom());
		final Fingerprint fingerprint = store(service, scenario.get2());

		fleet.getGridnodes().stream().filter(gridnode -> random.nextInt(3) == 0)
			.forEach(gridnode -> fleet.getTransport().offline(gridnode.getId()));

		try {
			final ByteArrayOutputStream output = new ByteArrayOutputStream();
			service.retrieve(fingerprint, output);
			assertThat(output.toByteArray(), equalTo(scenario.get2()));
		} catch (StorageException expected) {
			/* Losing more than the parity allows must fail loudly, never return different bytes */
		} catch (IOException ex) {
			throw new AssertionError(ex);
		}
	}

	@Property(tries = 40)
	public void retrievesAfterSporkChange(
		@ForAll("sporkChanges") Tuple2<StorageSpork.SporkData, StorageSpork.SporkData> change, @ForAll final long seed) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(change.get1(),
			Math.max(change.get1().window(), change.get2().window()) + random.nextInt(4));
		final StorageService service = fleet.service(new SecureRandom());
		final byte[] file = bytes(random, random.nextInt(4 * change.get1().layout().payloadSize()));
		final Fingerprint fingerprint = store(service, file);

		change.get2().setPlacementSlack(change.get1().getPlacementSlack());
		fleet.setParameters(change.get2());
		assertThat(retrieve(service, fingerprint), equalTo(file));
	}

	@Property(tries = 40)
	public void retrievesAfterGridnodesJoin(@ForAll("scenarios") Tuple2<StorageSpork.SporkData, byte[]> scenario,
		@ForAll final long seed) {

		final Random random = new Random(seed);
		final StorageSpork.SporkData parameters = scenario.get1();
		final StorageFleet fleet = fleet(parameters, random);
		final StorageService service = fleet.service(new SecureRandom());
		final Fingerprint fingerprint = store(service, scenario.get2());
		final int spare = parameters.window() - parameters.layout().guaranteedFragments();

		for (int joined = random.nextInt(spare + 1); joined > 0; joined--) {
			fleet.join();
		}

		assertThat(retrieve(service, fingerprint), equalTo(scenario.get2()));
	}

	@SneakyThrows
	@Property(tries = 40)
	public void deletesEveryGroupOfAFile(@ForAll("scenarios") Tuple2<StorageSpork.SporkData, byte[]> scenario,
		@ForAll final long seed) {

		final StorageFleet fleet = fleet(scenario.get1(), new Random(seed));
		final StorageService service = fleet.service(new SecureRandom());
		final Fingerprint fingerprint = store(service, scenario.get2());

		service.delete(fingerprint);

		assertThat(fleet.groups().isEmpty(), is(true));
		assertThrows(FingerprintNotFoundException.class, () -> service.open(fingerprint));
	}

	@Property(tries = 40)
	public void neverSendsTheFingerprintOrItsKeys(@ForAll("scenarios") Tuple2<StorageSpork.SporkData, byte[]> scenario,
		@ForAll final long seed) {

		final StorageFleet fleet = fleet(scenario.get1(), new Random(seed));
		final Fingerprint fingerprint = store(fleet.service(new SecureRandom()), scenario.get2());
		final byte[] decoded = Base58.decodeChecked(fingerprint.encode());
		final byte[] secret = Arrays.copyOfRange(decoded, 1, decoded.length);
		final FingerprintKeys keys = new FingerprintKeys(fingerprint);

		for (byte[] payload : fleet.getTransport().getSent()) {
			assertThat(contains(payload, secret), is(false));
			assertThat(contains(payload, keys.chunkKey()), is(false));
			assertThat(contains(payload, keys.manifestKey()), is(false));
		}
	}

	/* A nonce sealing two plaintexts under one key breaks AES-GCM, so every placed ciphertext must open under its
	   own sequence only, and nothing may be placed beyond the groups the layout accounts for */
	@Property(tries = 40)
	public void sealsEveryChunkUnderItsOwnSequence(
		@ForAll("scenarios") Tuple2<StorageSpork.SporkData, byte[]> scenario, @ForAll final long seed)
		throws GeneralSecurityException {

		final StorageSpork.SporkData parameters = scenario.get1();
		final byte[] file = scenario.get2();
		final StorageFleet fleet = fleet(parameters, new Random(seed));
		final FingerprintKeys keys = new FingerprintKeys(store(fleet.service(new SecureRandom()), file));
		final StorageLayout layout = StorageLayout.of(parameters.layout(), file.length);
		final ChunkCipher chunkCipher = ChunkCipher.forChunks(keys);
		final ChunkCipher manifestCipher = ChunkCipher.forManifest(keys);
		final Set<Long> sequences = new HashSet<>();
		final Set<ByteBuffer> manifests = new HashSet<>();
		final Set<GroupId> groups = new HashSet<>();

		for (int stripe = 0; stripe < layout.stripes(); stripe++) {
			for (int index = 0; index < layout.dataChunksIn(stripe) + layout.parityChunksIn(stripe); index++) {
				groups.add(new GroupKey(keys.chunkSeed(stripe, index)).groupId());
			}

			for (int index = 0; index < layout.dataChunksIn(stripe); index++) {
				final long sequence = layout.firstSequenceOf(stripe) + index;

				assertThat(chunkCipher.open(sequence, chunk(fleet, keys.chunkSeed(stripe, index), keys)),
					equalTo(payloadAt(file, sequence, parameters.layout().payloadSize())));
				sequences.add(sequence);
			}
		}

		for (int copy = 0; copy < parameters.getManifestCopies(); copy++) {
			groups.add(new GroupKey(keys.manifestSeed(copy)).groupId());
			manifests.add(ByteBuffer.wrap(manifestCipher.open(copy, chunk(fleet, keys.manifestSeed(copy), keys))));
		}

		assertThat(sequences, hasSize((int) layout.dataChunks()));
		assertThat(manifests, hasSize(1));
		assertThat(fleet.groups(), equalTo(groups));
	}

	@Property(tries = 30)
	public void findsNothingForUnknownFingerprints(@ForAll("scenarios") Tuple2<StorageSpork.SporkData, byte[]> scenario,
		@ForAll final long seed) {

		final StorageFleet fleet = fleet(scenario.get1(), new Random(seed));
		final StorageService service = fleet.service(new SecureRandom());

		store(service, scenario.get2());
		assertThrows(FingerprintNotFoundException.class, () -> service.open(Fingerprint.generate(new SecureRandom())));
	}

	@Property(tries = 30)
	public void rollsBackUploadsThatCannotBePlaced(@ForAll("scenarios") Tuple2<StorageSpork.SporkData, byte[]> scenario) {
		final StorageSpork.SporkData parameters = scenario.get1();
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());
		final int spare = parameters.window() - parameters.layout().guaranteedFragments();

		fleet.getGridnodes().subList(0, spare + 1).forEach(g -> fleet.getTransport().offline(g.getId()));

		assertThrows(StorageException.class, () -> fleet.service(new SecureRandom())
			.store(new ByteArrayInputStream(scenario.get2())));
		assertThat(fleet.groups().isEmpty(), is(true));
	}

	@Property(tries = 20)
	public void refusesWithoutEnoughGridnodesOrSpork(@ForAll("parameters") final StorageSpork.SporkData parameters) {
		final StorageFleet small = new StorageFleet(parameters, parameters.layout().guaranteedFragments() - 1);
		final StorageService disabled = new StorageService(small.directory("client"), small.getTransport(),
			Optional::empty, new SecureRandom(), Duration.ZERO);

		assertThrows(InsufficientGridnodesException.class, () -> small.service(new SecureRandom())
			.store(new ByteArrayInputStream(new byte[1])));
		assertThrows(StorageDisabledException.class, () -> disabled.store(new ByteArrayInputStream(new byte[1])));
	}

	private static byte[] chunk(final StorageFleet fleet, final byte[] seed, final FingerprintKeys keys) {
		return ChunkGroups.open(new GroupFetcher(fleet.getTransport()).fetch(new GroupKey(seed).groupId(),
			fleet.getGridnodes(), ReedSolomon.MAX_SHARDS, keys.format()));
	}

	private static byte[] payloadAt(final byte[] file, final long sequence, final int payload) {
		final int from = (int) Math.min(file.length, sequence * payload);

		return Arrays.copyOf(Arrays.copyOfRange(file, from, Math.min(file.length, from + payload)), payload);
	}

	private static boolean contains(final byte[] haystack, final byte[] needle) {
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
				return true;
			}
		}

		return false;
	}
}
