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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.cdi.ProtectedInterceptor;
import org.unigrid.hedgehog.model.network.ChannelMap;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.model.network.packet.PublishPeers;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.service.ledger.LedgerProtocol;
import org.unigrid.hedgehog.transport.initializer.PeerPipeline;
import org.unigrid.hedgehog.transport.initializer.RegisterQuicChannelInitializer.Type;

/* A peer that sends something now and then and reads little or nothing: the stream to it is the node's own pipeline and
   schedules, and only what an example lets the peer read leaves its outbound side, as a peer's receive window that
   stays shut leaves it. The missed pings start over on every byte the peer sends, so they do not tell such a peer
   from one that is there: the stream is no longer written to once it takes no more, and is closed once too little
   left it through three periods in a row. */
@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class }, scan = false)
public class UnreadStreamTest extends BaseMockedWeldTest {
	private static final int PERIODS = 100;
	private static final int PEERS = 100;
	private static final int SLOW_PERIOD_IN_PINGS = PublishPeers.DISTRIBUTION_FREQUENCY_MINUTES * 60
		/ Ping.HEARTBEAT_SECONDS;
	private static final int PERIODS_AN_HOUR = 3600 / Ping.HEARTBEAT_SECONDS;
	private static final int UNWRITABLE_PERIODS = 3;
	private static final int KIBIBYTE = 1024;
	private static final int SLOWEST_BYTES_A_SECOND = 32 * KIBIBYTE;
	private static final int PUSH_BYTES = 10 * KIBIBYTE * KIBIBYTE;
	private static final FileSystem DATA = Jimfs.newFileSystem(Configuration.unix());

	@Inject private Topology topology;

	/* A stream whose peer takes off it only what it is let to: the rest waits in its outbound buffer */
	private static final class Unread extends EmbeddedChannel {
		private long allowance;

		private Unread(Channel connection, ChannelId id, List<ChannelHandler> handlers) {
			super(connection, id, true, false, handlers.toArray(new ChannelHandler[0]));
		}

		@Override
		protected void doWrite(ChannelOutboundBuffer in) {
			for (Object next = in.current(); allowance > 0 && next instanceof ByteBuf; next = in.current()) {
				final long taken = Math.min(allowance, ((ByteBuf) next).readableBytes());

				in.removeBytes(taken);
				allowance -= taken;
			}
		}

		/* The peer reads this much, or all there is when that is less */
		private void peerReads(long bytes) {
			allowance = bytes;
			flush();
			allowance = 0;
		}

		private long pending() {
			final ChannelOutboundBuffer waiting = unsafe().outboundBuffer();

			return Objects.isNull(waiting) ? 0 : waiting.totalPendingWriteBytes();
		}
	}

	/* What the schedules look up in the container: the topology of the test, a node with sporks that no one signed, which
	   stand for sporks that count, taken from the database of release 0.0.7, the largest at hand that came off a real
	   node, and none pending */
	@SneakyThrows
	private void withTheStateOfANode() {
		final Map<Class<?>, Object> held = new HashMap<>();

		try (InputStream in = UnreadStreamTest.class.getResourceAsStream("/spork/spork-0.0.7.db")) {
			final Path file = DATA.getPath("/spork-0.0.7.db");

			Files.write(file, in.readAllBytes());
			held.put(SporkDatabase.class, SporkDatabase.load(file));
		}

		held.put(Topology.class, topology);
		held.put(PendingSporks.class, new PendingSporks());

		new MockUp<CDIUtil>() {
			@Mock @SuppressWarnings({ "unchecked", "rawtypes" })
			void resolveAndRun(Class<?> type, Consumer consumer) {
				consumer.accept(held.get(type));
			}
		};

		new MockUp<GridSpork>() {
			@Mock public boolean isCounting() {
				return true;
			}
		};

		IntStream.rangeClosed(1, PEERS).forEach(n -> topology.addNode(Node.builder()
			.address(new InetSocketAddress("10.1." + n / 250 + "." + n % 250, 4000)).build()));
	}

	private static Unread streamOn(EmbeddedChannel connection) {
		connection.attr(LedgerProtocol.NEGOTIATED_KEY).set(true);
		return new Unread(connection, DefaultChannelId.newInstance(), PeerPipeline.handlers(Type.SERVER, false, false));
	}

	/* What the peer sends: the head of a frame that promises more than it ever sends, then a byte in each period */
	private static void peerSends(EmbeddedChannel stream, int period) {
		if (period == 1) {
			stream.writeInbound(Unpooled.buffer().writeShort(FrameDecoder.MAGIC)
				.writeShort(Packet.Type.PUBLISH_SPORK.getValue()).writeInt(Short.MAX_VALUE));
		}

		stream.writeInbound(Unpooled.wrappedBuffer(new byte[1]));
		stream.releaseInbound();
	}

	private static long grownBy(Unread stream, Runnable run) {
		final long before = stream.pending();

		run.run();
		return stream.pending() - before;
	}

	/* The schedules of a stream by what they send, in the order in which they are made */
	private static Map<String, Consumer<Channel>> schedules() {
		final Map<String, Consumer<Channel>> schedules = new LinkedHashMap<>();

		schedules.put("ping", new PingSchedule().getConsumer());
		schedules.put("peers", new PublishPeersSchedule().getConsumer());
		schedules.put("sporks", new PublishSporkSchedule().getConsumer());
		return schedules;
	}

