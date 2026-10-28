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

import java.nio.ByteBuffer;

public final class EntryCodec {
	private EntryCodec() {
		/* Static helpers only */
	}

	public static byte[] encode(Entry entry) {
		final byte[] body = body(entry.header(), entry.payload());

		return ByteBuffer.allocate(body.length + Entry.TRAILER_SIZE).put(body).putLong(entry.hash())
			.put(entry.signature()).array();
	}

	public static Entry decode(byte[] bytes, int maxEntryBytes) throws InvalidEntryException {
		final ByteBuffer in = ByteBuffer.wrap(bytes);
		final Entry entry = decode(in, maxEntryBytes);

		if (in.hasRemaining()) {
			throw new InvalidEntryException("Found " + in.remaining() + " bytes after the entry");
		}

		return entry;
	}

	public static Entry decode(ByteBuffer in, int maxEntryBytes) throws InvalidEntryException {
		requireRemaining(in, Entry.HEADER_SIZE);

		final EntryHeader header = header(in);
		final int length = payloadLength(in, maxEntryBytes);

		requireRemaining(in, length + Entry.TRAILER_SIZE);

		final byte[] payload = take(in, length);
		final long hash = in.getLong();

		return new Entry(header, payload, hash, take(in, Entry.SIGNATURE_SIZE));
	}

	static byte[] body(EntryHeader header, byte[] payload) {
		return ByteBuffer.allocate(Entry.HEADER_SIZE + payload.length)
			.put(header.chainId().bytes()).put(header.writer().bytes())
			.putLong(header.seq()).putLong(header.prev()).putLong(header.timestamp()).put(header.tag().bytes())
			.putInt(payload.length).put(payload).array();
	}

	/* FNVJ64 is not collision resistant, so the signature covers the body itself and not only its hash */
	static byte[] signed(byte[] body, long hash) {
		return ByteBuffer.allocate(body.length + Long.BYTES).put(body).putLong(hash).array();
	}

	private static EntryHeader header(ByteBuffer in) throws InvalidEntryException {
		final ChainId chainId = new ChainId(take(in, ChainId.SIZE));
		final WriterId writer = new WriterId(take(in, WriterId.SIZE));
		final long seq = seq(in);
		final long prev = in.getLong();
		final long timestamp = in.getLong();

		return new EntryHeader(chainId, writer, seq, prev, timestamp, new Tag(take(in, Tag.SIZE)));
	}

	private static long seq(ByteBuffer in) throws InvalidEntryException {
		final long seq = in.getLong();

		if (seq < 0) {
			throw new InvalidEntryException("Negative sequence number " + seq);
		}

		return seq;
	}

	/* The length is checked against the limit before anything is allocated for the payload */
	private static int payloadLength(ByteBuffer in, int maxEntryBytes) throws InvalidEntryException {
		final long length = Integer.toUnsignedLong(in.getInt());
		final long size = Entry.OVERHEAD + length;

		if (size > maxEntryBytes) {
			throw new InvalidEntryException("An entry of " + size + " bytes exceeds the limit of "
				+ maxEntryBytes + " bytes");
		}

		return (int) length;
	}

	private static void requireRemaining(ByteBuffer in, int size) throws InvalidEntryException {
		if (in.remaining() < size) {
			throw new InvalidEntryException("Truncated entry: needs " + size + " more bytes, found "
				+ in.remaining());
		}
	}

	private static byte[] take(ByteBuffer in, int size) {
		final byte[] bytes = new byte[size];

		in.get(bytes);
		return bytes;
	}
}
