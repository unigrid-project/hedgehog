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

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.ChunkGroups;
import org.unigrid.hedgehog.model.storage.LayoutParameters;
import org.unigrid.hedgehog.model.storage.Manifest;
import org.unigrid.hedgehog.model.storage.crypto.ChunkCipher;
import org.unigrid.hedgehog.model.storage.crypto.FingerprintKeys;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
import org.unigrid.hedgehog.model.storage.erasure.ReedSolomon;
import org.unigrid.hedgehog.model.storage.placement.Placement;

final class StorageUpload {
	private final FingerprintKeys keys;
	private final StorageSpork.SporkData parameters;
	private final LayoutParameters layout;
	private final List<Gridnode> gridnodes;
	private final GroupDistributor distributor;
	private final Random random;
	private final List<GroupKey> placed = new ArrayList<>();
	private boolean ended;

	StorageUpload(final FingerprintKeys keys, final StorageSpork.SporkData parameters, final List<Gridnode> gridnodes,
		final GroupDistributor distributor, final Random random) {

		this.keys = keys;
		this.parameters = parameters;
		this.layout = parameters.layout();
		this.gridnodes = gridnodes;
		this.distributor = distributor;
		this.random = random;
	}

	/* Whatever ends an upload early, errors included, must not leave its groups behind */
	void run(final InputStream input) throws IOException, StorageException {
		boolean stored = false;

		try {
			storeManifest(storeStripes(input));
			stored = true;
		} finally {
			if (!stored) {
				rollback();
			}
		}
	}

	private long storeStripes(final InputStream input) throws IOException, StorageException {
		final ChunkCipher cipher = ChunkCipher.forChunks(keys);
		long fileSize = 0;
		int stripe = 0;
		List<byte[]> reads = readStripe(input, true);

		while (!reads.isEmpty()) {
			fileSize += reads.stream().mapToLong(read -> read.length).sum();
			storeStripe(stripe++, reads, cipher);
			reads = ended ? List.of() : readStripe(input, false);
		}

		return fileSize;
	}

	/* An empty file still becomes one chunk, so every stored file has a first stripe to find. */
	private List<byte[]> readStripe(final InputStream input, final boolean first) throws IOException {
		final List<byte[]> reads = new ArrayList<>();

		while (reads.size() < layout.getMaxOuterDataChunks() && !ended) {
			final byte[] read = input.readNBytes(layout.payloadSize());

			ended = read.length < layout.payloadSize();

			if (read.length > 0 || first && reads.isEmpty()) {
				reads.add(read);
			}
		}

		return reads;
	}

	private void storeStripe(final int stripe, final List<byte[]> reads, final ChunkCipher cipher)
		throws StorageException {

		final long firstSequence = (long) stripe * layout.getMaxOuterDataChunks();
		final byte[][] data = new byte[reads.size()][];

		for (int i = 0; i < data.length; i++) {
			data[i] = seal(cipher, firstSequence + i, Arrays.copyOf(reads.get(i), layout.payloadSize()));
		}

		final byte[][] parity = new ReedSolomon(data.length, layout.outerParityChunks(data.length)).encode(data);
		final List<byte[]> chunks = new ArrayList<>(Arrays.asList(data));

		chunks.addAll(Arrays.asList(parity));

		for (final int index : shuffled(chunks.size())) {
			placeGroup(chunks.get(index), new GroupKey(keys.chunkSeed(stripe, index)));
		}
	}

	/* Every copy seals the same plaintext under its own sequence, so no nonce ever covers two plaintexts */
	private void storeManifest(final long fileSize) throws StorageException {
		final ChunkCipher cipher = ChunkCipher.forManifest(keys);
		final byte[] plaintext = Arrays.copyOf(new Manifest(keys.format(), fileSize, parameters.getManifestCopies(),
			layout).encode(), layout.payloadSize());

		for (int copy = 0; copy < parameters.getManifestCopies(); copy++) {
			placeGroup(seal(cipher, copy, plaintext), new GroupKey(keys.manifestSeed(copy)));
		}
	}

	/* A group is recorded before placement, so a group that fails halfway is still withdrawn on rollback */
	private void placeGroup(final byte[] chunk, final GroupKey key) throws StorageException {
		placed.add(key);

		if (!distributor.place(ChunkGroups.seal(chunk, key, keys.format(), layout), windowOf(key))) {
			throw new StorageException("Too few gridnodes accepted the fragments of a group");
		}
	}

	private void rollback() {
		final long now = System.currentTimeMillis();

		placed.forEach(key -> distributor.withdraw(key, windowOf(key), now));
	}

	private List<Gridnode> windowOf(final GroupKey key) {
		return Placement.window(key.groupId(), gridnodes, parameters.window());
	}

	private List<Integer> shuffled(final int count) {
		final List<Integer> order = IntStream.range(0, count).boxed().collect(Collectors.toList());

		Collections.shuffle(order, random);
		return order;
	}

	private static byte[] seal(final ChunkCipher cipher, final long sequence, final byte[] plaintext)
		throws StorageException {

		try {
			return cipher.seal(sequence, plaintext);
		} catch (GeneralSecurityException ex) {
			throw new StorageException("Unable to encrypt a chunk", ex);
		}
	}
}
