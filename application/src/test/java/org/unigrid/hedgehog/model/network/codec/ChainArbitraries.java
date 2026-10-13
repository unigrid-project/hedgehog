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

package org.unigrid.hedgehog.model.network.codec;

import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryHeader;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.chain.WriterKey;

public final class ChainArbitraries {
	public static final int MAX_PAYLOAD_BYTES = 300;

	private ChainArbitraries() {
		/* Static helpers only */
	}

	public static Arbitrary<byte[]> bytes(final int size) {
		return Arbitraries.bytes().array(byte[].class).ofSize(size);
	}

	public static Arbitrary<ChainId> chainIds() {
		return bytes(ChainId.SIZE).map(ChainId::new);
	}

	public static Arbitrary<WriterId> writers() {
		return bytes(WriterId.SIZE).map(WriterId::new);
	}

	public static Arbitrary<Head> head() {
		return Combinators.combine(Arbitraries.longs().greaterOrEqual(0), Arbitraries.longs()).as(Head::new);
	}

	public static Arbitrary<Map<WriterId, Head>> heads() {
		return Arbitraries.maps(writers(), head()).ofMaxSize(40);
	}

	public static Arbitrary<Entry> entries() {
		return Combinators.combine(bytes(WriterKey.SEED_SIZE).map(WriterKey::new), chainIds(),
			Arbitraries.longs().greaterOrEqual(0), Arbitraries.longs(), Arbitraries.longs(),
			bytes(Fingerprint.SIZE).map(Fingerprint::new), Arbitraries.of(EntryKind.class),
			Arbitraries.bytes().array(byte[].class).ofMaxSize(MAX_PAYLOAD_BYTES))
			.as((key, chainId, seq, prev, timestamp, fingerprint, kind, payload) -> key.sign(
				new EntryHeader(chainId, key.id(), seq, prev, timestamp, fingerprint, kind), payload));
	}

	public static Arbitrary<List<Entry>> entryLists() {
		return entries().list().ofMaxSize(8);
	}
}
