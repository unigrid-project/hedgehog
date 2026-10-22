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
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;
import org.unigrid.hedgehog.model.spork.StatisticsPubKey.SporkData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class StatisticsPubKeyIntegrityTest {
	private static SporkData with(String publicKey) {
		final SporkData data = new SporkData();

		data.setPublicKey(publicKey);
		return data;
	}

	@SneakyThrows
	private static ByteBuf encoded(SporkData data) {
		final ByteBuf buffer = Unpooled.buffer();

		new StatisticsPubKeyEncoder().encodeChunk(null, data, buffer);
		return buffer;
	}

	@SneakyThrows
	private static SporkData decoded(ByteBuf buffer) {
		return new StatisticsPubKeyDecoder().decodeChunk(null, buffer).orElseThrow();
	}

	private static ByteBuf keyOf(String publicKey) {
		final ByteBuf buffer = Unpooled.buffer().writeZero(8 /* 64 bits */);

		ByteBufUtils.writeNullTerminatedString(publicKey, buffer);
		return buffer;
	}

	@Example
	public void survivesARoundTripAtTheCap() {
		final SporkData data = with("ab".repeat(SporkData.MAX_PUBLIC_KEY_LENGTH / 2));
		final ByteBuf buffer = encoded(data);

		assertThat(decoded(buffer), equalTo(data));
		assertThat(buffer.readableBytes(), equalTo(0));
	}

	@Property(tries = 20)
	public void refusesAKeyAboveTheCap(
		@ForAll @IntRange(min = SporkData.MAX_PUBLIC_KEY_LENGTH + 1, max = 65536) int length) {

		assertThrows(CorruptedFrameException.class, () -> decoded(keyOf("a".repeat(length))));
	}

	@Example
	public void refusesAKeyThatDoesNotEnd() {
		final ByteBuf buffer = Unpooled.buffer().writeZero(8 /* 64 bits */).writeBytes("abcdef".getBytes());

		assertThrows(CorruptedFrameException.class, () -> decoded(buffer));
	}
}
