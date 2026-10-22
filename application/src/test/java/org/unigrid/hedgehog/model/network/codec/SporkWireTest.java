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

package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Instant;
import java.util.Arrays;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSporkProvider;

public class SporkWireTest {
	private static final int FRAME_HEADER = 8;
	private static final int SIGNATURE_SIZE = 139;
	private static final long LATEST_MILLIS = 4_102_444_800_000L;

	/* A spork signed by an earlier build covers its Java serialization, which no two copies need to share */
	private static final short WIRE_SIGNABLE = GridSpork.Flag.WIRE_SIGNABLE.getValue();

	private final GridSporkProvider provider = new GridSporkProvider();

	@Provide
	public Arbitrary<GridSpork> sporks() {
		final Arbitrary<Instant> times = Arbitraries.longs().between(0, LATEST_MILLIS).map(Instant::ofEpochMilli);

		return Combinators.combine(Arbitraries.of(GridSpork.Type.class).filter(type -> type != GridSpork.Type.UNDEFINED),
			Arbitraries.bytes().array(byte[].class).ofSize(SIGNATURE_SIZE), times, times)
			.flatAs((type, signature, time, previousTime) -> provider.provide(type, WIRE_SIGNABLE, signature, time,
				previousTime));
	}

	/* What a peer writes in the frame of a spork, after the header of the frame */
	private static byte[] frameBodyOf(GridSpork spork) {
		final EmbeddedChannel channel = new EmbeddedChannel(new PublishSporkEncoder());

		channel.writeOutbound(PublishSpork.builder().gridSpork(spork).build());

		final ByteBuf frame = channel.readOutbound();

		try {
			return ByteBufUtil.getBytes(frame.skipBytes(FRAME_HEADER));
		} finally {
			frame.release();
		}
	}

	@Property(tries = 50)
	public void shouldBeTheBodyOfTheFrameAPeerSends(@ForAll("sporks") GridSpork spork) {
		assertThat(SporkWire.encode(spork), equalTo(frameBodyOf(spork)));
	}

	@Property(tries = 50)
	public void shouldReadBackTheSporkWithTheBytesItsSignaturesCover(@ForAll("sporks") GridSpork spork) {
		final GridSpork decoded = SporkWire.decode(frameBodyOf(spork));

		assertThat(decoded, equalTo(spork));
		assertThat(decoded.getSignable(), equalTo(spork.getSignable()));
	}

	@Property(tries = 30)
	public void shouldRefuseASporkThatIsCutShort(@ForAll("sporks") GridSpork spork, @ForAll @IntRange(min = 1)
		int cut) {

		final byte[] wire = SporkWire.encode(spork);

		assertThrows(IllegalArgumentException.class, () -> SporkWire.decode(Arrays.copyOf(wire,
			Math.max(0, wire.length - cut))));
	}

	@Property(tries = 10)
	public void shouldRefuseBytesAfterTheSpork(@ForAll("sporks") GridSpork spork) {
		final byte[] wire = SporkWire.encode(spork);

		assertThat(assertThrows(IllegalArgumentException.class, () -> SporkWire.decode(Arrays.copyOf(wire,
			wire.length + 1))).getMessage(), containsString("1 byte"));
	}

	@Example
	public void shouldRefuseASporkOfATypeNoNodeKnows() {
		assertThrows(IllegalArgumentException.class, () -> SporkWire.decode(new byte[] { 0x7F, 0x7F, 0, 0 }));
	}

	@Example
	public void shouldRefuseMoreThanTheLargestSporkAPeerMaySend() {
		assertThat(assertThrows(IllegalArgumentException.class, () -> SporkWire.decode(
			new byte[SporkWire.MAX_SIZE + 1])).getMessage(), containsString(Integer.toString(SporkWire.MAX_SIZE)));
	}
}
