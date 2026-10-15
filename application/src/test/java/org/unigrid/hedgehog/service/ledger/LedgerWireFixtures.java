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

package org.unigrid.hedgehog.service.ledger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Arrays;
import java.util.List;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Ballot;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.BlockRequest;
import org.unigrid.hedgehog.ledger.Digests;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.Heartbeat;
import org.unigrid.hedgehog.ledger.Message;
import org.unigrid.hedgehog.ledger.Status;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.ledger.Vote;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.initializer.LedgerPipeline;

/* Ledger objects that are well formed and unsigned: the network layer carries them and never judges them */
public final class LedgerWireFixtures {
	public static final int FRAME_HEADER_SIZE = 8;

	private LedgerWireFixtures() {
		/* Static helpers only */
	}

	/* The frame that the encoders of the ledger pipeline write for the packet */
	public static ByteBuf encoded(final Packet packet) {
		final EmbeddedChannel channel = new EmbeddedChannel(LedgerPipeline.handlers().toArray(new ChannelHandler[0]));

		channel.writeOutbound(packet);
		return channel.readOutbound();
	}

	public static byte[] bodyOf(final ByteBuf frame) {
		return ByteBufUtil.getBytes(frame, FRAME_HEADER_SIZE, frame.readableBytes() - FRAME_HEADER_SIZE);
	}

	/* The same frame with a body that is `change` bytes longer, or shorter when negative, and a header that says so */
	public static ByteBuf resized(final ByteBuf frame, final int change) {
		final byte[] body = bodyOf(frame);
		final int length = Math.max(0, body.length + change);
		final ByteBuf resized = Unpooled.buffer().writeBytes(frame, 0, Integer.BYTES).writeInt(length);

		return resized.writeBytes(Arrays.copyOf(body, length));
	}

	private static byte[] filled(final int size, final int seed) {
		final byte[] bytes = new byte[size];

		Arrays.fill(bytes, (byte) seed);
		return bytes;
	}

	private static AccountKey account(final int seed) {
		return new AccountKey(filled(AccountKey.SIZE, seed));
	}

	public static Status status(final int seed) {
		return new Status(filled(Digests.HASH_SIZE, seed), seed, filled(Digests.HASH_SIZE, seed + 1));
	}

	public static Heartbeat heartbeat(final int seed) {
		return new Heartbeat(account(seed), seed, filled(Digests.HASH_SIZE, seed), seed,
			filled(Ed25519.SIGNATURE_SIZE, seed));
	}

	public static Transaction transaction(final int seed) {
		return new Vote(account(seed), account(seed + 1), Vote.Action.ADD, seed, filled(Ed25519.SIGNATURE_SIZE, seed));
	}

	public static Message message(final int seed) {
		return new Ballot(Ballot.Type.PREVOTE, 1 + seed, seed, filled(Digests.HASH_SIZE, seed), account(seed),
			filled(Ed25519.SIGNATURE_SIZE, seed));
	}

	public static BlockRequest request(final int seed) {
		return new BlockRequest(1 + seed, 1 + seed % BlockBatch.MAX_BLOCKS);
	}

	public static BlockBatch emptyBatch() {
		return new BlockBatch(List.of());
	}
}
