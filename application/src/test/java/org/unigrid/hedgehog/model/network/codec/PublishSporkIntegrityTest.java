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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import java.time.Instant;
import java.math.BigDecimal;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.ShortRange;
import net.jqwik.api.domains.Domain;
import static com.shazam.shazamcrest.matcher.Matchers.*;
import java.util.Optional;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.jqwik.SuiteDomain;
import org.unigrid.hedgehog.jqwik.NotNull;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSporkProvider;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.SignatureLog;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class PublishSporkIntegrityTest extends BaseCodecTest<PublishSpork> {
	private static final int FRAME_HEADER = 8;
	private static String[] networkKeys;

	private final GridSporkProvider gridSporkProvider = new GridSporkProvider();

	@Provide
	public Arbitrary<GridSpork> provideGridSpork(@ForAll GridSpork.Type gridSporkType,
		@ForAll @ShortRange(min = Short.MIN_VALUE) short flags, @ForAll @Size(min = 50, max = 60) byte[] signature,
		@ForAll Instant time, @ForAll Instant previousTime) {

		try {
			return gridSporkProvider.provide(gridSporkType, flags, signature, time, previousTime);
		} catch(IllegalArgumentException ex) {
			return Arbitraries.just(null);
		}
	}

	@SneakyThrows
	@Property(tries = 100)
	@Domain(SuiteDomain.class)
	public void shouldMatch(@ForAll("provideGridSpork") @NotNull GridSpork gridSpork,
		@Mocked ChannelHandlerContext context) {

		final PublishSpork publishSpork = PublishSpork.builder().gridSpork(gridSpork).build();
		final Optional<Pair<MutableInt, MutableInt>> sizes = getSizeHolder();

		final PublishSpork resultingPublishSpork = encodeDecode(publishSpork,
			new PublishSporkEncoder(), new PublishSporkDecoder(), context, sizes
		);

		assertThat(resultingPublishSpork, sameBeanAs(publishSpork));
		assertThat(resultingPublishSpork, equalTo(publishSpork));
		assertThat(sizes.get().getLeft(), equalTo(sizes.get().getRight()));
	}

	@SneakyThrows
	private GridSpork roundTrip(GridSpork gridSpork, ChannelHandlerContext context) {
		return encodeDecode(PublishSpork.builder().gridSpork(gridSpork).build(), new PublishSporkEncoder(),
			new PublishSporkDecoder(), context
		).getGridSpork();
	}

	@SneakyThrows
	@Example
	public void shouldAcceptRenewalsSentOverTheNetwork(@Mocked ChannelHandlerContext context) {
		final Signature proposer = new Signature();
		final Signature cosigner = new Signature();
		final MintSupply singleSigned = new MintSupply();
		final MintSupply.SporkData data = new MintSupply.SporkData();

		networkKeys = new String[] { proposer.getPublicKey(), cosigner.getPublicKey() };

		new MockUp<NetworkKey>() {
			@Mock public static String[] getPublicKeys() {
				return networkKeys;
			}
		};

		data.setMaxSupply(BigDecimal.TEN);
		singleSigned.setData(data);
		singleSigned.archive();
		singleSigned.sign(proposer.getPrivateKey());

		final GridSpork stored = roundTrip(singleSigned, context);
		final GridSpork renewed = SerializationUtils.clone(stored);

		renewed.renew();
		renewed.sign(proposer.getPrivateKey());
		renewed.cosign(cosigner.getPrivateKey());

		final GridSpork received = roundTrip(renewed, context);
		final GridSpork renewedAgain = SerializationUtils.clone(received);

		renewedAgain.renew();
		renewedAgain.sign(cosigner.getPrivateKey());
		renewedAgain.cosign(proposer.getPrivateKey());

		assertThat(received.canReplace(stored), is(true));
		assertThat(roundTrip(renewedAgain, context).canReplace(received), is(true));
	}

	/* The body of a frame of a spork with no log, its count of log entries changed to the one given */
	private static ByteBuf withLogOf(int entries) {
		final EmbeddedChannel encoder = new EmbeddedChannel(new PublishSporkEncoder());
		final MintSupply spork = new MintSupply();

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		spork.setPreviousData(spork.getData());
		spork.setSignature(new byte[60]);
		encoder.writeOutbound(PublishSpork.builder().gridSpork(spork).build());

		final ByteBuf frame = encoder.readOutbound();

		return frame.setInt(frame.writerIndex() - Integer.BYTES, entries).skipBytes(FRAME_HEADER);
	}

	@Property(tries = 20)
	public void shouldRefuseASignatureLogOfANegativeSize(@ForAll @IntRange(min = Integer.MIN_VALUE, max = -1)
		int entries) {

		assertThrows(CorruptedFrameException.class, () -> new PublishSporkDecoder().typedDecode(null,
			withLogOf(entries)));
	}

	@Property(tries = 20)
	public void shouldRefuseASignatureLogAboveTheCapBeforeReadingAnEntry(
		@ForAll @IntRange(min = SignatureLog.MAX_ENTRIES + 1) int entries) {

		final ByteBuf body = withLogOf(entries);

		assertThrows(CorruptedFrameException.class, () -> new PublishSporkDecoder().typedDecode(null, body));
		assertThat(body.readableBytes(), equalTo(0));
	}

	@SneakyThrows
	@Example
	public void shouldTakeAnEmptySignatureLog() {
		final ByteBuf body = withLogOf(0);

		assertThat(new PublishSporkDecoder().typedDecode(null, body).orElseThrow().getGridSpork().getSignatureLog()
			.isEmpty(), is(true));
		assertThat(body.readableBytes(), equalTo(0));
	}

	/* A spork of a type this node does not know is left alone, and what follows its type in the frame is not read as
	   another spork. The bytes after the type begin like a spork of a known type, as they could by chance. */
	@Example
	public void shouldSkipASporkOfAnUnknownTypeWholeAndDecodeTheNextPacket() {
		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), new PublishSporkDecoder(),
			new PingDecoder());
		final EmbeddedChannel encoder = new EmbeddedChannel(new PingEncoder());
		final ByteBuf spork = Unpooled.buffer().writeShort(FrameDecoder.MAGIC)
			.writeShort(Packet.Type.PUBLISH_SPORK.getValue()).writeInt(2 * Short.BYTES + 10)
			.writeShort(Short.MAX_VALUE).writeShort(GridSpork.Type.MINT_STORAGE.getValue()).writeZero(10);

		encoder.writeOutbound(Ping.builder().nanoTime(5).build());
		channel.writeInbound(spork);
		channel.writeInbound(encoder.<ByteBuf>readOutbound());

		assertThat(((Ping) channel.readInbound()).getNanoTime(), is(5L));
		assertThat(channel.<Object>readInbound(), nullValue());
	}

	/* A peer of a later release sends sporks of types this node does not know, which is no fault of either */
	@Example
	public void shouldNoteASporkOfAnUnknownTypeOnlyInTheDebugLog() {
		final ListAppender<ILoggingEvent> log = new ListAppender<>();
		final Logger logger = (Logger) LoggerFactory.getLogger(AbstractGridSporkDecoder.class);
		final Level level = logger.getLevel();
		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), new PublishSporkDecoder());
		final ByteBuf spork = Unpooled.buffer().writeShort(FrameDecoder.MAGIC)
			.writeShort(Packet.Type.PUBLISH_SPORK.getValue()).writeInt(Short.BYTES + 10)
			.writeShort(Short.MAX_VALUE).writeZero(10);

		log.start();
		logger.addAppender(log);
		logger.setLevel(Level.DEBUG);

		try {
			channel.writeInbound(spork);
			assertThat(channel.<Object>readInbound(), nullValue());
			assertThat(log.list.stream().map(ILoggingEvent::getLevel).toList(), contains(Level.DEBUG));
		} finally {
			logger.detachAppender(log);
			logger.setLevel(level);
		}
	}
}
