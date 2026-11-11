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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntToLongFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
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
   from one that is there: the stream is no longer written to once it takes no more, and is closed once what left
   it is 48 KiB behind the 16 KiB that are to leave it in every period. */
@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class }, scan = false)
public class UnreadStreamTest extends BaseMockedWeldTest {
	private static final int PERIODS = 100;
	private static final int PEERS = 100;
	private static final int SLOW_PERIOD_IN_PINGS = PublishPeers.DISTRIBUTION_FREQUENCY_MINUTES * 60
		/ Ping.HEARTBEAT_SECONDS;
	private static final int PERIODS_AN_HOUR = 3600 / Ping.HEARTBEAT_SECONDS;

	/* What is to leave a stream that takes no more in a period, the periods of nothing after which it is closed, and
	   how far behind its peer is by then, which closes the stream however the peer came to be that far behind,
	   written out here and not taken from the schedule */
	private static final int KIBIBYTE = 1024;
	private static final int LEAST_BYTES_A_PERIOD = 16 * KIBIBYTE;
	private static final int EMPTY_PERIODS = 3;
	private static final int BYTES_BEHIND_TO_CLOSE = 48 * KIBIBYTE;
	private static final int PUSH_BYTES = 10 * KIBIBYTE * KIBIBYTE;

	/* The periods in which a peer that reads 16 KiB in each has read a push */
	private static final int MOST_PERIODS_OF_A_PUSH = PUSH_BYTES / LEAST_BYTES_A_PERIOD;
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
		assertThat(message, closedAt - unwritableFrom, equalTo(EMPTY_PERIODS));
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

	/* The periods that the stream takes no more after a push of ten mebibytes to a peer that sends a byte in each and
	   reads in each what is given for it, until what is left of the push is little enough for the stream to take
	   more, or it is closed. The run of the first period is the first to find that the stream takes no more, so
	   the peer is judged by what it reads from the second on. */
	private static int periodsOfAPush(Unread stream, IntToLongFunction readIn) {
		final Consumer<Channel> ping = new PingSchedule().getConsumer();
		int periods = 0;

		ping.accept(stream);
		stream.writeAndFlush(Unpooled.wrappedBuffer(new byte[PUSH_BYTES]));

		while (stream.isOpen() && !stream.isWritable() && periods <= MOST_PERIODS_OF_A_PUSH) {
			periods++;
			peerSends(stream, periods);
			stream.peerReads(readIn.applyAsLong(periods));
			ping.accept(stream);
		}

		return periods;
	}

	/* The push is done once the stream takes more again, which it does no sooner than the peer has read all but
	   what a stream may hold and still take more */
	private static void assertKeptUntilAPushIsDone(String reads, IntToLongFunction readIn) {
		final EmbeddedChannel connection = new EmbeddedChannel();
		final Unread stream = streamOn(connection);

		try {
			final int periods = periodsOfAPush(stream, readIn);
			final String message = "a peer that reads " + reads + ", after " + periods + " periods";

			assertThat(message, stream.isOpen(), is(true));
			assertThat(message, connection.isOpen(), is(true));
			assertThat(message, stream.isWritable(), is(true));
			assertThat(message, IntStream.rangeClosed(1, periods).mapToLong(readIn).sum(), greaterThanOrEqualTo(
				(long) PUSH_BYTES - stream.config().getWriteBufferLowWaterMark()));
		} finally {
			stream.finishAndReleaseAll();
			connection.finishAndReleaseAll();
		}
	}

	private static void assertKeptUntilAPushIsDone(int bytesAPeriod) {
		assertKeptUntilAPushIsDone(bytesAPeriod + " bytes a period", period -> bytesAPeriod);
	}

	private static void assertClosedInPeriod(int closedIn, String reads, IntToLongFunction readIn) {
		final EmbeddedChannel connection = new EmbeddedChannel();
		final Unread stream = streamOn(connection);

		try {
			final int periods = periodsOfAPush(stream, readIn);
			final String message = "a peer that reads " + reads + ", after " + periods + " periods";

			assertThat(message, stream.isOpen(), is(false));
			assertThat(message, connection.isOpen(), is(false));
			assertThat(message, periods, equalTo(closedIn));
		} finally {
			stream.finishAndReleaseAll();
			connection.finishAndReleaseAll();
		}
	}

	/* A peer on a slow link takes minutes to read a large spork, and reads all the while */
	@Property(tries = 25)
	public void keepsAPeerThatReadsNoLessThan16KibibytesAPeriodHoweverLongAPushTakes(
		@ForAll @IntRange(min = LEAST_BYTES_A_PERIOD, max = 160 * LEAST_BYTES_A_PERIOD) int bytesAPeriod) {

		assertKeptUntilAPushIsDone(bytesAPeriod);
	}

