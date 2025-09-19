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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.ChunkGroups;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.Manifest;
import org.unigrid.hedgehog.model.storage.StorageLayout;
import org.unigrid.hedgehog.model.storage.crypto.ChunkCipher;
import org.unigrid.hedgehog.model.storage.crypto.FingerprintKeys;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
import org.unigrid.hedgehog.model.storage.erasure.ReedSolomon;
import org.unigrid.hedgehog.model.storage.placement.Placement;

public final class Retrieval {
	private final FingerprintKeys keys;
	private final Manifest manifest;
	private final StorageLayout layout;
	private final List<Gridnode> gridnodes;
	private final GroupFetcher fetcher;
	private final int window;

	private Retrieval(final FingerprintKeys keys, final Manifest manifest, final List<Gridnode> gridnodes,
		final GroupFetcher fetcher, final int placementSlack) {

		this.keys = keys;
		this.manifest = manifest;
		this.layout = StorageLayout.of(manifest.getLayout(), manifest.getFileSize());
		this.gridnodes = gridnodes;
		this.fetcher = fetcher;
		this.window = manifest.getLayout().maxFragments() + placementSlack;
	}

	/* The manifest's own layout is unknown until it is read, so its lookup may widen to every ranked gridnode. */
	static Retrieval open(final FingerprintKeys keys, final StorageSpork.SporkData current,
		final List<Gridnode> gridnodes, final GroupFetcher fetcher) throws FingerprintNotFoundException {

		final ChunkCipher cipher = ChunkCipher.forManifest(keys);

		for (int copy = 0; copy < current.getManifestCopies(); copy++) {
			final GroupId groupId = new GroupKey(keys.manifestSeed(copy)).groupId();
			final List<Gridnode> candidates = Placement.window(groupId, gridnodes,
				ReedSolomon.MAX_SHARDS + current.getPlacementSlack());
			final Optional<Manifest> manifest = readManifest(cipher, copy,
				fetcher.fetch(groupId, candidates, current.layout().dataFragments(), keys.format()));

			if (manifest.isPresent()) {
				return new Retrieval(keys, manifest.get(), gridnodes, fetcher, current.getPlacementSlack());
			}
		}

		throw new FingerprintNotFoundException();
	}

	public long size() {
		return manifest.getFileSize();
	}

	public void writeTo(final OutputStream output) throws IOException, StorageException {
		final ChunkCipher cipher = ChunkCipher.forChunks(keys);
		long remaining = manifest.getFileSize();

		for (int stripe = 0; stripe < layout.stripes(); stripe++) {
			final byte[][] chunks = stripe(stripe);

			for (int i = 0; i < layout.dataChunksIn(stripe); i++) {
				final byte[] plaintext = open(cipher, layout.firstSequenceOf(stripe) + i, chunks[i], stripe);
				final int length = (int) Math.min(plaintext.length, remaining);

				output.write(plaintext, 0, length);
				remaining -= length;
			}
		}
	}

	void withdraw(final GroupDistributor distributor, final long timestamp) {
		for (int stripe = 0; stripe < layout.stripes(); stripe++) {
			for (int index = 0; index < layout.dataChunksIn(stripe) + layout.parityChunksIn(stripe); index++) {
				withdraw(distributor, new GroupKey(keys.chunkSeed(stripe, index)), timestamp);
			}
		}

		for (int copy = 0; copy < manifest.getManifestCopies(); copy++) {
			withdraw(distributor, new GroupKey(keys.manifestSeed(copy)), timestamp);
		}
	}

	private void withdraw(final GroupDistributor distributor, final GroupKey key, final long timestamp) {
		distributor.withdraw(key, Placement.window(key.groupId(), gridnodes, window), timestamp);
	}

	private byte[][] stripe(final int stripe) throws DataLossException {
		final int dataChunks = layout.dataChunksIn(stripe);
		final int total = dataChunks + layout.parityChunksIn(stripe);
		final byte[][] chunks = new byte[total][];
		final boolean[] present = new boolean[total];
		final int foundData = fetchRange(stripe, 0, dataChunks, chunks, present);

		if (foundData == dataChunks) {
			return chunks;
		}

		if (foundData + fetchRange(stripe, dataChunks, total, chunks, present) < dataChunks) {
			throw new DataLossException(stripe);
		}

		return new ReedSolomon(dataChunks, total - dataChunks).decode(chunks, present);
	}

	private int fetchRange(final int stripe, final int from, final int to, final byte[][] chunks,
		final boolean[] present) {

		int found = 0;

		for (int index = from; index < to; index++) {
			final Optional<byte[]> chunk = chunk(new GroupKey(keys.chunkSeed(stripe, index)).groupId());

			if (chunk.isPresent()) {
				chunks[index] = chunk.get();
				present[index] = true;
				found++;
			}
		}

		return found;
	}

	private Optional<byte[]> chunk(final GroupId groupId) {
		final List<Fragment> fragments = fetcher.fetch(groupId, Placement.window(groupId, gridnodes, window),
			manifest.getLayout().dataFragments(), keys.format());

		return GroupFetcher.isComplete(fragments) ? Optional.of(ChunkGroups.open(fragments)) : Optional.empty();
	}

	private static Optional<Manifest> readManifest(final ChunkCipher cipher, final int copy,
		final List<Fragment> fragments) {

		if (!GroupFetcher.isComplete(fragments)) {
			return Optional.empty();
		}

		try {
			return Optional.of(Manifest.decode(cipher.open(copy, ChunkGroups.open(fragments))));
		} catch (GeneralSecurityException | IllegalArgumentException | BufferUnderflowException ex) {
			return Optional.empty();
		}
	}

	private static byte[] open(final ChunkCipher cipher, final long sequence, final byte[] chunk, final int stripe)
		throws DataLossException {

		try {
			return cipher.open(sequence, chunk);
		} catch (GeneralSecurityException ex) {
			throw new DataLossException(stripe);
		}
	}
}
