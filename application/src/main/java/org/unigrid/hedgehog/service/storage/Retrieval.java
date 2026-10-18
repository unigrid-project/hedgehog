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
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.ChunkGroups;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.Manifest;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import org.unigrid.hedgehog.model.storage.StorageLayout;
import org.unigrid.hedgehog.model.storage.ChunkCipher;
import org.unigrid.hedgehog.model.storage.FingerprintKeys;
import org.unigrid.hedgehog.model.storage.GroupKey;
import org.unigrid.hedgehog.model.storage.erasure.ReedSolomon;
import org.unigrid.hedgehog.model.storage.placement.Placement;

public final class Retrieval {
	private final FingerprintKeys keys;
	private final Manifest manifest;
	private final StorageLayout layout;
	private final List<Gridnode> gridnodes;
	private final GroupFetcher fetcher;
	private final int window;
	private Optional<List<byte[]>> first = Optional.empty();

	private Retrieval(final FingerprintKeys keys, final Manifest manifest, final List<Gridnode> gridnodes,
		final GroupFetcher fetcher, final int placementSlack) {

		this.keys = keys;
		this.manifest = manifest;
		this.layout = StorageLayout.of(manifest.getLayout(), manifest.getFileSize());
		this.gridnodes = gridnodes;
		this.fetcher = fetcher;
		this.window = manifest.getLayout().maxFragments() + placementSlack;
	}

	/* The file's copy count and layout are unknown until its manifest is read, and a later spork may have changed
	   both. So every copy that may exist is first looked for in the current window, and only then does the lookup
	   widen to every ranked gridnode. */
	static Retrieval open(final FingerprintKeys keys, final StorageSpork.SporkData current,
		final List<Gridnode> gridnodes, final GroupFetcher fetcher) throws FingerprintNotFoundException {

		final int expectedData = current.layout().dataFragments();
		final Manifest manifest = find(keys, gridnodes, fetcher, expectedData,
			StorageSpork.SporkData.MAX_MANIFEST_COPIES, current.window())
			.or(() -> find(keys, gridnodes, fetcher, expectedData, current.getManifestCopies(),
				ReedSolomon.MAX_SHARDS + current.getPlacementSlack()))
			.orElseThrow(FingerprintNotFoundException::new);

		return new Retrieval(keys, manifest, gridnodes, fetcher, current.getPlacementSlack());
	}

	private static Optional<Manifest> find(final FingerprintKeys keys, final List<Gridnode> gridnodes,
		final GroupFetcher fetcher, final int expectedData, final int copies, final int width) {

		final ChunkCipher cipher = ChunkCipher.forManifest(keys);

		for (int copy = 0; copy < copies; copy++) {
			final GroupId groupId = new GroupKey(keys.manifestSeed(copy)).groupId();
			final Optional<Manifest> manifest = readManifest(cipher, copy, keys.format(), fetcher.fetch(groupId,
				Placement.window(groupId, gridnodes, width), expectedData, keys.format()));

			if (manifest.isPresent()) {
				return manifest;
			}
		}

		return Optional.empty();
	}

	public long size() {
		return manifest.getFileSize();
	}

	/* Recovers the first stripe up front, so a file lost from its start fails before a caller commits to an answer
	   rather than partway through the bytes it sends */
	Retrieval prepare() throws DataLossException {
		if (layout.stripes() > 0) {
			first = Optional.of(plaintexts(ChunkCipher.forChunks(keys), 0));
		}

		return this;
	}

	public void writeTo(final OutputStream output) throws IOException, StorageException {
		final ChunkCipher cipher = ChunkCipher.forChunks(keys);
		long remaining = manifest.getFileSize();

		for (int stripe = 0; stripe < layout.stripes(); stripe++) {
			for (final byte[] plaintext : preparedOr(cipher, stripe)) {
				final int length = (int) Math.min(plaintext.length, remaining);

				output.write(plaintext, 0, length);
				remaining -= length;
			}
		}
	}

	/* Hands the prepared stripe over only once, so it is released before the next stripe is recovered */
	private List<byte[]> preparedOr(final ChunkCipher cipher, final int stripe) throws DataLossException {
		final Optional<List<byte[]>> prepared = stripe == 0 ? first : Optional.empty();

		first = Optional.empty();
		return prepared.isPresent() ? prepared.get() : plaintexts(cipher, stripe);
	}

	private List<byte[]> plaintexts(final ChunkCipher cipher, final int stripe) throws DataLossException {
		final byte[][] chunks = stripe(stripe);
		final List<byte[]> plaintexts = new ArrayList<>();

		for (int i = 0; i < layout.dataChunksIn(stripe); i++) {
			plaintexts.add(open(cipher, layout.firstSequenceOf(stripe) + i, chunks[i], stripe));
		}

		return plaintexts;
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
		fetchRange(stripe, 0, dataChunks, chunks, present);

		if (countOf(present) == dataChunks) {
			return chunks;
		}

		fetchRange(stripe, dataChunks, total, chunks, present);

		if (countOf(present) < dataChunks) {
			throw new DataLossException(stripe);
		}

		return new ReedSolomon(dataChunks, total - dataChunks).decode(chunks, present);
	}

	/* Stops as soon as the stripe has enough chunks, so parity groups are only fetched while still needed */
	private void fetchRange(final int stripe, final int from, final int to, final byte[][] chunks,
		final boolean[] present) {

		for (int index = from; index < to && countOf(present) < layout.dataChunksIn(stripe); index++) {
			final Optional<byte[]> chunk = chunk(new GroupKey(keys.chunkSeed(stripe, index)).groupId());

			if (chunk.isPresent()) {
				chunks[index] = chunk.get();
				present[index] = true;
			}
		}
	}

	private static int countOf(final boolean[] present) {
		return (int) IntStream.range(0, present.length).filter(i -> present[i]).count();
	}

	/* Only the owner can sign a group, yet a group sealed twice or with another layout must still count as missing
	   rather than break the stripe it belongs to */
	private Optional<byte[]> chunk(final GroupId groupId) {
		final List<Fragment> fragments = fetcher.fetch(groupId, Placement.window(groupId, gridnodes, window),
			manifest.getLayout().dataFragments(), keys.format());

		if (!GroupFetcher.isComplete(fragments)) {
			return Optional.empty();
		}

		try {
			return Optional.of(ChunkGroups.open(fragments))
				.filter(chunk -> chunk.length == manifest.getLayout().getChunkSize());
		} catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	/* A manifest that decrypts yet describes a file no layout can address, or claims a format other than the
	   fingerprint's, counts as unreadable, like a lost copy */
	private static Optional<Manifest> readManifest(final ChunkCipher cipher, final int copy, final StorageFormat format,
		final List<Fragment> fragments) {

		if (!GroupFetcher.isComplete(fragments)) {
			return Optional.empty();
		}

		try {
			final Manifest manifest = Manifest.decode(cipher.open(copy, ChunkGroups.open(fragments)));

			StorageLayout.of(manifest.getLayout(), manifest.getFileSize());
			return Optional.of(manifest).filter(read -> read.getFormat() == format);
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
