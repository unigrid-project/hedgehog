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
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CorruptedFrameException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData;
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData.Vesting;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class VestingStorageIntegrityTest {
	private static final String ACCOUNT = "ab".repeat(32);

	private static Vesting vesting() {
		return Vesting.builder().amount(new BigDecimal("12.5")).block(7).cliff(4).parts(10).percent(20)
			.duration(Duration.ofDays(30)).start(Instant.parse("2026-10-22T12:00:00.000000001Z")).build();
	}

	private static SporkData with(Map<Address, Vesting> vestings) {
		final SporkData data = new SporkData();

		data.setVestingAddresses(vestings);
		return data;
	}

	@SneakyThrows
	private static ByteBuf encoded(SporkData data) {
		final ByteBuf buffer = Unpooled.buffer();

		new VestingStorageEncoder().encodeChunk(null, data, buffer);
		return buffer;
	}

	@SneakyThrows
	private static SporkData decoded(ByteBuf buffer) {
		return new VestingStorageDecoder().decodeChunk(null, buffer).orElseThrow();
	}

	private static SporkData withVestings(int count) {
		return with(IntStream.range(0, count).boxed().collect(Collectors.toMap(
			number -> new Address(ACCOUNT + number), number -> vesting())));
	}

	private static ByteBuf vestingTo(String address, long startSeconds, int startNanos, long durationSeconds,
		int durationNanos) {

		final ByteBuf buffer = Unpooled.buffer().writeMedium(1).writeZero(5 /* 40 bits */);

		ByteBufUtils.writeNullTerminatedString(address, buffer);
		buffer.writeLong(startSeconds).writeInt(startNanos).writeLong(durationSeconds).writeInt(durationNanos);
		buffer.writeInt(10).writeInt(4).writeInt(20).writeInt(7);
		ByteBufUtils.writeNullTerminatedString("12.5", buffer);
		return buffer;
	}

	private static ByteBuf vestingTo(String address) {
		return vestingTo(address, 0, 0, 0, 0);
	}

	private static ByteBuf startingAt(long seconds, int nanos) {
		return vestingTo(ACCOUNT, seconds, nanos, 0, 0);
	}

	private static ByteBuf lastingFor(long seconds, int nanos) {
		return vestingTo(ACCOUNT, 0, 0, seconds, nanos);
	}

	@Example
	public void survivesARoundTripAtTheCap() {
		final SporkData data = withVestings(SporkData.MAX_ENTRIES);
		final ByteBuf buffer = encoded(data);

		assertThat(decoded(buffer), equalTo(data));
		assertThat(buffer.readableBytes(), equalTo(0));
	}

	@Property(tries = 50)
	public void refusesACountAboveTheCapBeforeReadingAnyVesting(
		@ForAll @IntRange(min = SporkData.MAX_ENTRIES + 1, max = 0xFF_FFFF) int count) {

		final ByteBuf buffer = Unpooled.buffer().writeMedium(count);

		assertThrows(CorruptedFrameException.class, () -> decoded(buffer));
		assertThat(buffer.readableBytes(), equalTo(0));
	}

	@Example
	public void refusesAClaimOfMillionsOfVestingsInATinyBodyAtOnce() {
		final ByteBuf buffer = vestingTo(ACCOUNT).setMedium(0, 1 << 23);

		assertThat(assertThrows(CorruptedFrameException.class, () -> decoded(buffer)).getMessage(),
			startsWith("A vesting storage of " + (1 << 23)));
		assertThat(buffer.readerIndex(), equalTo(3));
	}

	@Example
	public void takesTheFirstAndTheLastSecondOfTime() {
		assertThat(decoded(startingAt(Instant.MIN.getEpochSecond(), 0)).getVestingAddresses().values().iterator()
			.next().getStart(), equalTo(Instant.MIN));
		assertThat(decoded(startingAt(Instant.MAX.getEpochSecond(), Instant.MAX.getNano())).getVestingAddresses()
			.values().iterator().next().getStart(), equalTo(Instant.MAX));
	}

	@Example
	public void refusesAStartOutsideTheRangeOfTime() {
		for (long seconds : new long[] { Long.MIN_VALUE, Instant.MIN.getEpochSecond() - 1,
			Instant.MAX.getEpochSecond() + 1, Long.MAX_VALUE }) {

			assertThrows(CorruptedFrameException.class, () -> decoded(startingAt(seconds, 0)));
		}

		assertThrows(CorruptedFrameException.class, () -> decoded(startingAt(Instant.MAX.getEpochSecond(),
			Integer.MAX_VALUE)));
		assertThrows(CorruptedFrameException.class, () -> decoded(startingAt(Long.MAX_VALUE, Integer.MAX_VALUE)));
	}

	@Example
	public void refusesADurationOutsideTheRangeOfTime() {
		assertThat(decoded(lastingFor(Long.MAX_VALUE, 999_999_999)).getVestingAddresses().values().iterator().next()
			.getDuration(), equalTo(Duration.ofSeconds(Long.MAX_VALUE, 999_999_999)));

		assertThrows(CorruptedFrameException.class, () -> decoded(lastingFor(Long.MAX_VALUE, Integer.MAX_VALUE)));
		assertThrows(CorruptedFrameException.class, () -> decoded(lastingFor(Long.MIN_VALUE, -1)));
	}

	@Example
	public void refusesToWriteWhatItsPeersWouldNotAccept() {
		assertThrows(IllegalArgumentException.class, () -> encoded(withVestings(SporkData.MAX_ENTRIES + 1)));
		assertThrows(IllegalArgumentException.class, () -> encoded(with(new HashMap<>(Map.of(
			new Address("a".repeat(Address.MAX_LENGTH + 1)), vesting())))));
	}

	@Example
	public void survivesARoundTrip() {
		final SporkData data = with(new HashMap<>(Map.of(new Address(ACCOUNT), vesting())));
		final ByteBuf buffer = encoded(data);

		assertThat(decoded(buffer), equalTo(data));
		assertThat(buffer.readableBytes(), equalTo(0));
	}

	@Example
	public void takesAnAddressAtTheCap() {
		final String address = "a".repeat(Address.MAX_LENGTH);

		assertThat(decoded(vestingTo(address)).getVestingAddresses().keySet().iterator().next().getWif(),
			equalTo(address));
	}

	@Property(tries = 20)
	public void refusesAnAddressAboveTheCap(@ForAll @IntRange(min = Address.MAX_LENGTH + 1, max = 4096) int length) {
		assertThrows(CorruptedFrameException.class, () -> decoded(vestingTo("a".repeat(length))));
	}

	@Example
	public void refusesAnAddressThatDoesNotEnd() {
		final ByteBuf buffer = Unpooled.buffer().writeMedium(1).writeZero(5 /* 40 bits */)
			.writeBytes(ACCOUNT.getBytes());

		assertThrows(CorruptedFrameException.class, () -> decoded(buffer));
	}
}
