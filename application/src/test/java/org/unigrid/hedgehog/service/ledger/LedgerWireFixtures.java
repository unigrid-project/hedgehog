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
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Address;
import org.unigrid.hedgehog.ledger.Ballot;
import org.unigrid.hedgehog.ledger.Block;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.BlockRequest;
import org.unigrid.hedgehog.ledger.Certificate;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Decision;
import org.unigrid.hedgehog.ledger.Digests;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.Endorsement;
import org.unigrid.hedgehog.ledger.Heartbeat;
import org.unigrid.hedgehog.ledger.LegacyAddress;
import org.unigrid.hedgehog.ledger.LegacySignature;
import org.unigrid.hedgehog.ledger.Message;
import org.unigrid.hedgehog.ledger.Mint;
import org.unigrid.hedgehog.ledger.Proposal;
import org.unigrid.hedgehog.ledger.Reference;
import org.unigrid.hedgehog.ledger.Status;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.ledger.Transfer;
import org.unigrid.hedgehog.ledger.ValidatorSet;
import org.unigrid.hedgehog.ledger.Vote;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerBlockBatchDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerBlockRequestDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerConsensusDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerHeartbeatDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerStatusDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerTransactionDecoder;
import org.unigrid.hedgehog.model.network.initializer.LedgerPipeline;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockBatch;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockRequest;
import org.unigrid.hedgehog.model.network.packet.LedgerConsensus;
import org.unigrid.hedgehog.model.network.packet.LedgerHeartbeat;
import org.unigrid.hedgehog.model.network.packet.LedgerStatus;
import org.unigrid.hedgehog.model.network.packet.LedgerTransaction;

/* The one set of ledger objects, frames and addresses that the tests of the network half build on. The objects are
   well formed and unsigned, as the network layer carries them and never judges them, and every one is made from a
   Random, so that a seed gives the same object each time. */
public final class LedgerWireFixtures {
	public static final int FRAME_HEADER_SIZE = 8;

	private LedgerWireFixtures() {
		/* Static helpers only */
	}

	/* The frame that the encoders of the ledger pipeline write for the packet */
	public static ByteBuf encoded(final Packet packet) {
		final ChannelHandler[] handlers = LedgerPipeline.handlers().toArray(new ChannelHandler[0]);
		final EmbeddedChannel channel = new EmbeddedChannel(handlers);

		channel.writeOutbound(packet);
		return channel.readOutbound();
	}

	public static ByteBuf frameOf(final Packet.Type type, final byte[] body) {
		return Unpooled.buffer().writeShort(FrameDecoder.MAGIC).writeShort(type.getValue()).writeInt(body.length)
			.writeBytes(body);
	}

