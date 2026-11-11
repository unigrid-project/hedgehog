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
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ReplayingDecoder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.model.network.QuicLoopback.PREVIOUS_RELEASE;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.QuicLoopback;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.spork.VestingStorage;
import org.unigrid.hedgehog.service.ledger.LedgerProtocol;
import org.unigrid.hedgehog.transport.handler.AbstractInboundHandler;
import org.unigrid.hedgehog.transport.handler.SporkProtocol;
import org.unigrid.hedgehog.transport.initializer.PeerPipeline;
import org.unigrid.hedgehog.transport.initializer.RegisterQuicChannelInitializer.Type;

/* Why the sporks of the ledger and the storage spork must never be sent to a node of an earlier release. This is what
   release 0.0.8 does with a frame of a spork whose type it does not know, as that release is out and cannot be changed.

   ReleasedSporkReader is a copy of the logic of that release, cut down to what decides the outcome: AbstractGridSporkDecoder
   reads the two bytes of the type, GridSpork.Type.get turns a number it does not list into UNDEFINED, which is logged
   (the error line is what `unknown` counts here) and gives an empty result, and AbstractReplayingDecoder.decode does
   nothing with the rest of the frame. A ReplayingDecoder that has consumed bytes without giving a packet is called
   again, so the rest of the frame is read as more types, two bytes at a time. A type it knows is read whole. The
   framing in front of it (FrameDecoder) is the one of the release for this packet type.

   The other way round, a node of release 0.0.8 sends the vesting spork on every new stream and every few minutes, as
   it knows the type. Each of its entries has since grown by the cliff, the percent and the block, which come before
   the amount, so this release cannot read the frame as it is, and it could not take the spork if it did: the bytes
   that the signatures cover have the new layout. vestingFrameOf008 writes the frame as that release does. A stream
   whose connection settled on the protocol of that release leaves such a frame whole and stays open. */
public class ReleasedSporkDecoderTest {
	private static final int FRAME_HEADER = 8;
	private static final short LEDGER_FEE = 1050;
	private static final short LEDGER_BLACKLIST = 1040;
	private static final short MINT_SUPPLY = 1010;
	private static final short STORAGE = 1030;
	private static final short VESTING_STORAGE = 1020;
	private static final short PUBLISH_SPORK = 2020;

	/* What a default node of this release offers */
	private static final String[] THIS_RELEASE = { Network.SPORK_PROTOCOL, PREVIOUS_RELEASE[0], PREVIOUS_RELEASE[1] };

	/* Times whose two-byte halves are no type that the release knows, so that the count below is exact */
	private static final long SIGNED_AT = 0x0000_0123_4567_89ABL;
	private static final long SIGNED_BEFORE = 0x0000_0111_2222_3333L;

	private static final class ReleasedSporkReader extends ReplayingDecoder<Object> {
		/* GridSpork.Type of release 0.0.8 without UNDEFINED */
		private static final Set<Short> KNOWN = Set.of((short) 1000, (short) 1010, (short) 1020, (short) 2001);

		private final List<Short> unknown = new ArrayList<>();
		private final List<Short> understood = new ArrayList<>();