	/* The period in which the stream of a peer is closed that reads in each period of a push what is given for it, or
	   none when the stream is open still after the last of them. The peer reads the rest of the push in the period
	   after that, which ends the push. */
	private static int closedIn(int... read) {
		final EmbeddedChannel connection = new EmbeddedChannel();
		final Unread stream = streamOn(connection);

		try {
			final int periods = periodsOfAPush(stream,
				period -> period <= read.length ? read[period - 1] : PUSH_BYTES);

			if (stream.isOpen()) {
				assertThat("the periods of a push whose rest the peer reads", periods, is(read.length + 1));
			}

			return stream.isOpen() ? 0 : periods;
		} finally {
			stream.finishAndReleaseAll();
			connection.finishAndReleaseAll();
		}
	}

	/* The period in which a stream is to be closed, or none: the first by whose end its peer is 48 KiB or more behind
	   16 KiB a period over some run of periods that ends with it. The first period of a push is part of no run, as
	   the stream still took more in some of it. Each run is added up on its own here, where the schedule carries one
	   figure from a period to the next. */
	private static int periodToCloseIn(int... read) {
		for (int last = 1; last < read.length; last++) {
			long behind = 0;

			for (int first = last; first >= 1; first--) {
				behind += LEAST_BYTES_A_PERIOD - read[first];

				if (behind >= BYTES_BEHIND_TO_CLOSE) {
					return last + 1;
				}
			}
		}

		return 0;
	}

	/* Periods of nothing, of too little and of enough, in any order */
	@Provide
	Arbitrary<int[]> periodsOfAnything() {
		return Arbitraries.oneOf(Arbitraries.just(0), Arbitraries.integers().between(0, LEAST_BYTES_A_PERIOD - 1),
			Arbitraries.integers().between(LEAST_BYTES_A_PERIOD, EMPTY_PERIODS * LEAST_BYTES_A_PERIOD))
			.array(int[].class).ofMinSize(1).ofMaxSize(PERIODS);
	}

	/* Two periods at most of too little, and one that brings all that they were short of and at times more */
	private static List<Integer> madeUpFor(List<Integer> tooLittle, int more) {
		final int due = (tooLittle.size() + 1) * LEAST_BYTES_A_PERIOD;
		final int read = tooLittle.stream().mapToInt(Integer::intValue).sum();

		return Stream.concat(tooLittle.stream(), Stream.of(due - read + more)).toList();
	}

	@Provide
	Arbitrary<int[]> periodsThatAreMadeUpFor() {
		final Arbitrary<List<Integer>> tooLittle = Arbitraries.integers().between(0, LEAST_BYTES_A_PERIOD - 1).list()
			.ofMaxSize(EMPTY_PERIODS - 1);

		return Combinators.combine(tooLittle, Arbitraries.integers().between(0, LEAST_BYTES_A_PERIOD))
			.as(UnreadStreamTest::madeUpFor).list().ofMinSize(1).ofMaxSize(PERIODS / EMPTY_PERIODS)
			.map(runs -> runs.stream().flatMap(List::stream).mapToInt(Integer::intValue).toArray());
	}

	/* The run of the first period is the first to find that the stream takes no more. What a period is short of
	   16 KiB adds up from one to the next, so the less a peer reads in a period, the sooner it is 48 KiB behind:
	   after three periods of nothing, and after 48 of a kibibyte less than 16 KiB */
	@Property(tries = 25)
	public void closesAPeerThatReadsNoMoreThan15KibibytesAPeriodOnceItIs48KibibytesBehind(
		@ForAll @IntRange(min = 0, max = 15 * KIBIBYTE) int bytesAPeriod) {

		final int behindAPeriod = LEAST_BYTES_A_PERIOD - bytesAPeriod;

		assertClosedInPeriod(1 + (BYTES_BEHIND_TO_CLOSE + behindAPeriod - 1) / behindAPeriod,
			bytesAPeriod + " bytes a period", period -> bytesAPeriod);
	}

	@Example
	public void closesAPeerThatReads15KibibytesAPeriodOnceItIs48KibibytesBehind() {
		assertClosedInPeriod(1 + BYTES_BEHIND_TO_CLOSE / KIBIBYTE, "15 KiB a period", period -> 15 * KIBIBYTE);
	}

	@Example
	public void keepsAPeerThatReads17KibibytesAPeriod() {
		assertKeptUntilAPushIsDone(17 * KIBIBYTE);
	}

	/* A byte short in every period is a byte further behind with each, which closes no stream before a push is read:
	   ten mebibytes are read at this rate in 640 periods, by when the peer is under a kibibyte behind, where 48 KiB
	   would take 49152 periods */
	@Example
	public void keepsAPeerThatReadsAByteLessThan16KibibytesAPeriodUntilAPushIsDone() {
		assertKeptUntilAPushIsDone(LEAST_BYTES_A_PERIOD - 1);
	}

