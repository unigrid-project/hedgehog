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

import java.util.Arrays;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class EntryTest {
	@Provide
	public Arbitrary<EntryHeader> headers() {
		return ChainFixtures.headers();
	}

	@Provide
	public Arbitrary<Entry> entries() {
		return ChainFixtures.entries();
	}

	@Example
	public void shouldFollowTheWireLayoutSizes() {
		assertThat(Entry.HEADER_SIZE, is(108));
		assertThat(Entry.TRAILER_SIZE, is(72));
		assertThat(Entry.OVERHEAD, is(180));
	}

	@Property(tries = 100)
	public void shouldKeepItsOwnCopies(@ForAll("headers") EntryHeader header, @ForAll byte[] payload,
		@ForAll long hash, @ForAll @Size(Entry.SIGNATURE_SIZE) byte[] signature) {
		final byte[] originalPayload = payload.clone();
		final byte[] originalSignature = signature.clone();
		final Entry entry = new Entry(header, payload, hash, signature);

		Arrays.fill(payload, (byte) 1);
		Arrays.fill(signature, (byte) 1);
		Arrays.fill(entry.payload(), (byte) 2);
		Arrays.fill(entry.signature(), (byte) 2);
		assertThat(entry.payload(), equalTo(originalPayload));
		assertThat(entry.signature(), equalTo(originalSignature));
	}

	@Property(tries = 100)
	public void shouldMeasureItsEncodedSize(@ForAll("entries") Entry entry) {
		assertThat(entry.size(), is(Entry.OVERHEAD + entry.payload().length));
	}

	@Property(tries = 100)
	public void shouldEqualACopy(@ForAll("entries") Entry entry) {
		final Entry copy = new Entry(entry.header(), entry.payload(), entry.hash(), entry.signature());

		assertThat(copy, equalTo(entry));
		assertThat(copy.hashCode(), equalTo(entry.hashCode()));
	}

	@Property(tries = 100)
	public void shouldNotEqualAnEntryWithAnotherHash(@ForAll("entries") Entry entry) {
		final Entry other = new Entry(entry.header(), entry.payload(), entry.hash() + 1, entry.signature());

		assertThat(other.equals(entry), is(false));
	}

	@Property(tries = 50)
	public void shouldRefuseASignatureOfAnotherSize(@ForAll("headers") EntryHeader header,
		@ForAll @Size(max = 100) byte[] signature) {
		Assume.that(signature.length != Entry.SIGNATURE_SIZE);
		Expect.assertThrows(IllegalArgumentException.class, () -> new Entry(header, new byte[0], 0, signature));
	}

	@Property(tries = 50)
	public void shouldRefuseANegativeSequenceNumber(@ForAll("headers") EntryHeader header, @ForAll long seq) {
		Assume.that(seq < 0);
		Expect.assertThrows(IllegalArgumentException.class, () -> new EntryHeader(header.chainId(),
			header.writer(), seq, header.prev(), header.timestamp(), header.tag()));
	}

	@Property(tries = 20)
	public void shouldRefuseAMissingTag(@ForAll("headers") EntryHeader header) {
		final NullPointerException thrown = Expect.assertThrows(NullPointerException.class, () -> new EntryHeader(
			header.chainId(), header.writer(), header.seq(), header.prev(), header.timestamp(), null));

		assertThat(thrown.getMessage(), is("tag"));
	}

	@Property(tries = 20)
	public void shouldNameItsSequenceNumberAndWriter(@ForAll("entries") Entry entry) {
		assertThat(entry.toString(), is("Entry " + entry.header().seq() + " of " + entry.header().writer()));
	}
}
