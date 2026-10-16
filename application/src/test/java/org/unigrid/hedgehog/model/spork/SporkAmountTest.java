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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class SporkAmountTest {
	private static ByteBuf wire(final String text) {
		return Unpooled.buffer().writeBytes(text.getBytes(StandardCharsets.UTF_8)).writeByte(0);
	}

	private static String refusalOf(final String text) {
		return assertThrows(CorruptedFrameException.class, () -> SporkAmount.read(wire(text))).getMessage();
	}

	@Provide
	public Arbitrary<BigDecimal> amountsWithinTheBounds() {
		return Arbitraries.bigIntegers().between(BigInteger.ZERO, BigInteger.TEN.pow(20).subtract(BigInteger.ONE))
			.flatMap(unscaled -> Arbitraries.integers().between(0, SporkAmount.MAX_DECIMALS)
				.map(scale -> new BigDecimal(unscaled, scale)))
			.filter(SporkAmount::isWithinBounds);
	}

	@Property
	public void shouldReadWhatTheEncodersWrite(@ForAll("amountsWithinTheBounds") final BigDecimal amount) {
		final ByteBuf in = wire(amount.toPlainString());

		assertThat(SporkAmount.read(in).compareTo(amount), is(0));
		assertThat(in.isReadable(), is(false));
	}

	@Example
	public void shouldTakeTheLimitsThemselves() {
		assertThat(SporkAmount.isWithinBounds(new BigDecimal("999999999999.99999999")), is(true));
		assertThat(SporkAmount.isWithinBounds(new BigDecimal("0.00000001")), is(true));
		assertThat(SporkAmount.isWithinBounds(new BigDecimal("1E+8")), is(true));
		assertThat(SporkAmount.isWithinBounds(BigDecimal.ZERO), is(true));
	}

	@Example
	public void shouldRefuseAnExponentThatWouldExpandToGigabytes() {
		assertThat(refusalOf("1E+999999999"), containsString("1E+999999999"));
		assertThat(SporkAmount.isWithinBounds(new BigDecimal("1E+999999999")), is(false));
		assertThat(SporkAmount.isWithinBounds(new BigDecimal("1E-999999999")), is(false));
	}

	@Example
	public void shouldRefuseMoreThanEightDecimalsAndMoreThanTwelveIntegerDigits() {
		refusalOf("0.123456789");
		refusalOf("1000000000000");
		refusalOf("1E+9");
		refusalOf("1E-9");
		refusalOf("1E+13");
	}

	@Example
	public void shouldRefuseTextThatIsNoNumberOrIsTooLong() {
		refusalOf("");
		refusalOf("ten");
		refusalOf("1".repeat(SporkAmount.MAX_TEXT + 1));
		assertThat(refusalOf("1".repeat(1_000_000)).length() < 200, is(true));
	}

	@Example
	public void shouldRefuseAFrameThatHoldsNoEndOfTheText() {
		assertThrows(CorruptedFrameException.class,
			() -> SporkAmount.read(Unpooled.buffer().writeBytes("12".getBytes(StandardCharsets.UTF_8))));
	}

	@Example
	public void shouldTakeNothingForAnEmptyTextWhereAnAmountMayBeLeftOut() {
		assertThat(SporkAmount.readOptional(wire("")), equalTo(Optional.empty()));
		assertThat(SporkAmount.readOptional(wire("5")).orElseThrow().compareTo(BigDecimal.valueOf(5)), is(0));
		assertThrows(CorruptedFrameException.class, () -> SporkAmount.readOptional(wire("1E+999999999")));
	}

	private static final String HUGE = "1E+999999999";

	@Example
	public void shouldBeHeldByTheDecoderOfTheMaximumSupply() {
		assertThrows(CorruptedFrameException.class, () -> new MintSupplyDecoder().decodeChunk(null, wire(HUGE)));
	}

	@Example
	public void shouldBeHeldByTheDecoderOfTheMintStorage() {
		assertThrows(CorruptedFrameException.class, () -> new MintStorageDecoder().decodeChunk(null,
			mintStorageWith(HUGE)));
	}

	@Example
	public void shouldBeHeldByTheDecoderOfTheVestingStorage() {
		assertThrows(CorruptedFrameException.class, () -> new VestingStorageDecoder().decodeChunk(null,
			vestingWith(HUGE)));
	}

	@SneakyThrows
	@Example
	public void shouldLetTheDecodersTakeAnAmountThatIsInBounds() {
		assertThat(new MintSupplyDecoder().decodeChunk(null, wire("21000000")).orElseThrow().getMaxSupply()
			.compareTo(BigDecimal.valueOf(21_000_000)), is(0));
		assertThat(new MintStorageDecoder().decodeChunk(null, mintStorageWith("12.5")).orElseThrow().getMints()
			.values().iterator().next().compareTo(new BigDecimal("12.5")), is(0));
		assertThat(new VestingStorageDecoder().decodeChunk(null, vestingWith("")).orElseThrow()
			.getVestingAddresses().values().iterator().next().getAmount(), nullValue());
	}

	private static ByteBuf mintStorageWith(final String amount) {
		final ByteBuf in = Unpooled.buffer().writeMedium(1).writeZero(5);

		in.writeBytes("address".getBytes(StandardCharsets.UTF_8)).writeByte(0).writeInt(7);
		return in.writeBytes(amount.getBytes(StandardCharsets.UTF_8)).writeByte(0);
	}

	private static ByteBuf vestingWith(final String amount) {
		final ByteBuf in = Unpooled.buffer().writeMedium(1).writeZero(5);

		in.writeBytes("address".getBytes(StandardCharsets.UTF_8)).writeByte(0);
		in.writeLong(0).writeInt(0).writeLong(0).writeInt(0).writeInt(0).writeInt(0).writeInt(0).writeInt(0);
		return in.writeBytes(amount.getBytes(StandardCharsets.UTF_8)).writeByte(0);
	}
}
