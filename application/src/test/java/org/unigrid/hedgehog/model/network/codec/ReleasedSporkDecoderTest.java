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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ReplayingDecoder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintSupply;

/* Why the sporks of the ledger must never be sent to a node of an earlier release. This is what release 0.0.8 does
   with a frame of a spork whose type it does not know, as that release is out and cannot be changed.

   ReleasedSporkReader is a copy of the logic of that release, cut down to what decides the outcome: AbstractGridSporkDecoder
   reads the two bytes of the type, GridSpork.Type.get turns a number it does not list into UNDEFINED, which is logged
   (the error line is what `unknown` counts here) and gives an empty result, and AbstractReplayingDecoder.decode does
   nothing with the rest of the frame. A ReplayingDecoder that has consumed bytes without giving a packet is called
   again, so the rest of the frame is read as more types, two bytes at a time. A type it knows is read whole. The
   framing in front of it (FrameDecoder) is the one of the release for this packet type. */
public class ReleasedSporkDecoderTest {
	private static final int FRAME_HEADER = 8;
	private static final short LEDGER_FEE = 1050;
	private static final short LEDGER_BLACKLIST = 1040;
	private static final short MINT_SUPPLY = 1010;

	/* Times whose two-byte halves are no type that the release knows, so that the count below is exact */
	private static final long SIGNED_AT = 0x0000_0123_4567_89ABL;
	private static final long SIGNED_BEFORE = 0x0000_0111_2222_3333L;

	private static final class ReleasedSporkReader extends ReplayingDecoder<Object> {
		/* GridSpork.Type of release 0.0.8 without UNDEFINED */
		private static final Set<Short> KNOWN = Set.of((short) 1000, (short) 1010, (short) 1020, (short) 1030,
			(short) 2001);

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
}
