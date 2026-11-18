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

package org.unigrid.hedgehog.model.spork;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.Arrays;
import java.util.Set;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.model.spork.LegacyWhiteList.SporkData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.model.spork.LegacyWhiteListTest.distinctHashes;
import static org.unigrid.hedgehog.model.spork.LegacyWhiteListTest.with;

/** The legacy whitelist on the wire: a two-byte count, then the hash160s in ascending order. */
public class LegacyWhiteListIntegrityTest {
	private static final int COUNT_BYTES = 2;

	/** Lists of hashes well within the cap. */
	@Provide
	public Arbitrary<Set<String>> sets() {
		return LegacyWhiteListTest.cappedSets();
	}

	/** The list as the encoder writes it. */
	@SneakyThrows
	private static ByteBuf encoded(SporkData data) {
		final ByteBuf buffer = Unpooled.buffer();

		new LegacyWhiteListEncoder().encodeChunk(null, data, buffer);
		return buffer;
	}

	/** The list as the decoder reads it back. */
	@SneakyThrows
	private static SporkData decoded(ByteBuf buffer) {
		return new LegacyWhiteListDecoder().decodeChunk(null, buffer).orElseThrow();
	}

	/** A list is read back as it was written, in two bytes for the count and twenty per address. */
	@Property
	public void survivesARoundTripAtTwentyBytesPerAddress(@ForAll("sets") Set<String> addresses) {
		final ByteBuf buffer = encoded(with(addresses));

		assertThat(buffer.readableBytes(), equalTo(COUNT_BYTES + SporkData.HASH_BYTES * addresses.size()));
		assertThat(decoded(buffer).getApprovedLegacyAddresses(), equalTo(addresses));
		assertThat(buffer.readableBytes(), equalTo(0));
	}

	/** A list as long as the cap is read back as it was written. */
	@Example
	public void survivesARoundTripAtTheCap() {
		final Set<String> addresses = distinctHashes(SporkData.MAX_ENTRIES);

		assertThat(decoded(encoded(with(addresses))).getApprovedLegacyAddresses(), equalTo(addresses));
	}

	/** An empty list is a count of zero and nothing else. */
	@Example
	public void writesNothingButACountForAnEmptyList() {
		final ByteBuf buffer = encoded(new SporkData());

		assertThat(ByteBufUtil.hexDump(buffer), equalTo("0000"));
		assertThat(decoded(buffer).getApprovedLegacyAddresses(), equalTo(Set.of()));
	}

	/** The hashes are written in ascending order, so that equal lists are always the same bytes. */
	@Property
	public void writesTheAddressesInAscendingOrder(@ForAll("sets") Set<String> addresses) {
		final ByteBuf buffer = encoded(with(addresses));
		byte[] previous = null;

		buffer.skipBytes(COUNT_BYTES);

		while (buffer.isReadable()) {
			final byte[] hash = new byte[SporkData.HASH_BYTES];

			buffer.readBytes(hash);

			if (previous != null) {
				assertThat(Arrays.compareUnsigned(previous, hash), lessThan(0));
			}

			previous = hash;
		}
	}

	/** A count above the cap is refused before any hash is read. */
	@Property(tries = 20)
	public void refusesACountAboveTheCapBeforeReadingAnyAddress(
		@ForAll @IntRange(min = SporkData.MAX_ENTRIES + 1, max = 0xFFFF) int count) {

		final ByteBuf buffer = Unpooled.buffer().writeShort(count);

		assertThat(assertThrows(CorruptedFrameException.class, () -> decoded(buffer)).getMessage(),
			startsWith("A legacy whitelist of " + count));
	}

	/** A list too long or with an entry that is no hash is never written, so it is never signed. */
	@Example
	public void refusesToWriteWhatItsPeersWouldNotAccept() {
		assertThrows(IllegalArgumentException.class, () -> encoded(with(distinctHashes(SporkData.MAX_ENTRIES + 1))));
		assertThrows(IllegalArgumentException.class, () -> encoded(with(Set.of("A".repeat(40)))));
	}
}