	/* A batch whose body no decoder could read: a length of a couple of gigabytes, and a few bytes after it */
	public static ByteBuf unreadableBatch(final long requestId) {
		final ByteBuf body = Unpooled.buffer().writeLong(requestId).writeBytes(new byte[] { 127, -1, -1, -1, 1, 2 });

		return frameOf(Packet.Type.LEDGER_BLOCK_BATCH, ByteBufUtil.getBytes(body));
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

	/* The packet that a channel with the ledger encoders wrote, read back through the decoders of the ledger */
	public static Packet sentBy(final EmbeddedChannel sender) {
		final Object frame = sender.readOutbound();
		final EmbeddedChannel receiver = new EmbeddedChannel(new FrameDecoder(), new LedgerTransactionDecoder(),
			new LedgerConsensusDecoder(), new LedgerHeartbeatDecoder(), new LedgerStatusDecoder(),
			new LedgerBlockRequestDecoder(), new LedgerBlockBatchDecoder((channel, id) -> true));

		assertThat("Nothing was sent", frame, notNullValue());
		receiver.writeInbound(frame);
		return receiver.readInbound();
	}

	public static Supplier<Optional<InetSocketAddress>> remoteAt(final String address, final int port) {
		final Optional<InetSocketAddress> resolved = Optional.of(new InetSocketAddress(address, port));

		return () -> resolved;
	}

	/* An IPv4 address as an IPv6 one, ::ffff:a.b.c.d, which is how a socket that takes both families may report it */
	@SneakyThrows
	public static InetSocketAddress ipv4Mapped(final int a, final int b, final int c, final int d, final int port) {
		final byte[] bytes = new byte[16];

		bytes[10] = -1;
		bytes[11] = -1;
		bytes[12] = (byte) a;
		bytes[13] = (byte) b;
		bytes[14] = (byte) c;
		bytes[15] = (byte) d;

		final InetAddress address = Inet6Address.getByAddress(null, bytes, 0);

		assertThat(address, instanceOf(Inet6Address.class));
		return new InetSocketAddress(address, port);
	}

	public static byte[] random(final Random random, final int size) {
		final byte[] bytes = new byte[size];

		random.nextBytes(bytes);
		return bytes;
	}

	public static AccountKey account(final Random random) {
		return new AccountKey(random(random, AccountKey.SIZE));
	}

	public static Address address(final Random random) {
		return random.nextBoolean() ? account(random) : new LegacyAddress(random(random, LegacyAddress.SIZE));
	}

	public static Claim claim(final Random random, final boolean compressed) {
		final byte[] key = random(random, compressed ? 33 : 65);

		key[0] = (byte) (compressed ? 2 + random.nextInt(2) : 4);
		return new Claim(key, account(random), random(random, LegacySignature.SIZE));
	}

	public static Transaction transaction(final Random random) {
		return switch (random.nextInt(4)) {
			case 0 -> new Mint(address(random), 1 + random.nextInt(1_000_000),
				new Reference(random(random, Reference.SIZE)));
			case 1 -> new Vote(account(random), account(random), Vote.Action.values()[random.nextInt(2)],
				random.nextInt(1000), random(random, Ed25519.SIGNATURE_SIZE));
			case 2 -> claim(random, random.nextBoolean());
			default -> new Transfer(account(random), address(random), 1 + random.nextInt(1_000_000),
				random.nextInt(1000), random.nextInt(100), random(random, Ed25519.SIGNATURE_SIZE));
		};
	}

	public static Certificate certificate(final Random random, final int endorsements) {
		return new Certificate(random.nextInt(5), IntStream.range(0, endorsements)
			.mapToObj(i -> new Endorsement(account(random), random(random, Ed25519.SIGNATURE_SIZE))).toList());
	}

	public static Block block(final Random random, final long height, final List<Transaction> transactions,
		final int endorsements) {

		return Block.builder().height(height).view(random.nextInt(5)).previousHash(random(random, Digests.HASH_SIZE))
			.time(random.nextInt(100_000)).stateRoot(random(random, Digests.HASH_SIZE))
			.transactionRoot(random(random, Digests.HASH_SIZE)).transactions(transactions)
			.proposer(account(random)).certificate(certificate(random, endorsements))
			.lastCommit(certificate(random, endorsements)).build();
	}

	public static Block block(final Random random, final long height) {
		final List<Transaction> transactions = IntStream.range(0, random.nextInt(4))
			.mapToObj(i -> transaction(random)).toList();

		return block(random, height, transactions, random.nextInt(4));
	}

	public static Block largestBlock(final Random random) {
		return block(random, 1, Collections.nCopies(Block.MAX_TRANSACTIONS, claim(random, false)),
			ValidatorSet.MAX_SIZE);
	}

	public static Ballot ballot(final Random random, final Ballot.Type type, final long height, final int view,
		final byte[] blockHash) {

		return new Ballot(type, height, view, blockHash, account(random), random(random, Ed25519.SIGNATURE_SIZE));
	}

	public static Proposal proposal(final Random random, final Block block, final int proof) {
		final int validRound = proof == 0 ? random.nextInt(4) - 1 : random.nextInt(4);
		final List<Ballot> polka = IntStream.range(0, proof).mapToObj(i -> ballot(random, Ballot.Type.PREVOTE,
			block.getHeight(), validRound, block.hash())).toList();

		return new Proposal(block.getHeight(), validRound + 1 + random.nextInt(3), validRound, block, polka,
			account(random), random(random, Ed25519.SIGNATURE_SIZE));
	}

	public static Message message(final Random random) {
		final Block block = block(random, 1 + random.nextInt(1000));

		return switch (random.nextInt(4)) {
			case 0 -> ballot(random, Ballot.Type.values()[random.nextInt(2)], 1 + random.nextInt(1000),
				random.nextInt(5), random(random, Digests.HASH_SIZE));
			case 1 -> proposal(random, block, 0);
			case 2 -> proposal(random, block, 1 + random.nextInt(3));
			default -> new Decision(block);
		};
	}

	public static Heartbeat heartbeat(final Random random) {
		return new Heartbeat(account(random), random.nextLong(Long.MAX_VALUE), random(random, Digests.HASH_SIZE),
			random.nextLong(Long.MAX_VALUE), random(random, Ed25519.SIGNATURE_SIZE));
	}

	public static Status status(final Random random) {
		return new Status(random(random, Digests.HASH_SIZE), random.nextLong(Long.MAX_VALUE),
			random(random, Digests.HASH_SIZE));
	}

	public static BlockRequest request(final Random random) {
		return new BlockRequest(1 + random.nextLong(Long.MAX_VALUE - 1), 1 + random.nextInt(BlockBatch.MAX_BLOCKS));
	}

	public static BlockBatch batch(final Random random) {
		final long first = 1 + random.nextInt(1000);

		return new BlockBatch(IntStream.range(0, random.nextInt(4))
			.mapToObj(i -> block(random, first + i)).toList());
	}

	public static BlockBatch emptyBatch() {
		return new BlockBatch(List.of());
	}

	public static Transaction transaction(final int seed) {
		return transaction(new Random(seed));
	}

	public static Message message(final int seed) {
		return message(new Random(seed));
	}

	public static Heartbeat heartbeat(final int seed) {
		return heartbeat(new Random(seed));
	}

	public static Status status(final int seed) {
		return status(new Random(seed));
	}

	public static BlockRequest request(final int seed) {
		return request(new Random(seed));
	}

	public static Packet packet(final Random random) {
		final long requestId = random.nextLong();

		return switch (random.nextInt(6)) {
			case 0 -> LedgerTransaction.builder().transaction(transaction(random)).build();
			case 1 -> LedgerConsensus.builder().message(message(random)).build();
			case 2 -> LedgerHeartbeat.builder().heartbeat(heartbeat(random)).build();
			case 3 -> LedgerStatus.builder().status(status(random)).build();
			case 4 -> LedgerBlockRequest.builder().requestId(requestId).request(request(random)).build();
			default -> LedgerBlockBatch.builder().requestId(requestId).batch(batch(random)).build();
		};
	}

	public static Arbitrary<Packet> packets() {
		return Arbitraries.longs().map(seed -> packet(new Random(seed)));
	}
}
