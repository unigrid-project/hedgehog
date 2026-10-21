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
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

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

	@Example
	public void roundTripsEveryNumberOfTheEdgesTogether() {
		final Map<Location, BigDecimal> mints = new HashMap<>();

		for (int number : new int[] { 0, 1, Integer.MAX_VALUE, Integer.MIN_VALUE, (int) 4_000_000_000L, -1 }) {
			mints.put(new Location(new Address(ACCOUNT), number), BigDecimal.valueOf(number & 0xFF));
		}

		assertThat(decoded(encoded(with(mints))), equalTo(with(mints)));
	}
}
