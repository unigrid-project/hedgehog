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
import java.util.Optional;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.statistics.Statistics;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class EntrySignatureTest {
	@Provide
	public Arbitrary<Entry> signedEntries() {
		return ChainFixtures.signedEntries();
	}

	@Provide
	public Arbitrary<WriterKey> writerKeys() {
		return ChainFixtures.writerKeys();
	}

	@Provide
	public Arbitrary<byte[]> payloads() {
		return ChainFixtures.payloads();
	}

	@Property(tries = 100)
	public void shouldTrustWhatTheWriterSigned(@ForAll("signedEntries") Entry entry) {
		assertThat(entry.isAuthentic(), is(true));
	}

	@Property(tries = 100)
	public void shouldHashTheBytesFromTheChainIdToTheEndOfThePayload(@ForAll("signedEntries") Entry entry) {
		final byte[] body = Arrays.copyOf(EntryCodec.encode(entry), entry.size() - Entry.TRAILER_SIZE);

		assertThat(entry.hash(), is(FnvJ64.hash(body)));
	}

	@Property(tries = 100)
	public void shouldSignTheBodyAndTheHash(@ForAll("signedEntries") Entry entry) {
		final byte[] signed = Arrays.copyOf(EntryCodec.encode(entry), entry.size() - Entry.SIGNATURE_SIZE);

		assertThat(entry.header().writer().verify(signed, entry.signature()), is(true));
	}

	@Property(tries = 300)
	public void shouldNotTrustAChangedByte(@ForAll("signedEntries") Entry entry, @ForAll int position,
		@ForAll byte delta) {
		final byte[] bytes = EntryCodec.encode(entry);

		bytes[Math.floorMod(position, bytes.length)] += delta == 0 ? 1 : delta;

		final Optional<Entry> decoded = decode(bytes);

		Statistics.collect(decoded.isPresent());
		Statistics.coverage(coverage -> coverage.check(true).percentage(percentage -> percentage > 80.0));
		decoded.ifPresent(tampered -> assertThat(tampered.isAuthentic(), is(false)));
	}

	@Property(tries = 50)
	public void shouldNotTrustASignedHashThatDoesNotMatch(@ForAll("signedEntries") Entry entry,
		@ForAll("writerKeys") WriterKey key) {
		final EntryHeader header = new EntryHeader(entry.header().chainId(), key.id(), entry.header().seq(),
			entry.header().prev(), entry.header().timestamp(), entry.header().fingerprint(), entry.header().kind());
		final byte[] body = EntryCodec.body(header, entry.payload());
		final long wrongHash = FnvJ64.hash(body) + 1;
		final Entry forged = new Entry(header, entry.payload(), wrongHash,
			key.signature(EntryCodec.signed(body, wrongHash)));

		assertThat(forged.header().writer().verify(EntryCodec.signed(body, wrongHash), forged.signature()), is(true));
		assertThat(forged.isAuthentic(), is(false));
	}

	@Property(tries = 50)
	public void shouldNotTrustAnotherWritersSignature(@ForAll("signedEntries") Entry entry,
		@ForAll("writerKeys") WriterKey other) {
		Assume.that(!other.id().equals(entry.header().writer()));

		final EntryHeader header = entry.header();
		final EntryHeader stolen = new EntryHeader(header.chainId(), other.id(), header.seq(), header.prev(),
			header.timestamp(), header.fingerprint(), header.kind());

		assertThat(rehashed(stolen, entry.payload(), entry.signature()).isAuthentic(), is(false));
	}

	@Property(tries = 50)
	public void shouldNotTrustAChangedSequenceNumberWithARecomputedHash(@ForAll("signedEntries") Entry entry,
		@ForAll @LongRange(min = 0) long seq) {
		Assume.that(seq != entry.header().seq());

		final EntryHeader header = entry.header();
		final EntryHeader changed = new EntryHeader(header.chainId(), header.writer(), seq, header.prev(),
			header.timestamp(), header.fingerprint(), header.kind());

		assertThat(rehashed(changed, entry.payload(), entry.signature()).isAuthentic(), is(false));
	}

	@Property(tries = 50)
	public void shouldNotTrustAChangedPayloadWithARecomputedHash(@ForAll("signedEntries") Entry entry,
		@ForAll("payloads") byte[] payload) {
		Assume.that(!Arrays.equals(payload, entry.payload()));

		assertThat(rehashed(entry.header(), payload, entry.signature()).isAuthentic(), is(false));
	}

	@Property(tries = 20)
	public void shouldRefuseToSignForAnotherWriter(@ForAll("signedEntries") Entry entry,
		@ForAll("writerKeys") WriterKey key) {
		Assume.that(!key.id().equals(entry.header().writer()));
		Expect.assertThrows(IllegalArgumentException.class, () -> key.sign(entry.header(), entry.payload()));
	}

	/* The hash is unkeyed, so whoever changes an entry can recompute it: only the signature can refuse that */
	private static Entry rehashed(EntryHeader header, byte[] payload, byte[] signature) {
		return new Entry(header, payload, FnvJ64.hash(EntryCodec.body(header, payload)), signature);
	}

	private static Optional<Entry> decode(byte[] bytes) {
		try {
			return Optional.of(EntryCodec.decode(bytes, ChainFixtures.MAX_ENTRY_BYTES));
		} catch (InvalidEntryException refused) {
			/* A changed kind, sequence number or length is refused before the signature is looked at */
			return Optional.empty();
		}
	}
}
