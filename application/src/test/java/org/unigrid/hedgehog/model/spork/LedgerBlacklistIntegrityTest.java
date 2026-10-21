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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist.SporkData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.model.spork.LedgerBlacklistTest.distinctHashes;
import static org.unigrid.hedgehog.model.spork.LedgerBlacklistTest.with;

public class LedgerBlacklistIntegrityTest {
	private static final int COUNT_BYTES = 2;

	@Provide
	public Arbitrary<Set<String>> sets() {
		return LedgerBlacklistTest.cappedSets();
	}

	@SneakyThrows
	private static ByteBuf encoded(SporkData data) {
		final ByteBuf buffer = Unpooled.buffer();

		new LedgerBlacklistEncoder().encodeChunk(null, data, buffer);
		return buffer;
	}

	@SneakyThrows
	private static SporkData decoded(ByteBuf buffer) {
		return new LedgerBlacklistDecoder().decodeChunk(null, buffer).orElseThrow();
	}

	private static ByteBuf countOf(int count) {
		return Unpooled.buffer().writeShort(count);
	}

	@Property
	public void survivesARoundTripAtTwentyBytesPerAddress(@ForAll("sets") Set<String> addresses) {
		final ByteBuf buffer = encoded(with(addresses));

		assertThat(buffer.readableBytes(), equalTo(COUNT_BYTES + SporkData.HASH_BYTES * addresses.size()));
		assertThat(decoded(buffer).getLegacyAddresses(), equalTo(addresses));
		assertThat(buffer.readableBytes(), equalTo(0));
	}

	@Example
	public void survivesARoundTripAtTheCap() {
		final Set<String> addresses = distinctHashes(SporkData.MAX_ENTRIES);
		final ByteBuf buffer = encoded(with(addresses));

		assertThat(decoded(buffer).getLegacyAddresses(), equalTo(addresses));
		assertThat(buffer.readableBytes(), equalTo(0));
	}

	@Example
	public void writesNothingButACountForAnEmptyList() {
		final ByteBuf buffer = encoded(new SporkData());

		assertThat(ByteBufUtil.hexDump(buffer), equalTo("0000"));
		assertThat(decoded(buffer).getLegacyAddresses(), equalTo(Set.of()));
	}

	@Property
	public void writesTheSameBytesWhateverTheOrderOfTheSet(@ForAll("sets") Set<String> addresses) {
		final List<String> shuffled = new ArrayList<>(addresses);
		final Set<String> reversed = new TreeSet<>(Collections.reverseOrder());

		Collections.shuffle(shuffled);
		reversed.addAll(addresses);

		final String bytes = ByteBufUtil.hexDump(encoded(with(new TreeSet<>(addresses))));

		assertThat(ByteBufUtil.hexDump(encoded(with(new HashSet<>(shuffled)))), equalTo(bytes));
		assertThat(ByteBufUtil.hexDump(encoded(with(reversed))), equalTo(bytes));
	}

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

	@Property
	public void refusesACountAboveTheCapBeforeReadingAnyAddress(
		@ForAll @IntRange(min = SporkData.MAX_ENTRIES + 1, max = 0xFFFF) int count) {

		final ByteBuf buffer = countOf(count);

		assertThrows(CorruptedFrameException.class, () -> decoded(buffer));
		assertThat(buffer.readableBytes(), equalTo(0));
	}

	@Example
	public void refusesACountAboveTheCapEvenWhenAllTheAddressesFollow() {
		final ByteBuf buffer = countOf(SporkData.MAX_ENTRIES + 1);

		distinctHashes(SporkData.MAX_ENTRIES + 1).stream().sorted()
			.forEach(hash -> buffer.writeBytes(ByteBufUtil.decodeHexDump(hash)));

		assertThat(assertThrows(CorruptedFrameException.class, () -> decoded(buffer)).getMessage(),
			startsWith("A blacklist of " + (SporkData.MAX_ENTRIES + 1)));
	}

	@Example
	public void refusesToWriteWhatItsPeersWouldNotAccept() {
		assertThrows(IllegalArgumentException.class, () -> encoded(with(distinctHashes(SporkData.MAX_ENTRIES + 1))));
		assertThrows(IllegalArgumentException.class, () -> encoded(with(Set.of("A".repeat(40)))));
	}
}
