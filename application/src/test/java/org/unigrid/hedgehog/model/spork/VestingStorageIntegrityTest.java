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

	private static ByteBuf vestingTo(String address, long startSeconds, long durationSeconds) {
		final ByteBuf buffer = Unpooled.buffer().writeMedium(1).writeZero(5 /* 40 bits */);

		ByteBufUtils.writeNullTerminatedString(address, buffer);
		buffer.writeLong(startSeconds).writeInt(0).writeLong(durationSeconds).writeInt(0);
		buffer.writeInt(10).writeInt(4).writeInt(20).writeInt(7);
		ByteBufUtils.writeNullTerminatedString("12.5", buffer);
		return buffer;
	}

	private static ByteBuf vestingTo(String address) {
		return vestingTo(address, 0, 0);
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