	@Example
	public void closesTheStreamOfAPeerThatReadsNothingThreePeriodsAfterItTookNoMore() {
		withTheStateOfANode();

		final EmbeddedChannel connection = new EmbeddedChannel();
		final Unread stream = streamOn(connection);
		final Map<String, Consumer<Channel>> schedules = schedules();
		long pingRun = 0;
		long peersRun = 0;
		long sporksRun = 0;
		long mostInARun = 0;
		long mostHeld = 0;
		int unwritableFrom = 0;
		int closedAt = 0;

		for (int period = 1; period <= PERIODS && stream.isOpen(); period++) {
			peerSends(stream, period);
			unwritableFrom = unwritableFrom == 0 && !stream.isWritable() ? period : unwritableFrom;
			pingRun = Math.max(pingRun, grownBy(stream, () -> schedules.get("ping").accept(stream)));

			if (period % SLOW_PERIOD_IN_PINGS == 0 && stream.isOpen()) {
				peersRun = Math.max(peersRun, grownBy(stream, () -> schedules.get("peers").accept(stream)));
				sporksRun = Math.max(sporksRun, grownBy(stream, () -> schedules.get("sporks").accept(stream)));
			}

			mostInARun = Math.max(pingRun, Math.max(peersRun, sporksRun));
			mostHeld = Math.max(mostHeld, stream.pending());
			closedAt = stream.isOpen() ? 0 : period;
		}

		final long hour = pingRun * PERIODS_AN_HOUR
			+ (peersRun + sporksRun) * (PERIODS_AN_HOUR / SLOW_PERIOD_IN_PINGS);
		final String message = "after " + PERIODS + " periods of " + Ping.HEARTBEAT_SECONDS + " s the stream is "
			+ (stream.isOpen() ? "open" : "closed in period " + closedAt) + ", held " + mostHeld
			+ " bytes at most and took no more from period " + unwritableFrom + "; a run adds " + pingRun
			+ " (ping), " + peersRun + " (" + PEERS + " peers), " + sporksRun + " (sporks) bytes, so " + hour
			+ " bytes an hour";

		assertThat(message, stream.isOpen(), is(false));
		assertThat(message, connection.isOpen(), is(false));
		assertThat(message, closedAt - unwritableFrom, equalTo(UNWRITABLE_PERIODS));
		assertThat(message, mostHeld, lessThanOrEqualTo(stream.config().getWriteBufferHighWaterMark() + mostInARun));
	}

	/* Each schedule sends what holds when it runs, so what it leaves out is made up for by its next run */
	@Example
	public void writesNothingOfAScheduleToAStreamThatTakesNoMore() {
		withTheStateOfANode();

		final Unread stream = streamOn(new EmbeddedChannel());

		schedules().forEach((sends, schedule) -> assertThat(sends + " to a stream that takes them",
			grownBy(stream, () -> schedule.accept(stream)), greaterThan(0L)));

		stream.writeAndFlush(Unpooled.wrappedBuffer(new byte[stream.config().getWriteBufferHighWaterMark()]));
		assertThat(stream.isWritable(), is(false));

		schedules().forEach((sends, schedule) -> assertThat(sends + " to a stream that takes no more",
			grownBy(stream, () -> schedule.accept(stream)), is(0L)));

		assertThat(stream.isOpen(), is(true));
	}

	/* The periods that the stream takes no more after a push of ten mebibytes to a peer that reads this fast and sends
	   a byte in each, until what is left of the push is little enough for the stream to take more, or it is closed,
	   and a hundred at most */
	private static int periodsOfAPush(Unread stream, int bytesASecond) {
		final Consumer<Channel> ping = new PingSchedule().getConsumer();
		int periods = 0;

		ping.accept(stream);
		stream.writeAndFlush(Unpooled.wrappedBuffer(new byte[PUSH_BYTES]));

		while (stream.isOpen() && !stream.isWritable() && periods < PERIODS) {
			periods++;
			peerSends(stream, periods);
			stream.peerReads((long) bytesASecond * Ping.HEARTBEAT_SECONDS);
			ping.accept(stream);
		}

		return periods;
	}

	/* A peer on a slow link takes minutes to read a large spork, and reads all the while */
	@Property(tries = 25)
	public void keepsAPeerThatReadsNoSlowerThan32KibibytesASecondHoweverLongAPushTakes(
		@ForAll @IntRange(min = SLOWEST_BYTES_A_SECOND, max = 4 * SLOWEST_BYTES_A_SECOND) int bytesASecond) {

		final EmbeddedChannel connection = new EmbeddedChannel();
		final Unread stream = streamOn(connection);
		final int periods = periodsOfAPush(stream, bytesASecond);
		final String message = "a peer that reads " + bytesASecond + " bytes a second, after " + periods + " periods";

		assertThat(message, stream.isOpen(), is(true));
		assertThat(message, connection.isOpen(), is(true));
		assertThat(message, stream.isWritable(), is(true));
		assertThat(message, periods, greaterThanOrEqualTo(PUSH_BYTES / (bytesASecond * Ping.HEARTBEAT_SECONDS)));
	}

	@Property(tries = 25)
	public void closesAPeerThatReadsSlowerThan32KibibytesASecondThreePeriodsAfterItsStreamTookNoMore(
		@ForAll @IntRange(min = 0, max = SLOWEST_BYTES_A_SECOND - KIBIBYTE) int bytesASecond) {

		final EmbeddedChannel connection = new EmbeddedChannel();
		final Unread stream = streamOn(connection);
		final int periods = periodsOfAPush(stream, bytesASecond);
		final String message = "a peer that reads " + bytesASecond + " bytes a second, after " + periods + " periods";

		/* The run of the first period is the first to find that the stream takes no more */
		assertThat(message, stream.isOpen(), is(false));
		assertThat(message, connection.isOpen(), is(false));
		assertThat(message, periods, equalTo(1 + UNWRITABLE_PERIODS));
	}
}
