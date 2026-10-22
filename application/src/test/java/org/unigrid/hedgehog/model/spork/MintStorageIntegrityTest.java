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
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class MintStorageIntegrityTest {
	private static final int HEADER_BYTES = 8;
	private static final String ACCOUNT = "ab".repeat(32);

	private static SporkData with(Map<Location, BigDecimal> mints) {
		final SporkData data = new SporkData();

		data.setMints(mints);
		return data;
	}

	private static SporkData with(String address, int number) {
		return with(new HashMap<>(Map.of(new Location(new Address(address), number), BigDecimal.TEN)));
	}

	private static SporkData withMints(int count) {
		return with(IntStream.range(0, count).boxed().collect(Collectors.toMap(
			number -> new Location(new Address(ACCOUNT), number), number -> BigDecimal.ONE)));
	}

	@SneakyThrows
	private static ByteBuf encoded(SporkData data) {
		final ByteBuf buffer = Unpooled.buffer();

		new MintStorageEncoder().encodeChunk(null, data, buffer);
		return buffer;
	}

	@SneakyThrows
	private static SporkData decoded(ByteBuf buffer) {
		return new MintStorageDecoder().decodeChunk(null, buffer).orElseThrow();
	}

	@Property
	public void survivesARoundTripWithAnyNumber(@ForAll int number) {
		final SporkData data = with(ACCOUNT, number);
		final ByteBuf buffer = encoded(data);

		assertThat(decoded(buffer), equalTo(data));
		assertThat(buffer.readableBytes(), equalTo(0));
	}

	@Example
	public void writesATimeAbove2To31AsFourBigEndianBytesRightAfterTheAddress() {
		final long seconds = 4_000_000_000L;
		final ByteBuf buffer = encoded(with(ACCOUNT, (int) seconds));
		final int numberAt = HEADER_BYTES + ACCOUNT.length() + 1;

		assertThat(ByteBufUtil.hexDump(buffer, numberAt, Integer.BYTES), equalTo("ee6b2800"));
		assertThat(buffer.getUnsignedInt(numberAt), equalTo(seconds));
		assertThat(decoded(buffer).getMints().keySet().iterator().next().getHeight(), equalTo((int) seconds));
	}

	@Example
	public void keepsTheBytesOfAnOldEntryAsTheyWere() {
		assertThat(ByteBufUtil.hexDump(encoded(with("addr", 1_000))), equalTo(
			"0000010000000000" + "61646472" + "00" + "000003e8" + "3130" + "00"));
	}

	private static ByteBuf mintTo(String address) {
		final ByteBuf buffer = Unpooled.buffer().writeMedium(1).writeZero(5 /* 40 bits */);

		ByteBufUtils.writeNullTerminatedString(address, buffer);
		buffer.writeInt(7);
		ByteBufUtils.writeNullTerminatedString("10", buffer);
		return buffer;
	}

	@Example
	public void takesAnAddressAtTheCap() {
		final String address = "a".repeat(Address.MAX_LENGTH);

		assertThat(decoded(mintTo(address)).getMints().keySet().iterator().next().getAddress().getWif(),
			equalTo(address));
	}

	@Property(tries = 20)
	public void refusesAnAddressAboveTheCap(@ForAll @IntRange(min = Address.MAX_LENGTH + 1, max = 4096) int length) {
		assertThrows(CorruptedFrameException.class, () -> decoded(mintTo("a".repeat(length))));
	}

	@Example
	public void refusesAnAddressThatDoesNotEnd() {
		final ByteBuf buffer = Unpooled.buffer().writeMedium(1).writeZero(5 /* 40 bits */)
			.writeBytes(ACCOUNT.getBytes());

		assertThrows(CorruptedFrameException.class, () -> decoded(buffer));
	}

	@Example
	public void survivesARoundTripAtTheCap() {
		final SporkData data = withMints(SporkData.MAX_ENTRIES);
		final ByteBuf buffer = encoded(data);

		assertThat(decoded(buffer), equalTo(data));
		assertThat(buffer.readableBytes(), equalTo(0));
	}

	@Property(tries = 50)
	public void refusesACountAboveTheCapBeforeReadingAnyMint(
		@ForAll @IntRange(min = SporkData.MAX_ENTRIES + 1, max = 0xFF_FFFF) int count) {

		final ByteBuf buffer = Unpooled.buffer().writeMedium(count);

		assertThrows(CorruptedFrameException.class, () -> decoded(buffer));
		assertThat(buffer.readableBytes(), equalTo(0));
	}

	@Example
	public void refusesAClaimOfMillionsOfMintsInATinyBodyAtOnce() {
		final ByteBuf buffer = mintTo(ACCOUNT).setMedium(0, 1 << 23);

		assertThat(assertThrows(CorruptedFrameException.class, () -> decoded(buffer)).getMessage(),
			startsWith("A mint storage of " + (1 << 23)));
		assertThat(buffer.readerIndex(), equalTo(3));
	}

	@Example
	public void refusesToWriteWhatItsPeersWouldNotAccept() {
		assertThrows(IllegalArgumentException.class, () -> encoded(withMints(SporkData.MAX_ENTRIES + 1)));
		assertThrows(IllegalArgumentException.class, () -> encoded(with("a".repeat(Address.MAX_LENGTH + 1), 1)));
	}

	@Example
	public void roundTripsEveryNumberOfTheEdgesTogether() {
		final Map<Location, BigDecimal> mints = new HashMap<>();

		for (int number : new int[] { 0, 1, Integer.MAX_VALUE, Integer.MIN_VALUE, (int) 4_000_000_000L, -1 }) {
			mints.put(new Location(new Address(ACCOUNT), number), BigDecimal.valueOf(number & 0xFF));
		}

		assertThat(decoded(encoded(with(mints))), equalTo(with(mints)));
	}
}