	@Example
	public void keepsAPeerThatReads16KibibytesAPeriod() {
		assertKeptUntilAPushIsDone(LEAST_BYTES_A_PERIOD);
	}

	/* A sixth of what a link of 256 kbit/s carries */
	@Example
	public void keepsAPeerThatReads5KibibytesASecondUntilAPushOfTenMebibytesIsDone() {
		assertKeptUntilAPushIsDone(5 * KIBIBYTE * Ping.HEARTBEAT_SECONDS);
	}

	/* A period of 16 KiB makes up for nothing, so the periods of nothing between such periods add up as if they came
	   one after the other: a peer that reads 16 KiB in every third period is closed in the fourth after the first,
	   and does not keep what was pushed to it for the ten hours and more that it would take to read it like that */
	@Example
	public void closesAPeerThatReads16KibibytesInEveryThirdPeriodOnly() {
		assertClosedInPeriod(1 + EMPTY_PERIODS + 1, "16 KiB in every third period and nothing in any other",
			period -> period % 3 == 1 && period > 1 ? LEAST_BYTES_A_PERIOD : 0);
	}

	/* A period of more than 16 KiB makes up for what it brought above them and starts nothing again: the peer reads
	   nothing in the two periods after the first, 20 KiB in the next, which leaves it 28 KiB behind, and nothing
	   after it, which closes its stream two periods later */
	@Example
	public void leavesAPeerAsFarBehindAsAPeriodOfEnoughDidNotMakeUpFor() {
		final int readsIn = 1 + EMPTY_PERIODS;

		assertClosedInPeriod(readsIn + 2, "20 KiB in period " + readsIn + " and nothing in any other",
			period -> period == readsIn ? 20 * KIBIBYTE : 0);
	}

	/* What a period brings above what the peer is behind is not kept for later, so a peer that read fast and then
	   reads nothing keeps what was pushed to it no longer than any other */
	@Example
	public void closesAPeerAfterThreePeriodsOfNothingWhateverItReadBeforeThem() {
		assertClosedInPeriod(2 + EMPTY_PERIODS, "64 KiB in period 2 and nothing in any other",
			period -> period == 2 ? 4 * LEAST_BYTES_A_PERIOD : 0);
	}

	/* The stream is closed by the byte that puts its peer 48 KiB behind and by none before it: the peer reads nothing
	   in the two periods after the first and a byte in the next, which leaves it a byte short of that for as long as
	   it reads 16 KiB a period, and the first period of a byte less closes the stream */
	@Example
	public void closesAPeerWithTheByteThatPutsIt48KibibytesBehind() {
		final int[] aByteShort = IntStream.concat(IntStream.of(0, 0, 0, 1),
			IntStream.generate(() -> LEAST_BYTES_A_PERIOD).limit(PERIODS)).toArray();
		final int[] behind = IntStream.concat(IntStream.of(aByteShort), IntStream.of(LEAST_BYTES_A_PERIOD - 1))
			.toArray();

		assertThat(closedIn(aByteShort), is(0));
		assertThat(closedIn(behind), is(behind.length));
	}

	/* A peer that read nothing for a period and reads 16 KiB a period from then on stays 16 KiB behind, and one that
	   reads 32 KiB every other period is behind by as much after every period of nothing and by nothing after the
	   next: neither is closed before a push is done */
	@Example
	public void keepsAPeerThatIsBehindByNoMoreFromOnePeriodToTheNext() {
		assertKeptUntilAPushIsDone("nothing in the second period and 16 KiB in any other",
			period -> period == 2 ? 0 : LEAST_BYTES_A_PERIOD);
		assertKeptUntilAPushIsDone("32 KiB in every other period",
			period -> period % 2 == 0 ? 0 : 2 * LEAST_BYTES_A_PERIOD);
	}

	/* A peer whose link gives out for a period or two and that makes up for it in the next reads 16 KiB a period over
	   every such run, and is kept for as long as that goes on. What a period brings above what the peer is behind is
	   not kept for later, so three periods of nothing close a stream whatever its peer read before them. */
	@Property(tries = 50)
	public void keepsAPeerThatMakesUpForEveryPeriodOrTwoOfTooLittle(@ForAll("periodsThatAreMadeUpFor") int[] read) {
		assertThat(Arrays.toString(read), closedIn(read), is(0));
		assertThat(Arrays.toString(read), periodToCloseIn(read), is(0));
	}

	/* However what a peer reads is spread over the periods, its stream is open until the first period by whose end the
	   peer is 48 KiB behind, and closed in it */
	@Property(tries = 100)
	public void closesAPeerInThePeriodByWhoseEndItIs48KibibytesBehind(@ForAll("periodsOfAnything") int[] read) {
		assertThat(Arrays.toString(read), closedIn(read), is(periodToCloseIn(read)));
	}
}
