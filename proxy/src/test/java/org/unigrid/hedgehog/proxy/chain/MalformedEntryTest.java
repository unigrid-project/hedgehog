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
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

public class MalformedEntryTest {
	@Provide
	public Arbitrary<Entry> entries() {
		return ChainFixtures.entries();
	}

	@Property(tries = 300)
	public void shouldRefuseEveryTruncation(@ForAll("entries") Entry entry, @ForAll int cut) {
		final byte[] encoded = EntryCodec.encode(entry);
		final byte[] truncated = Arrays.copyOf(encoded, Math.floorMod(cut, encoded.length));

		final InvalidEntryException thrown = Expect.assertThrows(InvalidEntryException.class,
			() -> EntryCodec.decode(truncated, ChainFixtures.MAX_ENTRY_BYTES));

		assertThat(thrown.getMessage(), startsWith("Truncated entry"));
	}

	@Property(tries = 50)
	public void shouldRefuseAPayloadLongerThanTheBytesThatFollow(@ForAll("entries") Entry entry) {
		final byte[] bytes = EntryCodec.encode(entry);

		ByteBuffer.wrap(bytes).putInt(104, entry.payload().length + Entry.SIGNATURE_SIZE + 1);

		final InvalidEntryException thrown = Expect.assertThrows(InvalidEntryException.class,
			() -> EntryCodec.decode(bytes, ChainFixtures.MAX_ENTRY_BYTES));

		assertThat(thrown.getMessage(), startsWith("Truncated entry"));
	}

	@Property(tries = 50)
	public void shouldRefuseANegativeSequenceNumber(@ForAll("entries") Entry entry) {
		final byte[] bytes = EntryCodec.encode(entry);

		bytes[64] |= (byte) 0x80;

		final InvalidEntryException thrown = Expect.assertThrows(InvalidEntryException.class,
			() -> EntryCodec.decode(bytes, ChainFixtures.MAX_ENTRY_BYTES));

		assertThat(thrown.getMessage(), startsWith("Negative sequence number"));
	}

	@Property(tries = 500)
	public void shouldOnlyEverRefuseGarbageAsInvalid(@ForAll @Size(max = 400) byte[] garbage) {
		try {
			EntryCodec.decode(garbage, ChainFixtures.MAX_ENTRY_BYTES);
		} catch (InvalidEntryException expected) {
			assertThat(expected.getMessage().isEmpty(), is(false));
		}
	}
}
