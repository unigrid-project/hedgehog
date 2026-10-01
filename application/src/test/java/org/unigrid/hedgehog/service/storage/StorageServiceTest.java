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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;
import net.jqwik.api.constraints.IntRange;
import org.bitcoinj.base.Base58;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.ChunkGroups;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.Manifest;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import org.unigrid.hedgehog.model.storage.StorageLayout;
import org.unigrid.hedgehog.model.storage.crypto.ChunkCipher;
import org.unigrid.hedgehog.model.storage.crypto.Fingerprint;
import org.unigrid.hedgehog.model.storage.crypto.FingerprintKeys;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
import org.unigrid.hedgehog.model.storage.erasure.ReedSolomon;
import org.unigrid.hedgehog.model.storage.placement.Placement;

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
	public void roundTripsAnyFileUnderAnyLayout(@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario,
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
	public void survivesAnyLossWithinInnerParity(@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario,
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
	public void neverReturnsWrongBytes(@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario,
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
		@ForAll("sporkChanges") final Tuple2<StorageSpork.SporkData, StorageSpork.SporkData> change, @ForAll final long seed) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(change.get1(),
			Math.max(change.get1().window(), change.get2().window()) + random.nextInt(4));
		final StorageService service = fleet.service(new SecureRandom());
		final byte[] file = bytes(random, random.nextInt(4 * change.get1().layout().payloadSize()));
		final Fingerprint fingerprint = store(service, file);

		fleet.setParameters(change.get2());
		assertThat(retrieve(service, fingerprint), equalTo(file));
	}

	@Property(tries = 40)
	public void retrievesAfterFewerManifestCopiesWithoutTheFirst(
		@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario, @ForAll final long seed) {

		final Random random = new Random(seed);
		final StorageSpork.SporkData parameters = scenario.get1();

		parameters.setManifestCopies(Math.max(2, parameters.getManifestCopies()));

		final StorageFleet fleet = fleet(parameters, random);
		final StorageService service = fleet.service(new SecureRandom());
		final Fingerprint fingerprint = store(service, scenario.get2());

		fleet.forget(new GroupKey(new FingerprintKeys(fingerprint).manifestSeed(0)).groupId());
		parameters.setManifestCopies(1 + random.nextInt(parameters.getManifestCopies() - 1));
		assertThat(retrieve(service, fingerprint), equalTo(scenario.get2()));
	}

	/* Only the owner can sign a second seal of a group, but mixing it with the first must still read as a missing
	   chunk that outer parity replaces, never as an unchecked failure */
	@Property(tries = 40)
	public void treatsMixedSealsOfAGroupAsMissing(
		@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario, @ForAll final long seed) {

		final Random random = new Random(seed);
		final StorageSpork.SporkData parameters = scenario.get1();
		final StorageFleet fleet = fleet(parameters, random);
		final StorageService service = fleet.service(new SecureRandom());
		final Fingerprint fingerprint = store(service, scenario.get2());
		final GroupKey key = new GroupKey(new FingerprintKeys(fingerprint).chunkSeed(0, 0));
		final List<Gridnode> ranked = Placement.rank(key.groupId(), fleet.getGridnodes());

		fleet.replace(key.groupId(), ChunkGroups.seal(bytes(random, parameters.getChunkSize()), key,
			fingerprint.format(), parameters.layout()), gridnode -> ranked.indexOf(gridnode) % 2 == 1);

		if (StorageLayout.of(parameters.layout(), scenario.get2().length).parityChunksIn(0) > 0) {
			assertThat(retrieve(service, fingerprint), equalTo(scenario.get2()));
		} else {
			assertThrows(DataLossException.class, () -> service.retrieve(fingerprint, new ByteArrayOutputStream()));
		}
	}

	@SneakyThrows
	@Property(tries = 30)
	public void findsNothingBehindManifestsNoLayoutCanAddress(
		@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario, @ForAll final long seed) {

		final StorageSpork.SporkData parameters = scenario.get1();
		final StorageFleet fleet = fleet(parameters, new Random(seed));
		final StorageService service = fleet.service(new SecureRandom());
		final Fingerprint fingerprint = store(service, scenario.get2());
		final FingerprintKeys keys = new FingerprintKeys(fingerprint);
		final byte[] manifest = Arrays.copyOf(new Manifest(keys.format(), Long.MAX_VALUE,
			parameters.getManifestCopies(), parameters.layout()).encode(), parameters.layout().payloadSize());

		for (int copy = 0; copy < parameters.getManifestCopies(); copy++) {
			final GroupKey key = new GroupKey(keys.manifestSeed(copy));

			fleet.replace(key.groupId(), ChunkGroups.seal(ChunkCipher.forManifest(keys).seal(copy, manifest), key,
				keys.format(), parameters.layout()), gridnode -> true);
		}

		assertThrows(FingerprintNotFoundException.class, () -> service.open(fingerprint));
	}

	/* Only one format exists so far, so a manifest of another one can only be faked. A fake is never torn down
	   within this class, so it answers that one format again once the property is done with it. */
	@SneakyThrows
	@Property(tries = 20)
	public void findsNothingBehindAManifestOfAnotherFormat(
		@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario, @ForAll final long seed) {

		final StorageService service = fleet(scenario.get1(), new Random(seed)).service(new SecureRandom());
		final Fingerprint fingerprint = store(service, scenario.get2());
		final AtomicBoolean foreign = new AtomicBoolean(true);

		new MockUp<Manifest>() {
			@Mock
			StorageFormat getFormat() {
				return foreign.get() ? null : StorageFormat.current();
			}
		};

		try {
			assertThrows(FingerprintNotFoundException.class, () -> service.open(fingerprint));
		} finally {
			foreign.set(false);
		}

		assertThat(retrieve(service, fingerprint), equalTo(scenario.get2()));
	}

	@Property(tries = 40)
	public void retrievesAfterGridnodesJoin(@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario,
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
	public void deletesEveryGroupOfAFile(@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario,
		@ForAll final long seed) {

		final StorageFleet fleet = fleet(scenario.get1(), new Random(seed));
		final StorageService service = fleet.service(new SecureRandom());
		final Fingerprint fingerprint = store(service, scenario.get2());

		service.delete(fingerprint);

		assertThat(fleet.groups().isEmpty(), is(true));
		assertThrows(FingerprintNotFoundException.class, () -> service.open(fingerprint));
	}

	/* Opening recovers the first stripe, so only a later stripe can fail once bytes have been written. Either way
	   the manifest alone is enough to delete what is left of the file. */
	@SneakyThrows
	@Property(tries = 40)
	public void reportsALostFirstStripeOnOpen(@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario,
		@ForAll final long seed) {

		final Random random = new Random(seed);
		final StorageFleet fleet = fleet(scenario.get1(), random);
		final StorageService service = fleet.service(new SecureRandom());
		final Fingerprint fingerprint = store(service, scenario.get2());
		final FingerprintKeys keys = new FingerprintKeys(fingerprint);
		final StorageLayout layout = StorageLayout.of(scenario.get1().layout(), scenario.get2().length);

		Assume.that(layout.stripes() > 0);

		final int lost = random.nextInt(layout.stripes());

		for (int index = 0; index < layout.dataChunksIn(lost) + layout.parityChunksIn(lost); index++) {
			fleet.forget(new GroupKey(keys.chunkSeed(lost, index)).groupId());
		}

		if (lost == 0) {
			assertThrows(DataLossException.class, () -> service.open(fingerprint));
		} else {
			final ByteArrayOutputStream output = new ByteArrayOutputStream();
			final Retrieval retrieval = service.open(fingerprint);

			assertThrows(DataLossException.class, () -> retrieval.writeTo(output));
			assertThat(output.size() < scenario.get2().length, is(true));
		}

		service.delete(fingerprint);
		assertThrows(FingerprintNotFoundException.class, () -> service.open(fingerprint));
	}

	@Property(tries = 40)
	public void neverSendsTheFingerprintOrAnythingDerivedFromIt(
		@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario, @ForAll final long seed)
		throws StorageException {

		final StorageSpork.SporkData parameters = scenario.get1();
		final StorageFleet fleet = fleet(parameters, new Random(seed));
		final StorageService service = fleet.service(new SecureRandom());
		final Fingerprint fingerprint = store(service, scenario.get2());
		final FingerprintKeys keys = new FingerprintKeys(fingerprint);
		final byte[] decoded = Base58.decodeChecked(fingerprint.encode());
		final Set<ByteBuffer> secrets = new HashSet<>(List.of(ByteBuffer.wrap(decoded, 1, Fingerprint.SECRET_SIZE).slice(),
			ByteBuffer.wrap(keys.chunkKey()), ByteBuffer.wrap(keys.manifestKey())));
		final StorageLayout layout = StorageLayout.of(parameters.layout(), scenario.get2().length);

		for (int stripe = 0; stripe < layout.stripes(); stripe++) {
			for (int index = 0; index < layout.dataChunksIn(stripe) + layout.parityChunksIn(stripe); index++) {
				secrets.add(ByteBuffer.wrap(keys.chunkSeed(stripe, index)));
			}
		}

		for (int copy = 0; copy < parameters.getManifestCopies(); copy++) {
			secrets.add(ByteBuffer.wrap(keys.manifestSeed(copy)));
		}

		assertThat(retrieve(service, fingerprint), equalTo(scenario.get2()));
		service.delete(fingerprint);

		for (final byte[] payload : fleet.getTransport().getSent()) {
			assertThat(leaksAny(payload, secrets), is(false));
		}
	}

	/* A nonce sealing two plaintexts under one key breaks AES-GCM, so every placed ciphertext must open under its
	   own sequence only, and nothing may be placed beyond the groups the layout accounts for */
	@Property(tries = 40)
	public void sealsEveryChunkUnderItsOwnSequence(
		@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario, @ForAll final long seed)
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
	public void findsNothingForUnknownFingerprints(@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario,
		@ForAll final long seed) {

		final StorageFleet fleet = fleet(scenario.get1(), new Random(seed));
		final StorageService service = fleet.service(new SecureRandom());

		store(service, scenario.get2());
		assertThrows(FingerprintNotFoundException.class, () -> service.open(Fingerprint.generate(new SecureRandom())));
	}

	@Property(tries = 30)
	public void rollsBackUploadsThatCannotBePlaced(@ForAll("scenarios") final Tuple2<StorageSpork.SporkData, byte[]> scenario) {
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

	/* The secret, keys and seeds are all one size, so every window of that size is looked up once */
	private static boolean leaksAny(final byte[] payload, final Set<ByteBuffer> secrets) {
		return IntStream.rangeClosed(0, payload.length - FingerprintKeys.KEY_SIZE)
			.anyMatch(i -> secrets.contains(ByteBuffer.wrap(payload, i, FingerprintKeys.KEY_SIZE)));
	}
}
