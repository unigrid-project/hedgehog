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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Digests;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.SignatureLog;
import org.unigrid.hedgehog.model.spork.SignatureLogEntry;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.GroupKey;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class FrameDecoderTest {
	private static final int HEADER_SIZE = 8;
	private static final short UNKNOWN_TYPE = 12345;
	private static final int GROUPS = StorageCodecs.MAX_GROUPS_PER_PACKET;
	private static final int TOMBSTONE = GroupKey.PUBLIC_KEY_SIZE + Long.BYTES + GroupKey.SIGNATURE_SIZE;
	private static final int GRIDNODE = 1 + Long.BYTES + Short.BYTES + 2 * Signature.PUBLIC_KEY_HEX_SIZE
		+ 2 * Short.BYTES + 32;

	/* The longest DER encoding of a P-521 signature */
	private static final int SIGNATURE_SIZE = 139;

	/* A ping period, the least that is to arrive of a frame in one, and the periods in a row of less than that after
	   which its stream is closed, written out here and not taken from the decoder */
	private static final int PERIOD_SECONDS = 20;
	private static final int KIBIBYTE = 1024;
	private static final int LEAST_BYTES_A_PERIOD = 16 * KIBIBYTE;
	private static final int SLOW_PERIODS = 3;
	private static final int PERIODS = 100;

	/* The most that a frame of the type may hold, and the least, written out here and not taken from the decoder */
	private static final Map<Packet.Type, Integer> CAPS = Map.ofEntries(
		Map.entry(Packet.Type.HELLO, 16),
		Map.entry(Packet.Type.PING, 16),
		Map.entry(Packet.Type.ASK_PEERS, 8),
		Map.entry(Packet.Type.PUBLISH_PEERS, 8 + Short.MAX_VALUE * (8 + PublishPeersDecoder.MAX_HOST_ADDRESS + 1)),
		Map.entry(Packet.Type.ASK_NODE_DETAILS, 8),
		Map.entry(Packet.Type.PUBLISH_SPORK, 16 * 1024 * 1024),
		Map.entry(Packet.Type.GRIDNODE, GRIDNODE + 255 + 150),
		Map.entry(Packet.Type.FETCH_FRAGMENT, Long.BYTES + GroupId.SIZE),
		Map.entry(Packet.Type.HAS_FRAGMENT, Long.BYTES + Short.BYTES + GROUPS * GroupId.SIZE),
		Map.entry(Packet.Type.FRAGMENT_STATUS, Long.BYTES + Short.BYTES + GROUPS * (GroupId.SIZE + 2 + TOMBSTONE)),
		Map.entry(Packet.Type.DELETE_GROUP, Long.BYTES + GroupId.SIZE + 2 * Integer.BYTES + TOMBSTONE),
		Map.entry(Packet.Type.STORAGE_ACK, Long.BYTES + 1),
		Map.entry(Packet.Type.LEDGER_TRANSACTION, Integer.BYTES + Claim.MAX_ENCODED_SIZE),
		Map.entry(Packet.Type.LEDGER_CONSENSUS, Integer.BYTES + WireCodec.MAX_MESSAGE_SIZE),
		Map.entry(Packet.Type.LEDGER_HEARTBEAT, AccountKey.SIZE + Long.BYTES + Digests.HASH_SIZE + Long.BYTES
			+ Ed25519.SIGNATURE_SIZE),
		Map.entry(Packet.Type.LEDGER_STATUS, 2 * Digests.HASH_SIZE + Long.BYTES),
		Map.entry(Packet.Type.LEDGER_BLOCK_REQUEST, Long.BYTES + Long.BYTES + Integer.BYTES),
		Map.entry(Packet.Type.LEDGER_BLOCK_BATCH, Long.BYTES + Integer.BYTES + BlockBatch.MAX_BYTES + 64 * 1024));

	private static final Map<Packet.Type, Integer> MINIMA = Map.ofEntries(
		Map.entry(Packet.Type.HELLO, CAPS.get(Packet.Type.HELLO)),
		Map.entry(Packet.Type.PING, CAPS.get(Packet.Type.PING)),
		Map.entry(Packet.Type.ASK_PEERS, CAPS.get(Packet.Type.ASK_PEERS)),
		Map.entry(Packet.Type.PUBLISH_PEERS, 8),
		Map.entry(Packet.Type.ASK_NODE_DETAILS, CAPS.get(Packet.Type.ASK_NODE_DETAILS)),
		Map.entry(Packet.Type.PUBLISH_SPORK, Short.BYTES),
		Map.entry(Packet.Type.GRIDNODE, GRIDNODE),
		Map.entry(Packet.Type.STORE_FRAGMENT, Long.BYTES + Integer.BYTES),
		Map.entry(Packet.Type.FETCH_FRAGMENT, CAPS.get(Packet.Type.FETCH_FRAGMENT)),
		Map.entry(Packet.Type.FRAGMENT_REPLY, Long.BYTES + 1 + Integer.BYTES),
		Map.entry(Packet.Type.HAS_FRAGMENT, Long.BYTES + Short.BYTES),
		Map.entry(Packet.Type.FRAGMENT_STATUS, Long.BYTES + Short.BYTES),
		Map.entry(Packet.Type.DELETE_GROUP, CAPS.get(Packet.Type.DELETE_GROUP)),
		Map.entry(Packet.Type.STORAGE_ACK, CAPS.get(Packet.Type.STORAGE_ACK)),
		Map.entry(Packet.Type.LEDGER_TRANSACTION, Integer.BYTES),
		Map.entry(Packet.Type.LEDGER_CONSENSUS, Integer.BYTES),
		Map.entry(Packet.Type.LEDGER_HEARTBEAT, CAPS.get(Packet.Type.LEDGER_HEARTBEAT)),
		Map.entry(Packet.Type.LEDGER_STATUS, CAPS.get(Packet.Type.LEDGER_STATUS)),
		Map.entry(Packet.Type.LEDGER_BLOCK_REQUEST, CAPS.get(Packet.Type.LEDGER_BLOCK_REQUEST)),
		Map.entry(Packet.Type.LEDGER_BLOCK_BATCH, Long.BYTES + Integer.BYTES));

	/* A fragment is as large as the storage spork makes it */
	private static final Set<Packet.Type> FRAGMENT_TYPES = Set.of(Packet.Type.STORE_FRAGMENT,
		Packet.Type.FRAGMENT_REPLY);

	/* The types of the stream that chain traffic has to itself, which the main stream takes nothing of */
	private static final Set<Packet.Type> CHAIN_TYPES = Set.of(Packet.Type.JOIN_CHAIN, Packet.Type.JOIN_REPLY,
		Packet.Type.CHAIN_ENTRY, Packet.Type.CHAIN_HEADS, Packet.Type.FETCH_ENTRIES, Packet.Type.ENTRY_REFUSED,
		Packet.Type.LEAVE_CHAIN, Packet.Type.CHAIN_STEM);

	/* Types that no codec reads or writes */
	private static final Set<Packet.Type> UNREAD_TYPES = Set.of(Packet.Type.UNDEFINED,
		Packet.Type.PUBLISH_NODE_DETAILS, Packet.Type.ASK_SPORKS, Packet.Type.GROW_SPORK);

	/* The types that only a node run with the preview has stages for, those of the gridnodes and the storage, and the
	   types that only a node that offers the ledger has stages for */
	private static final Set<Packet.Type> PREVIEW_TYPES = Set.of(Packet.Type.GRIDNODE, Packet.Type.STORE_FRAGMENT,
		Packet.Type.FETCH_FRAGMENT, Packet.Type.FRAGMENT_REPLY, Packet.Type.HAS_FRAGMENT, Packet.Type.FRAGMENT_STATUS,
		Packet.Type.DELETE_GROUP, Packet.Type.STORAGE_ACK);

	private static final Set<Packet.Type> LEDGER_TYPES = Set.of(Packet.Type.LEDGER_TRANSACTION,
		Packet.Type.LEDGER_CONSENSUS, Packet.Type.LEDGER_HEARTBEAT, Packet.Type.LEDGER_STATUS,
		Packet.Type.LEDGER_BLOCK_REQUEST, Packet.Type.LEDGER_BLOCK_BATCH);

	private static ByteBuf frameOf(byte[] payload) {
		return Unpooled.buffer().writeShort(FrameDecoder.MAGIC).writeShort(Packet.Type.PING.getValue())
			.writeInt(payload.length).writeBytes(payload);
	}

	private static ByteBuf headerOf(final short type, final int size) {
		return Unpooled.buffer().writeShort(FrameDecoder.MAGIC).writeShort(type).writeInt(size);
	}

	private static ByteBuf headerOf(final Packet.Type type, final int size) {
		return headerOf(type.getValue(), size);
	}

	@Provide
	Arbitrary<Packet.Type> cappedTypes() {
		return Arbitraries.of(CAPS.keySet());
	}

	@Provide
	Arbitrary<Packet.Type> chainTypes() {
		return Arbitraries.of(CHAIN_TYPES);
	}

	@Provide
	Arbitrary<Packet.Type> fragmentTypes() {
		return Arbitraries.of(FRAGMENT_TYPES);
	}

	@Provide
	Arbitrary<Short> unreadTypes() {
		return Arbitraries.of(Stream.concat(UNREAD_TYPES.stream().map(Packet.Type::getValue), Stream.of(UNKNOWN_TYPE))
			.toList());
	}

	@Provide
	Arbitrary<Packet.Type> boundedTypes() {
		return Arbitraries.of(MINIMA.keySet());
	}

	@Provide
	Arbitrary<Packet.Type> typesOfTheParts() {
		return Arbitraries.of(Stream.concat(PREVIEW_TYPES.stream(), LEDGER_TYPES.stream()).toList());
	}

	/* The most that a frame of the type may hold, which for a fragment is what the stream may */
	private static int capOf(final Packet.Type type) {
		return FRAGMENT_TYPES.contains(type) ? Network.MAX_DATA_SIZE - HEADER_SIZE : CAPS.get(type);
	}

	/* Whether a frame of the type, whole and as small as one may be, comes out of the decoder */
	private static boolean reads(final FrameDecoder decoder, final Packet.Type type) {
		final EmbeddedChannel channel = new EmbeddedChannel(decoder);
		final int size = MINIMA.get(type);

		channel.writeInbound(headerOf(type, size).writeZero(size));

		final ByteBuf frame = channel.readInbound();

		return frame != null && frame.readableBytes() == size;
	}

	@Example
	public void boundsEveryType() {
		Arrays.stream(Packet.Type.values()).forEach(type -> assertThat(type.toString(), Stream.of(CAPS.keySet(),
			FRAGMENT_TYPES, UNREAD_TYPES, CHAIN_TYPES).filter(types -> types.contains(type)).count(), equalTo(1L)));
	}

	@Property(tries = 50)
	public void refusesAFrameOfAChainTypeOnTheMainStream(@ForAll("chainTypes") Packet.Type type,
		@ForAll @IntRange(min = 1, max = 100_000) int size) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		assertThrows(TooLongFrameException.class, () -> channel.writeInbound(headerOf(type, size)));
	}

	@Property(tries = 50)
	public void refusesAFrameOfAnyOtherTypeOnTheChainStream(@ForAll("cappedTypes") Packet.Type type) {
		Assume.that(type != Packet.Type.PING);

		final EmbeddedChannel channel = new EmbeddedChannel(FrameDecoder.forChainStream());

		assertThrows(TooLongFrameException.class, () -> channel.writeInbound(headerOf(type, 1)));
	}

	@Property(tries = 50)
	public void refusesAHeaderAboveTheCapOfItsType(@ForAll("cappedTypes") Packet.Type type,
		@ForAll @IntRange(min = 1, max = 100_000) int excess) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		assertThrows(TooLongFrameException.class, () -> channel.writeInbound(headerOf(type, CAPS.get(type) + excess)));
	}

	@Property(tries = 50)
	public void refusesAHeaderOfAHugeLength(@ForAll("cappedTypes") Packet.Type type,
		@ForAll @IntRange(min = Network.MAX_DATA_SIZE - 10, max = Integer.MAX_VALUE) int size) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		assertThrows(DecoderException.class, () -> channel.writeInbound(headerOf(type, size)));
	}

	@Example
	public void waitsForTheBodyOfAFrameThatIsAtTheCapOfItsType() {
		CAPS.forEach((type, cap) -> {
			final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

			channel.writeInbound(headerOf(type, cap));
			assertThat(channel.readInbound(), nullValue());
			assertThat(channel.attr(Packet.KEY).get(), equalTo(type));
		});
	}

	/* A join reply or a heads packet with as many heads as a packet carries passes the stream that chain traffic has
	   to itself, and one byte more does not. A head is its writer, sequence number, hash, stamp and mark, written out
	   here and not taken from the codec. */
	@Example
	public void takesExactlyTheMostHeadsOfAPacketOnTheChainStream() {
		final int heads = Short.BYTES + ChainCodecs.MAX_HEADS_PER_PACKET * (WriterId.SIZE + 3 * Long.BYTES + 1);

		Map.of(Packet.Type.JOIN_REPLY, Long.BYTES + 1 + ChainId.SIZE + heads, Packet.Type.CHAIN_HEADS,
			ChainId.SIZE + heads).forEach((type, cap) -> {
				final EmbeddedChannel atTheCap = new EmbeddedChannel(FrameDecoder.forChainStream());
				final EmbeddedChannel beyond = new EmbeddedChannel(FrameDecoder.forChainStream());

				atTheCap.writeInbound(headerOf(type, cap));
				assertThat(atTheCap.readInbound(), nullValue());
				assertThat(atTheCap.attr(Packet.KEY).get(), equalTo(type));
				assertThrows(TooLongFrameException.class, () -> beyond.writeInbound(headerOf(type, cap + 1)));
			});
	}

	/* A stem carries entries as a flood does, so the stream that chain traffic has to itself takes a frame of either
	   up to the limit of a frame */
	@Example
	public void takesAStemAsLargeAsAFloodOnTheChainStream() {
		final int cap = ChainCodecs.MAX_FRAME_BYTES - ChainCodecs.FRAME_HEADER_BYTES;

		List.of(Packet.Type.CHAIN_ENTRY, Packet.Type.CHAIN_STEM).forEach(type -> {
			final EmbeddedChannel atTheCap = new EmbeddedChannel(FrameDecoder.forChainStream());
			final EmbeddedChannel beyond = new EmbeddedChannel(FrameDecoder.forChainStream());
			final EmbeddedChannel empty = new EmbeddedChannel(FrameDecoder.forChainStream());

			atTheCap.writeInbound(headerOf(type, cap));
			assertThat(atTheCap.readInbound(), nullValue());
			assertThat(atTheCap.attr(Packet.KEY).get(), equalTo(type));
			assertThrows(TooLongFrameException.class, () -> beyond.writeInbound(headerOf(type, cap + 1)));
			assertThrows(CorruptedFrameException.class, () -> empty.writeInbound(headerOf(type, 1)));
		});
	}

	@Property(tries = 50)
	public void refusesAHeaderBelowTheLeastOfItsType(@ForAll("cappedTypes") Packet.Type type,
		@ForAll @IntRange(min = 0, max = 1_000) int shortage) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());
		final int size = MINIMA.get(type) - 1 - shortage % MINIMA.get(type);

		assertThrows(CorruptedFrameException.class, () -> channel.writeInbound(headerOf(type, size)));
	}

	@Property(tries = 20)
	public void refusesANegativeLength(@ForAll Packet.Type type, @ForAll @IntRange(min = Integer.MIN_VALUE, max = -1)
		int size) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		assertThrows(DecoderException.class, () -> channel.writeInbound(headerOf(type, size)));
	}

	@Property(tries = 20)
	public void refusesAFrameOfATypeThatNoCodecReadsThatHoldsAnything(@ForAll("unreadTypes") short type,
		@ForAll @IntRange(min = 1, max = 1_000_000) int size) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		assertThrows(TooLongFrameException.class, () -> channel.writeInbound(headerOf(type, size)));
	}

	@Example
	public void passesAnEmptyFrameOfAnUnknownTypeOn() {
		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		channel.writeInbound(headerOf(UNKNOWN_TYPE, 0));
		assertThat(((ByteBuf) channel.readInbound()).readableBytes(), equalTo(0));
		assertThat(channel.attr(Packet.KEY).get(), equalTo(Packet.Type.UNDEFINED));
	}

	@Property(tries = 50)
	public void keepsTheCapOfTheStreamForAFragment(@ForAll("fragmentTypes") Packet.Type type,
		@ForAll @IntRange(min = 13, max = Network.MAX_DATA_SIZE - HEADER_SIZE) int size) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		channel.writeInbound(headerOf(type, size));
		assertThat(channel.readInbound(), nullValue());
	}

	@Property(tries = 20)
	public void refusesAnythingAboveTheCapOfTheStreamForAFragment(@ForAll("fragmentTypes") Packet.Type type,
		@ForAll @IntRange(min = 1, max = 1_000_000) int excess) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		assertThrows(TooLongFrameException.class, () -> channel.writeInbound(headerOf(type,
			Network.MAX_DATA_SIZE - HEADER_SIZE + excess)));
	}

	/* The main stream of a node reads the frames of the parts that the node runs. Of the parts it does without it
	   hands on no frame, as it has no stage for one, and the frames of the pings, peers and sporks it reads always. */
	@Example
	public void readsOnTheMainStreamTheFramesOfThePartsThatTheNodeRunsAndOfNoOther() {
		MINIMA.keySet().forEach(type -> {
			final boolean ledger = LEDGER_TYPES.contains(type);
			final boolean preview = PREVIEW_TYPES.contains(type);

			assertThat(type.toString(), reads(FrameDecoder.forMainStream(true, true), type), is(true));
			assertThat(type.toString(), reads(FrameDecoder.forMainStream(false, true), type), is(!ledger));
			assertThat(type.toString(), reads(FrameDecoder.forMainStream(true, false), type), is(!preview));
			assertThat(type.toString(), reads(FrameDecoder.forMainStream(false, false), type),
				is(!ledger && !preview));
		});
	}

	/* A node that runs every part holds the caps that the plain decoder does */
	@Example
	public void waitsOnTheMainStreamOfANodeThatRunsEveryPartForTheBodyOfAFrameAtTheCapOfItsType() {
		MINIMA.keySet().forEach(type -> {
			final EmbeddedChannel atTheCap = new EmbeddedChannel(FrameDecoder.forMainStream(true, true));
			final EmbeddedChannel beyond = new EmbeddedChannel(FrameDecoder.forMainStream(true, true));

			atTheCap.writeInbound(headerOf(type, capOf(type)));
			assertThat(atTheCap.readInbound(), nullValue());
			assertThat(atTheCap.attr(Packet.KEY).get(), equalTo(type));
			assertThrows(TooLongFrameException.class, () -> beyond.writeInbound(headerOf(type, capOf(type) + 1)));
		});
	}

	/* The header of a frame and then a piece of its body, as a stream hands them over, and the memory of the buffers
	   that the stream has not let go of afterwards, read off the allocator that every one of them comes from */
	private static long heldOf(final FrameDecoder decoder, final Packet.Type type, final int size, final int piece) {
		final UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false, true);
		final EmbeddedChannel channel = new EmbeddedChannel(decoder);

		channel.config().setAllocator(allocator);
		channel.writeInbound(allocator.heapBuffer(HEADER_SIZE).writeShort(FrameDecoder.MAGIC)
			.writeShort(type.getValue()).writeInt(size));
		channel.writeInbound(allocator.heapBuffer(piece).writeZero(piece));
		assertThat(channel.readInbound(), nullValue());
		assertThat(channel.isOpen(), is(true));
		return allocator.metric().usedHeapMemory();
	}

	/* A frame of a part that the node does without is not collected: what arrives of it is used up and freed with
	   every read, however much the header says is to come, where a node that reads the type keeps it for the frame */
	@Property(tries = 50)
	public void holdsNothingOfAFrameOfAPartThatTheNodeDoesWithout(@ForAll("typesOfTheParts") Packet.Type type,
		@ForAll @IntRange(min = 1, max = 64 * 1024) int arrived) {

		final int size = capOf(type);
		final int piece = Math.min(arrived, size - 1);

		assertThat(heldOf(FrameDecoder.forMainStream(false, false), type, size, piece), is(0L));
		assertThat(heldOf(FrameDecoder.forMainStream(true, true), type, size, piece),
			greaterThanOrEqualTo((long) HEADER_SIZE + piece));
	}

	/* What a node takes of a peer does not turn on the parts it runs: a frame that would only be passed over is held
	   to the bounds of its type all the same */
	@Property(tries = 50)
	public void holdsAFrameOfAPartThatTheNodeDoesWithoutToTheBoundsOfItsType(@ForAll("typesOfTheParts") Packet.Type type,
		@ForAll @IntRange(min = 1, max = 100_000) int excess) {

		final EmbeddedChannel above = new EmbeddedChannel(FrameDecoder.forMainStream(false, false));
		final EmbeddedChannel below = new EmbeddedChannel(FrameDecoder.forMainStream(false, false));

		assertThrows(TooLongFrameException.class, () -> above.writeInbound(headerOf(type, capOf(type) + excess)));
		assertThrows(CorruptedFrameException.class, () -> below.writeInbound(headerOf(type, MINIMA.get(type) - 1)));
	}

	/* The bytes to pass over end where the frame does, wherever the reads of the stream fall, so the frame behind it
	   is read whole and nothing else comes out */
	@Property(tries = 200)
	public void readsTheFrameBehindOneOfAPartThatTheNodeDoesWithout(@ForAll("typesOfTheParts") Packet.Type type,
		@ForAll @IntRange(min = 0, max = 10_000) int at, @ForAll @Size(16) byte[] payload) {

		final EmbeddedChannel channel = new EmbeddedChannel(FrameDecoder.forMainStream(false, false));
		final int size = MINIMA.get(type);
		final ByteBuf frames = headerOf(type, size).writeZero(size).writeBytes(frameOf(payload));
		final int firstPart = 1 + at % (frames.readableBytes() - 1);

		channel.writeInbound(frames.readRetainedSlice(firstPart));
		channel.writeInbound(frames.readRetainedSlice(frames.readableBytes()));

		final ByteBuf decoded = channel.readInbound();

		assertThat(ByteBufUtil.getBytes(decoded), equalTo(payload));
		assertThat(channel.readInbound(), nullValue());
		assertThat(channel.attr(Packet.KEY).get(), equalTo(Packet.Type.PING));
	}

	/* A stream whose clock stands still but for the time that a test lets pass */
	private static EmbeddedChannel timedChannel(final FrameDecoder decoder) {
		final EmbeddedChannel channel = new EmbeddedChannel(decoder);

		channel.freezeTime();
		return channel;
	}

	private static void pass(final EmbeddedChannel channel, final long seconds) {
		channel.advanceTimeBy(seconds, TimeUnit.SECONDS);
		channel.runScheduledPendingTasks();
	}

	/* All of three periods but the last second, a period at a time, as on an event loop that is not held up */
	private static void passUntilASecondBeforeTheThirdPeriodEnds(final EmbeddedChannel channel) {
		for (int period = 1; period < SLOW_PERIODS; period++) {
			pass(channel, PERIOD_SECONDS);
		}

		pass(channel, PERIOD_SECONDS - 1);
	}

	/* Whether the stream has anything set for a later time, which here can only be the next look at what arrived of
	   a frame */
	private static boolean isWaiting(final EmbeddedChannel channel) {
		return channel.runScheduledPendingTasks() >= 0;
	}

	/* The periods, of those that the peer delivers something in, at whose end the stream is still open: each starts
	   with what the peer delivers in it, and none is begun on a stream that is closed */
	private static int periodsKept(final EmbeddedChannel channel, final int... delivered) {
		int periods = 0;

		while (periods < delivered.length && channel.isOpen()) {
			if (delivered[periods] > 0) {
				channel.writeInbound(Unpooled.buffer(delivered[periods]).writeZero(delivered[periods]));
			}

			pass(channel, PERIOD_SECONDS);
			periods += channel.isOpen() ? 1 : 0;
		}

		return periods;
	}

	/* The stream of a spork frame as large as one may be, of which the header has arrived and nothing else */
	private static EmbeddedChannel streamOfTheLargestSporkFrame() {
		final EmbeddedChannel channel = timedChannel(new FrameDecoder());

		channel.writeInbound(headerOf(Packet.Type.PUBLISH_SPORK, CAPS.get(Packet.Type.PUBLISH_SPORK)));
		return channel;
	}

	private static int[] inEachOf(final int periods, final int bytes) {
		return IntStream.generate(() -> bytes).limit(periods).toArray();
	}

	/* A peer that never finishes a frame cannot make a stream hold what has arrived of it for as long as it likes:
	   at the end of the third period in a row in which too little of the frame arrived, and not a second before, the
	   stream is closed and frees what it held, however little of the frame was still to come and whatever arrived
	   in the meantime */
	@Property(tries = 100)
	public void closesTheStreamOfAFrameThatIsNotWholeAfterThreePeriodsOfTooLittle(
		@ForAll("boundedTypes") Packet.Type type, @ForAll @IntRange(min = 0) int pick,
		@ForAll @IntRange(min = 1, max = 4096) int arrived) {

		final UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false, true);
		final EmbeddedChannel channel = timedChannel(new FrameDecoder());
		final int size = MINIMA.get(type) + pick % (capOf(type) - MINIMA.get(type) + 1);
		final int piece = Math.min(arrived, size - 2);

		channel.config().setAllocator(allocator);
		channel.writeInbound(allocator.heapBuffer(HEADER_SIZE).writeShort(FrameDecoder.MAGIC)
			.writeShort(type.getValue()).writeInt(size));
		channel.writeInbound(allocator.heapBuffer().writeZero(piece));
		passUntilASecondBeforeTheThirdPeriodEnds(channel);
		assertThat(channel.isOpen(), is(true));
		assertThat(allocator.metric().usedHeapMemory(), greaterThanOrEqualTo((long) HEADER_SIZE + piece));

		channel.writeInbound(allocator.heapBuffer(1).writeByte(0));
		pass(channel, 1);
		assertThat(channel.isOpen(), is(false));
		assertThat(allocator.metric().usedHeapMemory(), is(0L));
	}

	/* The bytes of a frame count as a sign of its peer, so no ping ends a frame of which a byte arrives now and then.
	   Such a frame is not given the time that it would take to arrive whole at any rate: the stream is closed at the
	   end of the third period, a minute after the header. */
	@Example
	public void closesTheStreamOfTheLargestSporkFrameThatCrawlsInAByteAPeriodInTheThirdPeriod() {
		final EmbeddedChannel channel = streamOfTheLargestSporkFrame();

		assertThat(periodsKept(channel, inEachOf(PERIODS, 1)), is(SLOW_PERIODS - 1));
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void closesTheStreamOfAFrameOfWhich15KibibytesArriveAPeriodInTheThirdPeriod() {
		final EmbeddedChannel channel = streamOfTheLargestSporkFrame();

		assertThat(periodsKept(channel, inEachOf(PERIODS, 15 * KIBIBYTE)), is(SLOW_PERIODS - 1));
		assertThat(channel.isOpen(), is(false));
	}

	/* A peer on a slow link is kept however long a frame takes to arrive: the largest spork frame at this rate takes
	   more than five hours */
	@Example
	public void keepsTheStreamOfAFrameOfWhich17KibibytesArriveAPeriod() {
		final EmbeddedChannel channel = streamOfTheLargestSporkFrame();

		assertThat(periodsKept(channel, inEachOf(PERIODS, 17 * KIBIBYTE)), is(PERIODS));
		assertThat(channel.isOpen(), is(true));
		assertThat(channel.readInbound(), nullValue());
	}

	/* The two sides of the least that a stream is kept for lie a byte apart */
	@Example
	public void closesTheStreamOfAFrameOfWhichAByteLessThan16KibibytesArrivesAPeriod() {
		final EmbeddedChannel channel = streamOfTheLargestSporkFrame();

		assertThat(periodsKept(channel, inEachOf(PERIODS, LEAST_BYTES_A_PERIOD - 1)), is(SLOW_PERIODS - 1));
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void keepsTheStreamOfAFrameOfWhich16KibibytesArriveAPeriod() {
		final EmbeddedChannel channel = streamOfTheLargestSporkFrame();

		assertThat(periodsKept(channel, inEachOf(PERIODS, LEAST_BYTES_A_PERIOD)), is(PERIODS));
		assertThat(channel.isOpen(), is(true));
	}

	@Property(tries = 25)
	public void keepsTheStreamOfAFrameOfWhichNoLessThan16KibibytesArriveAPeriod(
		@ForAll @IntRange(min = LEAST_BYTES_A_PERIOD, max = 8 * LEAST_BYTES_A_PERIOD) int bytesAPeriod) {

		final EmbeddedChannel channel = streamOfTheLargestSporkFrame();

		assertThat(periodsKept(channel, inEachOf(PERIODS, bytesAPeriod)), is(PERIODS));
		channel.finishAndReleaseAll();
	}

	@Property(tries = 25)
	public void closesTheStreamOfAFrameOfWhichLessThan16KibibytesArriveAPeriodInTheThirdPeriod(
		@ForAll @IntRange(min = 0, max = LEAST_BYTES_A_PERIOD - 1) int bytesAPeriod) {

		final EmbeddedChannel channel = streamOfTheLargestSporkFrame();

		assertThat(periodsKept(channel, inEachOf(PERIODS, bytesAPeriod)), is(SLOW_PERIODS - 1));
		assertThat(channel.isOpen(), is(false));
	}

	/* Only periods in a row count, so one in which enough arrived starts the count again: too little arrives in the
	   first, enough in the second, and nothing after it, which closes the stream at the end of the fifth */
	@Example
	public void closesTheStreamThreePeriodsAfterTheLastInWhichEnoughArrived() {
		final EmbeddedChannel channel = streamOfTheLargestSporkFrame();

		assertThat(periodsKept(channel, 15 * KIBIBYTE, 20 * KIBIBYTE, 0, 0), is(4));
		assertThat(periodsKept(channel, 0), is(0));
		assertThat(channel.isOpen(), is(false));
	}

	/* A period is counted from the read that began the frame, so what that read brought of the frame is no part of
	   what arrives in the first: a frame that then stands still is closed a minute after its last byte */
	@Example
	public void countsForAPeriodWhatArrivedAfterTheReadThatBeganTheFrame() {
		final EmbeddedChannel channel = timedChannel(new FrameDecoder());

		channel.writeInbound(headerOf(Packet.Type.PUBLISH_SPORK, CAPS.get(Packet.Type.PUBLISH_SPORK))
			.writeZero(64 * LEAST_BYTES_A_PERIOD));

		assertThat(periodsKept(channel, inEachOf(PERIODS, 0)), is(SLOW_PERIODS - 1));
		assertThat(channel.isOpen(), is(false));
	}

	/* An event loop that is held up, by a lock that a thread waits on or by a machine that stood still, leaves the
	   looks that it missed due all at once. Made one after the other in the same breath, three of them would close
	   the stream of a peer that delivered all the while: the stream is looked at once for the time that the loop
	   stood still, and again a period after that. */
	@Example
	public void doesNotMakeUpForTheLooksThatAStallMissed() {
		final EmbeddedChannel channel = streamOfTheLargestSporkFrame();

		channel.advanceTimeBy(5 * PERIOD_SECONDS, TimeUnit.SECONDS);
		channel.runScheduledPendingTasks();
		assertThat(channel.isOpen(), is(true));

		assertThat(periodsKept(channel, 0), is(1));
		assertThat(periodsKept(channel, 0), is(0));
		assertThat(channel.isOpen(), is(false));
	}

	/* A frame that is whole ends the looks at it, so nothing is left to end a stream that then stays quiet, and the
	   frame behind it is looked at from its own header on */
	@Example
	public void endsTheLooksAtAFrameOnceItIsWholeAndStartsThemForTheNext() {
		final EmbeddedChannel channel = timedChannel(new FrameDecoder());
		final byte[] payload = new byte[2 * Long.BYTES];
		final ByteBuf frame = frameOf(payload);

		channel.writeInbound(frame.readRetainedSlice(HEADER_SIZE + 1));
		assertThat(isWaiting(channel), is(true));
		passUntilASecondBeforeTheThirdPeriodEnds(channel);
		channel.writeInbound(frame.readRetainedSlice(frame.readableBytes()));
		assertThat(ByteBufUtil.getBytes((ByteBuf) channel.readInbound()), equalTo(payload));
		assertThat(isWaiting(channel), is(false));

		pass(channel, PERIODS * PERIOD_SECONDS);
		assertThat(channel.isOpen(), is(true));

		channel.writeInbound(headerOf(Packet.Type.PING, payload.length));
		passUntilASecondBeforeTheThirdPeriodEnds(channel);
		assertThat(channel.isOpen(), is(true));
		pass(channel, 1);
		assertThat(channel.isOpen(), is(false));
	}

	/* A frame that arrives whole in one read is not looked at again */
	@Example
	public void looksAtNoFrameThatArrivesWhole() {
		final EmbeddedChannel channel = timedChannel(new FrameDecoder());

		channel.writeInbound(frameOf(new byte[2 * Long.BYTES]));
		assertThat(isWaiting(channel), is(false));
	}

	/* The looks at a frame end with the stream, and with the decoder when it is taken off the stream, so that none
	   outlives what it was to close */
	@Example
	public void endsTheLooksAtAFrameWithTheStreamAndWithTheDecoder() {
		final EmbeddedChannel inactive = streamOfTheLargestSporkFrame();
		final EmbeddedChannel removed = streamOfTheLargestSporkFrame();

		assertThat(isWaiting(inactive), is(true));
		inactive.pipeline().fireChannelInactive();
		assertThat(isWaiting(inactive), is(false));

		assertThat(isWaiting(removed), is(true));
		removed.pipeline().remove(FrameDecoder.class);
		assertThat(isWaiting(removed), is(false));
		removed.releaseInbound();
		pass(removed, PERIODS * PERIOD_SECONDS);
		assertThat(removed.isOpen(), is(true));
	}

	/* A frame of a part that the node does without holds nothing while it arrives, so there is nothing to free and it
	   is not looked at: the stream is left open however slowly the frame comes */
	@Property(tries = 20)
	public void looksAtNoFrameOfAPartThatTheNodeDoesWithout(@ForAll("typesOfTheParts") Packet.Type type) {
		final EmbeddedChannel channel = timedChannel(FrameDecoder.forMainStream(false, false));

		channel.writeInbound(headerOf(type, capOf(type)));
		channel.writeInbound(Unpooled.buffer().writeZero(1));
		assertThat(isWaiting(channel), is(false));
		pass(channel, PERIODS * PERIOD_SECONDS);
		assertThat(channel.isOpen(), is(true));
	}

	/* The stream that chain traffic has to itself holds a frame for no longer than the main stream does */
	@Example
	public void closesTheChainStreamOfAFrameThatIsNotWholeAfterThreePeriodsOfTooLittle() {
		final EmbeddedChannel channel = timedChannel(FrameDecoder.forChainStream());
		final int size = ChainCodecs.MAX_FRAME_BYTES - ChainCodecs.FRAME_HEADER_BYTES;

		channel.writeInbound(headerOf(Packet.Type.CHAIN_ENTRY, size));
		passUntilASecondBeforeTheThirdPeriodEnds(channel);
		assertThat(channel.isOpen(), is(true));
		pass(channel, 1);
		assertThat(channel.isOpen(), is(false));
	}

	/* A mint storage at its cap in both its data and its previous data, at the longest address and amount, signed
	   twice, with a signature log at its cap of entries signed twice by keys of full length */
	@Example
	public void takesTheLargestSporkAnHonestNodeSends() {
		final MintStorage spork = new MintStorage();
		final Map<Location, BigDecimal> mints = new HashMap<>();
		final Address address = new Address("a".repeat(Address.MAX_LENGTH));
		final BigDecimal amount = new BigDecimal("999999999999.99999999").setScale(35);
		final String key = "f".repeat(2 * Signature.PUBLIC_KEY_HEX_SIZE);
		final byte[] signature = new byte[SIGNATURE_SIZE];
		final SignatureLog log = new SignatureLog();

		IntStream.range(0, MintStorage.SporkData.MAX_ENTRIES).forEach(n -> mints.put(new Location(address, n), amount));
		IntStream.range(0, SignatureLog.MAX_ENTRIES).forEach(n -> log.append(SignatureLogEntry.builder()
			.timeStamp(Instant.ofEpochMilli(n)).signer(key).digest(new byte[SignatureLogEntry.DIGEST_SIZE])
			.signature(signature).cosigner(key).cosignature(signature).build()));

		spork.<MintStorage.SporkData>getData().setMints(mints);
		spork.setPreviousData(spork.getData());
		spork.setSignature(signature);
		spork.setCosignature(signature);
		spork.setSignatureLog(log);

		final EmbeddedChannel encoder = new EmbeddedChannel(new PublishSporkEncoder());
		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		encoder.writeOutbound(PublishSpork.builder().gridSpork(spork).build());

		final ByteBuf frame = encoder.readOutbound();
		final int size = frame.readableBytes() - HEADER_SIZE;

		assertThat(size, lessThanOrEqualTo(CAPS.get(Packet.Type.PUBLISH_SPORK)));
		channel.writeInbound(frame);
		assertThat(((ByteBuf) channel.readInbound()).readableBytes(), equalTo(size));
	}

	/* A stream hands over whatever bytes have arrived, so a read may end anywhere inside the header */
	@Property
	public void shouldWaitForTheRestOfASplitFrame(@ForAll @IntRange(min = 1, max = HEADER_SIZE + 8) int split,
		@ForAll @Size(16) byte[] payload) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());
		final ByteBuf frame = frameOf(payload);
		final int firstPart = Math.min(split, frame.readableBytes() - 1);

		channel.writeInbound(frame.readRetainedSlice(firstPart));
		assertThat(channel.readInbound(), nullValue());

		channel.writeInbound(frame.readRetainedSlice(frame.readableBytes()));

		final ByteBuf decoded = channel.readInbound();

		assertThat(ByteBufUtil.getBytes(decoded), equalTo(payload));
		assertThat(channel.attr(Packet.KEY).get(), equalTo(Packet.Type.PING));
	}
}
