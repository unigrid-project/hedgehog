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
import java.util.Map;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.model.spork.LedgerFee.SporkData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.model.spork.LedgerFeeSporkTest.with;

public class LedgerFeeSporkIntegrityTest {
	private static final long COIN_UNITS = 100_000_000L;
	private static final int UNIT_BYTES = 8;

	@Provide
	public Arbitrary<Long> unitsWithinACoin() {
		return Arbitraries.longs().between(0, COIN_UNITS);
	}

	/* The unsigned reading is what is tested: a signed one sees every value with the high bit set as a small fee */
	@Provide
	public Arbitrary<Long> unitsAboveACoin() {
		return Arbitraries.oneOf(Arbitraries.longs().between(COIN_UNITS + 1, Long.MAX_VALUE),
			Arbitraries.longs().between(Long.MIN_VALUE, -1), Arbitraries.of(COIN_UNITS + 1, Long.MIN_VALUE, -1L)
		);
	}

	@Provide
	public Arbitrary<BigDecimal> fees() {
		return LedgerFeeSporkTest.fees();
	}

	@SneakyThrows
	private static ByteBuf encoded(SporkData data) {
		final ByteBuf buffer = Unpooled.buffer();

		new LedgerFeeEncoder().encodeChunk(null, data, buffer);
		return buffer;
	}

	@SneakyThrows
	private static SporkData decoded(ByteBuf buffer) {
		return new LedgerFeeDecoder().decodeChunk(null, buffer).orElseThrow();
	}

	private static ByteBuf unitsOf(long units) {
		return Unpooled.buffer().writeLong(units);
	}

	@Property
	public void survivesARoundTripAsEightBytesOfUnits(@ForAll("unitsWithinACoin") long units) {
		final ByteBuf buffer = encoded(with(BigDecimal.valueOf(units, 8)));

		assertThat(buffer.readableBytes(), equalTo(UNIT_BYTES));
		assertThat(buffer.getLong(0), equalTo(units));
		assertThat(decoded(buffer).getMinimumTransferFee(), equalTo(BigDecimal.valueOf(units, 8)));
		assertThat(buffer.readableBytes(), equalTo(0));
	}

	@Example
	public void writesBigEndianUnitsOfEightDecimals() {
		final Map<String, String> expected = Map.of(
			"0", "0000000000000000", "0.00000001", "0000000000000001", "0.001", "00000000000186a0",
			"0.5", "0000000002faf080", "1", "0000000005f5e100", "1.00000000", "0000000005f5e100"
		);

		expected.forEach((fee, bytes) -> assertThat(fee, ByteBufUtil.hexDump(encoded(with(new BigDecimal(fee)))),
			equalTo(bytes)));
	}

	@Property
	public void writesTheSameBytesAtAnyScale(@ForAll("fees") BigDecimal fee) {
		final String bytes = ByteBufUtil.hexDump(encoded(with(fee)));

		assertThat(ByteBufUtil.hexDump(encoded(with(fee.stripTrailingZeros()))), equalTo(bytes));
		assertThat(ByteBufUtil.hexDump(encoded(with(fee.setScale(12)))), equalTo(bytes));
	}

	@Property
	public void rebuildsTheFeeAtEightDecimals(@ForAll("fees") BigDecimal fee) {
		final SporkData decoded = decoded(encoded(with(fee.stripTrailingZeros())));

		assertThat(decoded.getMinimumTransferFee().scale(), equalTo(8));
		assertThat(decoded.getMinimumTransferFee().compareTo(fee), equalTo(0));
	}

	@Example
	public void acceptsExactlyACoinOnTheWire() {
		assertThat(decoded(unitsOf(COIN_UNITS)).getMinimumTransferFee(), equalTo(new BigDecimal("1.00000000")));
	}

	@Property
	public void refusesAnythingAboveACoinOnTheWire(@ForAll("unitsAboveACoin") long units) {
		final ByteBuf buffer = unitsOf(units);

		assertThat(assertThrows(CorruptedFrameException.class, () -> decoded(buffer)).getMessage(),
			startsWith("A minimum fee of " + Long.toUnsignedString(units) + " units"));
	}

	@Example
	public void refusesToWriteWhatItsPeersWouldNotAccept() {
		assertThrows(IllegalArgumentException.class, () -> encoded(with(new BigDecimal("-0.00000001"))));
		assertThrows(IllegalArgumentException.class, () -> encoded(with(new BigDecimal("1.00000001"))));
		assertThrows(IllegalArgumentException.class, () -> encoded(with(new BigDecimal("0.000000001"))));
		assertThrows(IllegalArgumentException.class, () -> encoded(with(null)));
	}
}
