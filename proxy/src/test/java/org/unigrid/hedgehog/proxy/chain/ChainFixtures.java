/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

final class ChainFixtures {
	static final int MAX_ENTRY_BYTES = 16 * 1024;

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
			bytes(Fingerprint.SIZE).map(Fingerprint::new), Arbitraries.of(EntryKind.class)).as(EntryHeader::new);
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
				header.fingerprint(), header.kind()), payload));
	}
}