		@Override
		protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) {
			final short type = in.readShort();

			if (KNOWN.contains(type)) {
				understood.add(type);
				internalBuffer().skipBytes(internalBuffer().readableBytes());
			} else {
				unknown.add(type);
			}
		}
	}

	@SneakyThrows
	private static ByteBuf frameOf(final GridSpork spork, final int signatureBytes) {
		final EmbeddedChannel encoding = new EmbeddedChannel(new PublishSporkEncoder());

		spork.setTimeStamp(Instant.ofEpochMilli(SIGNED_AT));
		spork.setPreviousTimeStamp(Instant.ofEpochMilli(SIGNED_BEFORE));
		spork.setPreviousData(spork.getData());
		spork.setSignature(new byte[signatureBytes]);
		encoding.writeOutbound(PublishSpork.builder().gridSpork(spork).build());
		return encoding.readOutbound();
	}

	/* A frame whose body has an odd number of bytes, which leaves a byte over when read two at a time */
	private static ByteBuf oddFrameOf(final GridSpork spork) {
		final ByteBuf frame = frameOf(spork, 60);

		return (frame.readableBytes() - FRAME_HEADER) % 2 == 1 ? frame : frameOf(spork, 61);
	}

	private static GridSpork ledgerFee() {
		final LedgerFee spork = new LedgerFee();
		final LedgerFee.SporkData data = new LedgerFee.SporkData();

		data.setMinimumTransferFee(BigDecimal.valueOf(1, 3));
		spork.setData(data);
		return spork;
	}

	private static GridSpork ledgerBlacklist() {
		final LedgerBlacklist spork = new LedgerBlacklist();
		final LedgerBlacklist.SporkData data = new LedgerBlacklist.SporkData();

		data.getLegacyAddresses().add("11".repeat(LedgerBlacklist.SporkData.HASH_BYTES));
		data.getLegacyAddresses().add("22".repeat(LedgerBlacklist.SporkData.HASH_BYTES));
		spork.setData(data);
		return spork;
	}

	private static GridSpork mintSupply() {
		final MintSupply spork = new MintSupply();
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.TEN);
		spork.setData(data);
		return spork;
	}

	private static GridSpork storage() {
		return new StorageSpork();
	}

	private static EmbeddedChannel releasedNode(final ReleasedSporkReader reader) {
		return new EmbeddedChannel(new FrameDecoder(), reader);
	}

	@Example
	public void shouldReadAnEarlierSporkAsTheOneSporkItIs() {
		final ReleasedSporkReader reader = new ReleasedSporkReader();

		releasedNode(reader).writeInbound(frameOf(mintSupply(), 60));

		assertThat(reader.understood, contains(MINT_SUPPLY));
		assertThat(reader.unknown, empty());
	}

	@Example
	public void shouldReadTheFrameOfTheFeeSporkAsManyTypesOfWhichTheFirstIsTheRealOne() {
		final ReleasedSporkReader reader = new ReleasedSporkReader();
		final ByteBuf frame = frameOf(ledgerFee(), 60);
		final int bytes = frame.readableBytes() - FRAME_HEADER;

		releasedNode(reader).writeInbound(frame);

		assertThat(reader.unknown.get(0), is(LEDGER_FEE));
		assertThat(reader.unknown.size(), equalTo(bytes / 2));
		assertThat(reader.unknown.size(), greaterThan(30));
		assertThat(reader.understood, empty());
	}

	@Example
	public void shouldReadTheFrameOfTheBlacklistAsManyTypesOfWhichTheFirstIsTheRealOne() {
		final ReleasedSporkReader reader = new ReleasedSporkReader();
		final ByteBuf frame = frameOf(ledgerBlacklist(), 60);
		final int bytes = frame.readableBytes() - FRAME_HEADER;

		releasedNode(reader).writeInbound(frame);

		assertThat(reader.unknown.get(0), is(LEDGER_BLACKLIST));
		assertThat(reader.unknown.size(), equalTo(bytes / 2));
		assertThat(reader.unknown.size(), greaterThan(30));
		assertThat(reader.understood, empty());
	}

	@Example
	public void shouldReadTheFrameOfTheStorageSporkAsManyTypesOfWhichTheFirstIsTheRealOne() {
		final ReleasedSporkReader reader = new ReleasedSporkReader();
		final ByteBuf frame = frameOf(storage(), 60);
		final int bytes = frame.readableBytes() - FRAME_HEADER;

		releasedNode(reader).writeInbound(frame);

		assertThat(reader.unknown.get(0), is(STORAGE));
		assertThat(reader.unknown.size(), equalTo(bytes / 2));
		assertThat(reader.unknown.size(), greaterThan(30));
		assertThat(reader.understood, empty());
	}

	@Example
	public void shouldLoseTheNextSporkWhenTheFrameBeforeItHasAnOddLength() {
		final ReleasedSporkReader reader = new ReleasedSporkReader();
		final EmbeddedChannel node = releasedNode(reader);

		node.writeInbound(oddFrameOf(ledgerFee()));
		node.writeInbound(frameOf(mintSupply(), 60));

		assertThat(reader.understood, empty());
		assertThat(reader.unknown, hasSize(greaterThan(60)));
	}

	@Example
	public void shouldReadTheNextSporkAfterAFrameOfEvenLengthButWithHundredsOfErrorsFirst() {
		final ReleasedSporkReader reader = new ReleasedSporkReader();
		final EmbeddedChannel node = releasedNode(reader);
		final ByteBuf even = (frameOf(ledgerFee(), 60).readableBytes() - FRAME_HEADER) % 2 == 0
			? frameOf(ledgerFee(), 60) : frameOf(ledgerFee(), 61);

		node.writeInbound(even);
		node.writeInbound(frameOf(mintSupply(), 60));

		assertThat(reader.understood, contains(MINT_SUPPLY));
		assertThat(reader.unknown.size(), greaterThan(30));
	}

	/* A vesting as release 0.0.8 wrote it: no cliff, no percent and no block */
	private record EarlierVesting(String address, long start, long seconds, int parts, String amount) {
	}

	private static void writeText(final ByteBuf out, final String text) {
		out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
		out.writeZero(1);
	}

	private static void writeSized(final ByteBuf out, final byte[] bytes) {
		out.writeShort(bytes.length);
		out.writeBytes(bytes);
	}

	/* The chunk of release 0.0.8: the count and five bytes of reserve, then for each vesting its address, its start and
	   duration in seconds and nanoseconds, its parts and its amount, which is empty when there is none */
	private static void writeVestingsOf008(final ByteBuf out, final List<EarlierVesting> vestings) {
		out.writeMedium(vestings.size());
		out.writeZero(5);

		for (final EarlierVesting vesting : vestings) {
			writeText(out, vesting.address());
			out.writeLong(vesting.start());
			out.writeInt(0);
			out.writeLong(vesting.seconds());
			out.writeInt(0);
			out.writeInt(vesting.parts());
			writeText(out, vesting.amount());
		}
	}

	/* The frame that a node of release 0.0.8 writes for a vesting spork that two board keys signed: the frame header,
	   the header of the spork, its data and its previous data, the signature and the cosignature, and a signature log
	   of no entries. The signatures are bytes of the length of the real ones. */
	private static ByteBuf vestingFrameOf008(final List<EarlierVesting> vestings, final List<EarlierVesting> before) {
		final Random random = new Random(8);
		final byte[] signature = new byte[71];
		final byte[] cosignature = new byte[70];
		final ByteBuf body = Unpooled.buffer();
		final ByteBuf frame = Unpooled.buffer();

		random.nextBytes(signature);
		random.nextBytes(cosignature);

		body.writeShort(VESTING_STORAGE);
		body.writeShort(0);
		body.writeZero(4);
		body.writeLong(SIGNED_AT);
		body.writeLong(SIGNED_BEFORE);
		body.writeZero(8);
		writeVestingsOf008(body, vestings);
		writeVestingsOf008(body, before);
		writeSized(body, signature);
		writeSized(body, cosignature);
		body.writeInt(0);

		frame.writeShort(FrameDecoder.MAGIC);
		frame.writeShort(PUBLISH_SPORK);
		frame.writeInt(body.readableBytes());
		frame.writeBytes(body);
		body.release();
		return frame;
	}

	private static List<EarlierVesting> vestingsOf008(final int count, final String amount) {
		final List<EarlierVesting> vestings = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			vestings.add(new EarlierVesting("UAddressOfVesting" + i, 1_767_225_600L + i, 2_592_000L, 12 + i, amount));
		}

		return vestings;
	}

	private static GridSpork vestingStorage() {
		final VestingStorage spork = new VestingStorage();
		final VestingStorage.SporkData.Vesting vesting = new VestingStorage.SporkData.Vesting();

		vesting.setStart(Instant.ofEpochSecond(1_767_225_600L));
		vesting.setDuration(Duration.ofSeconds(2_592_000L));
		vesting.setParts(12);
		vesting.setCliff(2);
		vesting.setPercent(10);
		vesting.setBlock(100);
		vesting.setAmount(new BigDecimal("1000"));
		spork.<VestingStorage.SporkData>getData().getVestingAddresses().put(new Address("UAddressOfVesting0"), vesting);
		return spork;
	}

	/* The stage that decodes the sporks of a stream, as the pipeline of a default node builds it */
	private static ChannelHandler sporkDecoderOfTheNode() {
		return PeerPipeline.handlers(Type.SERVER, false, false).stream().filter(PublishSporkDecoder.class::isInstance)
			.findFirst().orElseThrow();
	}

	private static Channel connectionThat(final boolean settledOnThisRelease) {
		final EmbeddedChannel connection = new EmbeddedChannel();

		connection.attr(LedgerProtocol.NEGOTIATED_KEY).set(false);
		connection.attr(SporkProtocol.NEGOTIATED_KEY).set(settledOnThisRelease);
		return connection;
	}

	/* A stream of this node as far as a spork is concerned: its frames are cut, its sporks are decoded, and a failure
	   closes it, as it does in AbstractInboundHandler. Its connection has settled on the protocol of release 0.0.8 or
	   on that of this release. */
	private static final class SporkStream {
		private final List<Throwable> failures = new ArrayList<>();
		private final List<GridSpork.Type> taken = new ArrayList<>();
		private final EmbeddedChannel stream;

		private SporkStream(final boolean settledOnThisRelease) {
			this(connectionThat(settledOnThisRelease));
		}

		private SporkStream(final Channel connection) {
			stream = new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false, new FrameDecoder(),
				sporkDecoderOfTheNode(), new AbstractInboundHandler<PublishSpork>(PublishSpork.class) {
					@Override
					public void typedChannelRead(final ChannelHandlerContext ctx, final PublishSpork packet) {
						taken.add(packet.getGridSpork().getType());
					}

					@Override
					public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause)
						throws Exception {

						failures.add(cause);
						super.exceptionCaught(ctx, cause);
					}
				});
		}

		private void receive(final ByteBuf frame) {
			stream.writeInbound(frame);
		}
	}

	@Provide
	Arbitrary<String> amounts() {
		return Arbitraries.of("", "1000", "250000.5", "0.00000001");
	}

	/* Nothing in the frame is wrong for the release that sent it, so it is no reason to close the stream, over which
	   the pings, the peers and the sporks that follow it still travel. A frame this node cannot read is left whole, and
	   what follows it is read. */
	@Example
	public void shouldLeaveTheVestingSporkOfRelease008UnreadAndTheStreamOpen() {
		final SporkStream node = new SporkStream(false);

		node.receive(vestingFrameOf008(vestingsOf008(1, "1000"), List.of()));

		assertThat("The stream failed with", node.failures, empty());
		assertThat("The stream is open", node.stream.isOpen(), is(true));
		assertThat("The sporks taken from the vesting frame", node.taken, empty());

		node.receive(frameOf(mintSupply(), 60));

		assertThat(node.taken, contains(GridSpork.Type.MINT_SUPPLY));
	}

	@Property(tries = 20)
	public void shouldLeaveEveryVestingSporkOfRelease008Unread(@ForAll @IntRange(min = 1, max = 4) int count,
		@ForAll @IntRange(min = 0, max = 3) int earlier, @ForAll("amounts") String amount) {

		final SporkStream node = new SporkStream(false);

		node.receive(vestingFrameOf008(vestingsOf008(count, amount), vestingsOf008(earlier, amount)));

		assertThat("The stream failed with", node.failures, empty());
		assertThat("The stream is open", node.stream.isOpen(), is(true));
		assertThat("The sporks taken from the vesting frame", node.taken, empty());
	}

	@Example
	public void shouldReadTheVestingSporkOfThisReleaseFromAStreamOfThisRelease() {
		final SporkStream node = new SporkStream(true);

		node.receive(frameOf(vestingStorage(), 60));

		assertThat(node.failures, empty());
		assertThat(node.taken, contains(GridSpork.Type.VESTING_STORAGE));
	}

	/* What a connection settled on is read from its handshake, the same at the end that dialled and the one that
	   was dialled */
	@SneakyThrows
	@Example
	public void shouldTellTheReleaseOfAVestingSporkByWhatTheConnectionSettledOn() {
		try (QuicLoopback earlier = QuicLoopback.between(THIS_RELEASE, PREVIOUS_RELEASE);
			QuicLoopback current = QuicLoopback.between(THIS_RELEASE, THIS_RELEASE)) {

			for (final Channel connection : List.of(earlier.serverEnd(), earlier.clientEnd())) {
				final SporkStream node = new SporkStream(connection);

				node.receive(vestingFrameOf008(vestingsOf008(1, "1000"), List.of()));
				node.receive(frameOf(mintSupply(), 60));

				assertThat("The stream failed with", node.failures, empty());
				assertThat(node.taken, contains(GridSpork.Type.MINT_SUPPLY));
			}

			for (final Channel connection : List.of(current.serverEnd(), current.clientEnd())) {
				final SporkStream node = new SporkStream(connection);

				node.receive(frameOf(vestingStorage(), 60));

				assertThat("The stream failed with", node.failures, empty());
				assertThat(node.taken, contains(GridSpork.Type.VESTING_STORAGE));
			}
		}
	}
}
