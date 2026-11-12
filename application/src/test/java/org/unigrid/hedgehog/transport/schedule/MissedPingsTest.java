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

package org.unigrid.hedgehog.transport.schedule;

import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.ConnectionLimiter;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.transport.handler.PeerActivityHandler;
import org.unigrid.hedgehog.transport.handler.PingChannelHandler;
import org.unigrid.hedgehog.transport.initializer.PeerPipeline;
import org.unigrid.hedgehog.transport.initializer.RegisterQuicChannelInitializer.Type;
import org.unigrid.hedgehog.model.network.packet.Ping;

/* The pings that a peer leaves unanswered, counted by the schedule that sends them and started again by anything that
   arrives on the stream, an answer among it, on a stream of its own whose parent stands for the QUIC connection and
   whose pipeline starts as a node's does. The network examples of PingScheduleTest and PingChannelHandlerTest fail
   once another example of their class has run before them, so these are kept apart. A frame that is not whole is
   held by the stream while the bytes of it arrive, and the bytes count as a sign of life, so the pings never end a
   frame that crawls in: what arrives of it is looked at once in every period, and its stream is closed once the
   frame is 48 KiB behind the 16 KiB that are to arrive of it in every period, however much its header says it
   holds. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class MissedPingsTest {
	private static final int DATA_BYTES = 1024;
	private static final int HEADER_BYTES = 8;
	private static final int MEBIBYTE = 1024 * 1024;

	/* The most that the frame decoder takes of the body of a spork frame, which a node of this release reads */
	private static final int SPORK_CAP = 16 * MEBIBYTE;
	private static final int STILL_TO_COME = 4096;
	private static final int HOUR_IN_PERIODS = 3600 / Ping.HEARTBEAT_SECONDS;
	private static final int ONE_MEBIBYTE_A_PERIOD = 9;

	/* The periods of a byte each after which the stream of a frame is closed, written out here and not taken from
	   the decoder: a frame that is 48 KiB behind the 16 KiB that are to arrive of it in every period closes its
	   stream, and a byte in each of three periods leaves it three bytes short of that */
	private static final int PERIODS_OF_A_BYTE = 4;
	private static final int SLOWLY_A_PERIOD = 17 * 1024;
	private static final int PERIODS = 100;

	/* The round trip of an answer goes to the node in the topology, which a stream of its own is in none of */
	@BeforeTry
	public void withoutATopology() {
		new MockUp<CDIUtil>() {
			@Mock void resolveAndRun(Class<?> type, Consumer<?> consumer) { }
		};
	}

	private static EmbeddedChannel streamOf(EmbeddedChannel connection) {
		return new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false, new PeerActivityHandler(),
			new PingChannelHandler());
	}

	private static void answer(EmbeddedChannel stream) {
		stream.writeInbound(Ping.builder().response(true).build());
	}

	/* A piece of a frame, as the bytes of one arrive while it streams in */
	private static void receive(EmbeddedChannel stream) {
		stream.writeInbound(Unpooled.wrappedBuffer(new byte[DATA_BYTES]));
		stream.releaseInbound();
	}

	/* The main stream of a node run without --preview, as the server builds it, on the allocator given, so that what
	   the stream holds of a frame that is not whole can be read off it. Its clock stands still but for the periods
	   that an example lets pass. */
	private static EmbeddedChannel defaultNodeStreamOf(EmbeddedChannel connection, UnpooledByteBufAllocator allocator) {
		final EmbeddedChannel stream = new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false,
			PeerPipeline.handlers(Type.SERVER, false, false).toArray(ChannelHandler[]::new));

		stream.config().setAllocator(allocator);
		stream.freezeTime();
		return stream;
	}

	private static void writeZeros(EmbeddedChannel stream, UnpooledByteBufAllocator allocator, int bytes) {
		for (int written = 0; written < bytes; written += MEBIBYTE) {
			stream.writeInbound(allocator.heapBuffer(MEBIBYTE).writeZero(Math.min(MEBIBYTE, bytes - written)));
		}
	}

	/* The head of a spork frame that promises the most a spork frame may hold, and the first bytes of its body */
	private static void beginSporkFrame(EmbeddedChannel stream, UnpooledByteBufAllocator allocator, int bodyBytes) {
		stream.writeInbound(allocator.heapBuffer(HEADER_BYTES).writeShort(FrameDecoder.MAGIC)
			.writeShort(Packet.Type.PUBLISH_SPORK.getValue()).writeInt(SPORK_CAP));
		writeZeros(stream, allocator, bodyBytes);
	}

	/* One period of the ping schedule: the ping that the node writes at its start is taken off the stream, and then
	   the time of the period passes on the clock of the stream, with whatever the stream has set for that time */
	private static void period(EmbeddedChannel stream, Consumer<Channel> ping) {
		ping.accept(stream);
		stream.releaseOutbound();
		stream.advanceTimeBy(Ping.HEARTBEAT_SECONDS, TimeUnit.SECONDS);
		stream.runScheduledPendingTasks();
	}

	@Example
	public void countsEachPingThatIsStillUnansweredWhenTheNextIsSent() {
		final EmbeddedChannel connection = new EmbeddedChannel();
		final EmbeddedChannel stream = streamOf(connection);
		final Consumer<Channel> ping = new PingSchedule().getConsumer();

		ping.accept(stream);
		assertThat(stream.attr(Ping.MISSED_PINGS_KEY).get(), is(nullValue()));

		for (int missed = 1; missed < Ping.MISSED_PINGS_BEFORE_CLOSE; missed++) {
			ping.accept(stream);
			assertThat(stream.attr(Ping.MISSED_PINGS_KEY).get(), is(missed));
		}

		assertThat(connection.isOpen(), is(true));
		assertThat(stream.outboundMessages(), hasSize(Ping.MISSED_PINGS_BEFORE_CLOSE));
	}

	@Example
	public void closesTheConnectionOfAStreamAfterTheThirdUnansweredPingInARow() {
		final EmbeddedChannel connection = new EmbeddedChannel();
		final EmbeddedChannel stream = streamOf(connection);
		final Consumer<Channel> ping = new PingSchedule().getConsumer();

		for (int sent = 0; sent < Ping.MISSED_PINGS_BEFORE_CLOSE; sent++) {
			ping.accept(stream);
		}

		assertThat(connection.isOpen(), is(true));
		ping.accept(stream);
		assertThat(connection.isOpen(), is(false));
		assertThat(stream.isOpen(), is(false));
	}

	@Example
	public void keepsTheConnectionOfAPeerThatAnswers() {
		final EmbeddedChannel connection = new EmbeddedChannel();
		final EmbeddedChannel stream = streamOf(connection);
		final Consumer<Channel> ping = new PingSchedule().getConsumer();

		for (int sent = 0; sent < Ping.MISSED_PINGS_BEFORE_CLOSE * 4; sent++) {
			ping.accept(stream);
			answer(stream);
		}

		assertThat(connection.isOpen(), is(true));
		assertThat(stream.attr(Ping.MISSED_PINGS_KEY).get(), is(0));
	}

	/* Only misses in a row count: an answer in between starts the count again */
	@Example
	public void startsTheCountAgainAfterAnAnswer() {
		final EmbeddedChannel connection = new EmbeddedChannel();
		final EmbeddedChannel stream = streamOf(connection);
		final Consumer<Channel> ping = new PingSchedule().getConsumer();

		for (int round = 0; round < 3; round++) {
			for (int sent = 0; sent < Ping.MISSED_PINGS_BEFORE_CLOSE; sent++) {
				ping.accept(stream);
			}

			assertThat(stream.attr(Ping.MISSED_PINGS_KEY).get(), is(Ping.MISSED_PINGS_BEFORE_CLOSE - 1));
			answer(stream);
			assertThat(stream.attr(Ping.MISSED_PINGS_KEY).get(), is(0));
		}

		assertThat(connection.isOpen(), is(true));
	}

	/* An answer can wait on the stream behind a large frame, whose bytes show meanwhile that the peer is there */
	@Example
	public void keepsTheConnectionOfAPeerThatSendsAnythingBetweenMisses() {
		final EmbeddedChannel connection = new EmbeddedChannel();
		final EmbeddedChannel stream = streamOf(connection);
		final Consumer<Channel> ping = new PingSchedule().getConsumer();

		for (int sent = 0; sent < Ping.MISSED_PINGS_BEFORE_CLOSE * 4; sent++) {
			ping.accept(stream);
			receive(stream);
			assertThat(stream.attr(Ping.MISSED_PINGS_KEY).get(), is(0));
		}

		assertThat(connection.isOpen(), is(true));
		assertThat(stream.isOpen(), is(true));
	}

	@Example
	public void closesTheConnectionOfAPeerThatSendsNothingForThreeRounds() {
		final EmbeddedChannel connection = new EmbeddedChannel();
		final EmbeddedChannel stream = streamOf(connection);
		final Consumer<Channel> ping = new PingSchedule().getConsumer();

		ping.accept(stream);
		receive(stream);

		for (int sent = 1; sent < Ping.MISSED_PINGS_BEFORE_CLOSE; sent++) {
			ping.accept(stream);
		}

		assertThat(connection.isOpen(), is(true));
		ping.accept(stream);
		assertThat(connection.isOpen(), is(false));
		assertThat(stream.isOpen(), is(false));
	}

	/* A frame that a peer leaves unfinished, with nothing more from it, is held until the pings go unanswered: the
	   body here arrives in the first period, so the fourth ping finds three unanswered a period before the third
	   ends in which nothing of the frame arrived */
	@Example
	public void closesTheStreamOfAPeerThatLeavesASporkFrameUnfinishedAndSendsNothingMore() {
		final UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false, true);
		final EmbeddedChannel connection = new EmbeddedChannel();
		final EmbeddedChannel stream = defaultNodeStreamOf(connection, allocator);
		final Consumer<Channel> ping = new PingSchedule().getConsumer();

		try {
			beginSporkFrame(stream, allocator, SPORK_CAP - STILL_TO_COME);

			for (int sent = 0; sent <= Ping.MISSED_PINGS_BEFORE_CLOSE; sent++) {
				assertThat(stream.isOpen(), is(true));
				period(stream, ping);
			}

			assertThat(connection.isOpen(), is(false));
			assertThat(stream.isOpen(), is(false));
		} finally {
			stream.finishAndReleaseAll();
			connection.finishAndReleaseAll();
		}
	}

	/* The peer here sends a byte in each period, never answers a ping, and never finishes the frame: the bytes are as
	   few as can be, and the stream holds the most that a frame of the type may, in memory that the peer has paid for
	   with the bytes alone. A connection is served one main stream, and eight connections come from one address.
	   The bytes keep the pings from closing anything, so it is what arrives of the frame that ends this: the body
	   arrives in the first period, and the stream is closed at the end of the fourth after it, in each of which a
	   byte arrived. Its connection is not, and what the stream held of the frame is freed with it. */
	@Example
	public void closesTheStreamOfAPeerThatLetsASporkFrameCrawlInAByteAPeriod() {
		final UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false, true);
		final EmbeddedChannel connection = new EmbeddedChannel();
		final EmbeddedChannel stream = defaultNodeStreamOf(connection, allocator);
		final Consumer<Channel> ping = new PingSchedule().getConsumer();

		try {
			beginSporkFrame(stream, allocator, SPORK_CAP - STILL_TO_COME);

			int periods = 0;

			while (stream.isOpen() && periods < HOUR_IN_PERIODS) {
				stream.writeInbound(allocator.heapBuffer(1).writeByte(0));
				period(stream, ping);
				periods++;
			}

			final long held = allocator.metric().usedHeapMemory();
			final String message = "after " + periods + " periods of " + Ping.HEARTBEAT_SECONDS + " s ("
				+ periods * Ping.HEARTBEAT_SECONDS + " s, the idle timeout of a connection being "
				+ Network.IDLE_TIME_MINUTES * 60 + " s) the stream is " + (stream.isOpen() ? "open" : "closed")
				+ " and holds " + held + " bytes of a spork frame of " + SPORK_CAP + " bytes, of which "
				+ (SPORK_CAP - STILL_TO_COME + periods) + " have arrived, " + periods + " of them a byte in a period; with "
				+ "one main stream on each of " + ConnectionLimiter.MAX_PER_ADDRESS + " connections that is "
				+ held * ConnectionLimiter.MAX_PER_ADDRESS + " bytes for one address";

			assertThat(message, stream.isOpen(), is(false));
			assertThat(message, periods, is(1 + PERIODS_OF_A_BYTE));
			assertThat(message, held, is(0L));
			assertThat(message, connection.isOpen(), is(true));
		} finally {
			stream.finishAndReleaseAll();
			connection.finishAndReleaseAll();
		}
	}

	/* A frame of the greatest size that streams in at a good rate, a mebibyte in a period, is not one to close, long
	   as it takes */
	@Example
	public void keepsTheStreamOfAPeerThatDeliversASporkFrameAtAGoodRate() {
		final UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false, true);
		final EmbeddedChannel connection = new EmbeddedChannel();
		final EmbeddedChannel stream = defaultNodeStreamOf(connection, allocator);
		final Consumer<Channel> ping = new PingSchedule().getConsumer();

		try {
			beginSporkFrame(stream, allocator, 0);

			for (int periods = 0; periods < ONE_MEBIBYTE_A_PERIOD; periods++) {
				period(stream, ping);
				writeZeros(stream, allocator, MEBIBYTE);
			}

			assertThat(stream.isOpen(), is(true));
			assertThat(connection.isOpen(), is(true));
		} finally {
			stream.finishAndReleaseAll();
			connection.finishAndReleaseAll();
		}
	}

	/* A peer on a slow link, which delivers 17 KiB of the frame in a period and would need more than five hours for
	   all of it, is kept as well: no ping of it is answered, as an answer waits behind the frame, and what arrives
	   of the frame is enough in every period */
	@Example
	public void keepsTheStreamOfAPeerThatDeliversASporkFrameSlowly() {
		final UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false, true);
		final EmbeddedChannel connection = new EmbeddedChannel();
		final EmbeddedChannel stream = defaultNodeStreamOf(connection, allocator);
		final Consumer<Channel> ping = new PingSchedule().getConsumer();

		try {
			beginSporkFrame(stream, allocator, 0);

			int periods = 0;

			while (stream.isOpen() && periods < PERIODS) {
				writeZeros(stream, allocator, SLOWLY_A_PERIOD);
				period(stream, ping);
				periods++;
			}

			final String message = "after " + periods + " periods of " + Ping.HEARTBEAT_SECONDS + " s, in each of which "
				+ SLOWLY_A_PERIOD + " bytes of a spork frame of " + SPORK_CAP + " bytes arrived";

			assertThat(message, stream.isOpen(), is(true));
			assertThat(message, connection.isOpen(), is(true));
			assertThat(message, periods, is(PERIODS));
		} finally {
			stream.finishAndReleaseAll();
			connection.finishAndReleaseAll();
		}
	}
}
