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

import java.util.Arrays;
import java.util.Objects;

public final class Entry {
	public static final int SIGNATURE_SIZE = 64;
	public static final int HEADER_SIZE = ChainId.SIZE + WriterId.SIZE + 3 * Long.BYTES + Fingerprint.SIZE
		+ Byte.BYTES + Integer.BYTES;
	public static final int TRAILER_SIZE = Long.BYTES + SIGNATURE_SIZE;
	public static final int OVERHEAD = HEADER_SIZE + TRAILER_SIZE;

	private final EntryHeader header;
	private final byte[] payload;
	private final long hash;
	private final byte[] signature;

	Entry(EntryHeader header, byte[] payload, long hash, byte[] signature) {
		if (signature.length != SIGNATURE_SIZE) {
			throw new IllegalArgumentException("A signature is exactly " + SIGNATURE_SIZE + " bytes, found "
				+ signature.length);
		}

		this.header = Objects.requireNonNull(header, "header");
		this.payload = payload.clone();
		this.hash = hash;
		this.signature = signature.clone();
	}

	public EntryHeader header() {
		return header;
	}

	public byte[] payload() {
		return payload.clone();
	}

	public long hash() {
		return hash;
	}

	public byte[] signature() {
		return signature.clone();
	}

	public int size() {
		return OVERHEAD + payload.length;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Entry entry && header.equals(entry.header) && hash == entry.hash
			&& Arrays.equals(payload, entry.payload) && Arrays.equals(signature, entry.signature);
	}

	@Override
	public int hashCode() {
		return 31 * header.hashCode() + Long.hashCode(hash);
	}

	@Override
	public String toString() {
		return header.kind() + " " + header.seq() + " of " + header.writer();
	}
}
