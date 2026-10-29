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
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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
		Packet.Type.LEAVE_CHAIN);

	/* Types that no codec reads or writes */
	private static final Set<Packet.Type> UNREAD_TYPES = Set.of(Packet.Type.UNDEFINED,
		Packet.Type.PUBLISH_NODE_DETAILS, Packet.Type.ASK_SPORKS, Packet.Type.GROW_SPORK);

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
