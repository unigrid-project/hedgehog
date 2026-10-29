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
import java.util.Arrays;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class EntryCodecTest {
	private static final int SMALL_LIMIT = Entry.OVERHEAD + 10;

	@Provide
	public Arbitrary<Entry> entries() {
		return ChainFixtures.entries();
	}

	@Property(tries = 200)
	public void shouldDecodeWhatItEncoded(@ForAll("entries") Entry entry) throws InvalidEntryException {
		final byte[] bytes = EntryCodec.encode(entry);

		assertThat(bytes.length, is(entry.size()));
		assertThat(EntryCodec.decode(bytes, ChainFixtures.MAX_ENTRY_BYTES), equalTo(entry));
	}

	@Property(tries = 100)
	public void shouldFollowTheWireLayout(@ForAll("entries") Entry entry) {
		final EntryHeader header = entry.header();
		final ByteBuffer bytes = ByteBuffer.wrap(EntryCodec.encode(entry));
		final int payloadEnd = Entry.HEADER_SIZE + entry.payload().length;

		assertThat(Arrays.copyOfRange(bytes.array(), 0, 32), equalTo(header.chainId().bytes()));
		assertThat(Arrays.copyOfRange(bytes.array(), 32, 64), equalTo(header.writer().bytes()));
		assertThat(bytes.getLong(64), is(header.seq()));
		assertThat(bytes.getLong(72), is(header.prev()));
		assertThat(bytes.getLong(80), is(header.timestamp()));
		assertThat(Arrays.copyOfRange(bytes.array(), 88, 104), equalTo(header.tag().bytes()));
		assertThat(bytes.get(104), is((byte) (header.last() ? 1 : 0)));
		assertThat(bytes.getInt(105), is(entry.payload().length));
		assertThat(Arrays.copyOfRange(bytes.array(), 109, payloadEnd), equalTo(entry.payload()));
		assertThat(bytes.getLong(payloadEnd), is(entry.hash()));
		assertThat(Arrays.copyOfRange(bytes.array(), payloadEnd + 8, entry.size()), equalTo(entry.signature()));
	}

	@Property(tries = 100)
	public void shouldReadEntriesBackToBack(@ForAll("entries") Entry first, @ForAll("entries") Entry second)
		throws InvalidEntryException {
		final ByteBuffer in = ByteBuffer.allocate(first.size() + second.size())
			.put(EntryCodec.encode(first)).put(EntryCodec.encode(second)).flip();

		assertThat(EntryCodec.decode(in, ChainFixtures.MAX_ENTRY_BYTES), equalTo(first));
		assertThat(EntryCodec.decode(in, ChainFixtures.MAX_ENTRY_BYTES), equalTo(second));
		assertThat(in.hasRemaining(), is(false));
	}

	@Property(tries = 50)
	public void shouldRefuseBytesAfterTheEntry(@ForAll("entries") Entry entry) {
		final byte[] bytes = Arrays.copyOf(EntryCodec.encode(entry), entry.size() + 1);

		final InvalidEntryException thrown = Expect.assertThrows(InvalidEntryException.class,
			() -> EntryCodec.decode(bytes, ChainFixtures.MAX_ENTRY_BYTES));

		assertThat(thrown.getMessage(), is("Found 1 bytes after the entry"));
	}

	/* Nothing in the clear tells an opening, an answer or a message apart: no kind, and a tag that only the ends of
	   the session know. The one mark in the clear says whether the entry is the last of its feed. */
	@Example
	public void shouldCarryNoKindAndNoFingerprint() {
		assertThat(Entry.HEADER_SIZE, is(ChainId.SIZE + WriterId.SIZE + 3 * Long.BYTES + Tag.SIZE + Byte.BYTES
			+ Integer.BYTES));
		assertThat(Entry.OVERHEAD, is(181));
	}

	/* A mark that no writer sets is no entry anybody wrote */
	@Property(tries = 50)
	public void shouldRefuseAMarkItDoesNotKnow(@ForAll("entries") Entry entry, @ForAll byte flags) {
		final byte[] bytes = EntryCodec.encode(entry);
		final byte unknown = (byte) (flags & ~1) == 0 ? 2 : flags;

		bytes[104] = unknown;

		final InvalidEntryException thrown = Expect.assertThrows(InvalidEntryException.class,
			() -> EntryCodec.decode(bytes, ChainFixtures.MAX_ENTRY_BYTES));

		assertThat(thrown.getMessage(), is("Unknown marks " + Integer.toHexString(unknown & 0xFF)));
	}

	@Example
	public void shouldAcceptAnEntryAtTheLimit() throws InvalidEntryException {
		final Entry entry = ChainFixtures.entries().sample();
		final Entry atLimit = new Entry(entry.header(), new byte[10], entry.hash(), entry.signature());

		assertThat(EntryCodec.decode(EntryCodec.encode(atLimit), SMALL_LIMIT), equalTo(atLimit));
	}

	@Example
	public void shouldRefuseAnEntryOverTheLimit() {
		final Entry entry = ChainFixtures.entries().sample();
		final Entry overLimit = new Entry(entry.header(), new byte[11], entry.hash(), entry.signature());

		final InvalidEntryException thrown = Expect.assertThrows(InvalidEntryException.class,
			() -> EntryCodec.decode(EntryCodec.encode(overLimit), SMALL_LIMIT));

		assertThat(thrown.getMessage(), is("An entry of 192 bytes exceeds the limit of 191 bytes"));
	}

	@Property(tries = 20)
	public void shouldRefuseAHugeLengthBeforeReadingThePayload(@ForAll("entries") Entry entry) {
		for (int length : new int[] { Integer.MAX_VALUE, -1, Integer.MIN_VALUE }) {
			final byte[] bytes = EntryCodec.encode(entry);

			ByteBuffer.wrap(bytes).putInt(105, length);
			Expect.assertThrows(InvalidEntryException.class,
				() -> EntryCodec.decode(bytes, ChainFixtures.MAX_ENTRY_BYTES));
		}
	}
}
