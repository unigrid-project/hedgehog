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

package org.unigrid.hedgehog.proxy.chain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import org.unigrid.hedgehog.proxy.Padding;

final class ChainFixtures {
	static final int MAX_ENTRY_BYTES = 16 * 1024;

	/* The size of the entries signed() writes, the smallest class of every chain whose entries may be that large */
	static final int ENTRY_SIZE = SizeClasses.smallest(MAX_ENTRY_BYTES);

	/* The tag of the entries signed() writes, which no session looks out for */
	static final Tag TAG = new Tag(new byte[Tag.SIZE]);

	private ChainFixtures() {
		/* Static helpers only */
	}

	static Arbitrary<byte[]> bytes(int size) {
		return Arbitraries.bytes().array(byte[].class).ofSize(size);
	}

	static Arbitrary<byte[]> payloads() {
		return Arbitraries.bytes().array(byte[].class).ofMaxSize(300);
	}

	static Arbitrary<EntryHeader> headers() {
		return Combinators.combine(bytes(ChainId.SIZE).map(ChainId::new), bytes(WriterId.SIZE).map(WriterId::new),
			Arbitraries.longs().greaterOrEqual(0), Arbitraries.longs(), Arbitraries.longs(),
			bytes(Tag.SIZE).map(Tag::new)).as(EntryHeader::new);
	}

	static Arbitrary<Entry> entries() {
		return Combinators.combine(headers(), payloads(), Arbitraries.longs(), bytes(Entry.SIGNATURE_SIZE))
			.as(Entry::new);
	}

	static Arbitrary<WriterKey> writerKeys() {
		return bytes(WriterKey.SEED_SIZE).map(WriterKey::new);
	}

	static Arbitrary<Entry> signedEntries() {
		return Combinators.combine(writerKeys(), headers(), payloads()).as((key, header, payload) -> key.sign(
			new EntryHeader(header.chainId(), key.id(), header.seq(), header.prev(), header.timestamp(),
				header.tag()), payload));
	}

	/* The bytes of the entries of a chain above its smallest class, which never take more than the capacity */
	static long largerBytes(Chain chain) {
		return chain.entries().stream().mapToLong(Entry::size)
			.filter(size -> size != chain.limits().smallestClassBytes()).sum();
	}

	static WriterKey key(int number) {
		final byte[] seed = new byte[WriterKey.SEED_SIZE];

		Arrays.fill(seed, (byte) number);
		return new WriterKey(seed);
	}

	static ChainId chainId(int number) {
		final byte[] bytes = new byte[ChainId.SIZE];

		Arrays.fill(bytes, (byte) number);
		return new ChainId(bytes);
	}

	/* An entry of ENTRY_SIZE bytes whose payload starts with the low byte of its sequence number */
	static Entry signed(WriterKey key, ChainId chainId, long seq, long prev, long timestamp) {
		return key.sign(new EntryHeader(chainId, key.id(), seq, prev, timestamp, TAG),
			Padding.padded(new byte[] { (byte) seq }, MAX_ENTRY_BYTES));
	}

	/* A feed of count entries that each link to the one before, one second apart */
	static List<Entry> linked(WriterKey key, ChainId chainId, int count, long timestamp) {
		final List<Entry> entries = new ArrayList<>();
		long prev = 0;

		for (int seq = 0; seq < count; seq++) {
			final Entry entry = signed(key, chainId, seq, prev, timestamp + seq * Entry.STAMP_MILLIS);

			entries.add(entry);
			prev = entry.hash();
		}

		return entries;
	}
}
