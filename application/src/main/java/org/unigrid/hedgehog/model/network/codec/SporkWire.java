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
import io.netty.buffer.Unpooled;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;

/* A spork in the form one peer sends it to another, without the frame around it. The command line and the daemon hand
   sporks to each other in it too: the bytes a signature covers are then the ones every peer reads, and the daemon
   reads a spork from the command line within the bounds it reads one from a peer within. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SporkWire {
	public static final int MAX_SIZE = FrameDecoder.MAX_SPORK_SIZE;

	/* A decoder scans the classpath for the decoders of the spork data when it is made, so one serves the process.
	   Neither codec keeps anything from one spork to the next. */
	private static final class Codecs {
		private static final PublishSporkEncoder ENCODER = new PublishSporkEncoder();
		private static final PublishSporkDecoder DECODER = new PublishSporkDecoder();
	}

	@SneakyThrows
	public static byte[] encode(GridSpork spork) {
		final PublishSpork packet = PublishSpork.builder().gridSpork(spork).build();
		final ByteBuf out = Codecs.ENCODER.encode(null, packet).orElseThrow();

		try {
			return ByteBufUtil.getBytes(out);
		} finally {
			out.release();
		}
	}

	/* Whatever the bytes hold, the answer is a spork or an IllegalArgumentException that says what is wrong */
	public static GridSpork decode(byte[] wire) {
		if (wire.length > MAX_SIZE) {
			throw new IllegalArgumentException("A spork is at most " + MAX_SIZE + " bytes, not " + wire.length);
		}

		final ByteBuf in = Unpooled.wrappedBuffer(wire);
		final GridSpork spork = read(in).orElseThrow(() -> new IllegalArgumentException(
			"The bytes hold no spork of a type this node knows"
		)).getGridSpork();

		if (in.isReadable()) {
			throw new IllegalArgumentException(in.readableBytes() + " bytes follow the spork");
		}

		return spork;
	}

	/* A peer's spork that does not decode fails its stream; one handed to the daemon fails its request alone */
	private static Optional<PublishSpork> read(ByteBuf in) {
		try {
			return Codecs.DECODER.typedDecode(null, in);
		} catch (Exception ex) {
			throw new IllegalArgumentException("The bytes are no spork: " + ex, ex);
		}
	}
}
