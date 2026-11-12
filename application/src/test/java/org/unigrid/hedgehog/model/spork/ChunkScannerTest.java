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

package org.unigrid.hedgehog.model.spork;

import io.netty.channel.embedded.EmbeddedChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeProperty;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.sameInstance;
import org.reflections.Configuration;
import org.reflections.Reflections;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.collection.OptionalMap;
import org.unigrid.hedgehog.model.network.PreviewFrames;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkDecoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkEncoder;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.transport.initializer.PeerPipeline;
import org.unigrid.hedgehog.transport.initializer.RegisterQuicChannelInitializer.Type;

/* Every stream a peer opens gets stages of its own, built on the event loop of the connection, so what building a
   stage costs is paid once per stream. A scan of the classpath, which in the packaged jar reads the whole jar, must
   therefore not be part of it. */
public class ChunkScannerTest {
	private static final int STREAMS = 50;
	private static final int EVENT_LOOPS = 8;
	private static final int TIMEOUT_SECONDS = 60;
	private static final long SIGNED_AT = 1_700_000_000_000L;
	private static final int SIGNATURE_BYTES = 139;

	private static final AtomicInteger scans = new AtomicInteger();

	/* Once for the class, as a fake that is installed for each example would wrap the one of the example before it and
	   count each scan again */
	@BeforeContainer
	public static void countTheScans() {
		new MockUp<Reflections>() {
			@Mock public void $init(Invocation invocation, Configuration configuration) {
				scans.incrementAndGet();
				invocation.proceed();
			}
		};
	}

	@BeforeProperty
	public void before() {
		scans.set(0);
	}

	private static GridSpork mintSupply() {
		final MintSupply spork = new MintSupply();
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.TEN);
		spork.setData(data);
		spork.setPreviousData(data);
		spork.setTimeStamp(Instant.ofEpochMilli(SIGNED_AT));
		spork.setPreviousTimeStamp(Instant.ofEpochMilli(SIGNED_AT));
		spork.setSignature(new byte[SIGNATURE_BYTES]);
		return spork;
	}

	/* The calls run at once, the way the event loops of several connections reach the scanner */
	private static <T> List<T> onSeveralEventLoops(final Callable<T> call) throws Exception {
		final ExecutorService loops = Executors.newFixedThreadPool(EVENT_LOOPS);
		final CountDownLatch go = new CountDownLatch(1);

		try {
			final List<Future<T>> pending = new ArrayList<>();

			for (int i = 0; i < EVENT_LOOPS; i++) {
				pending.add(loops.submit(() -> {
					go.await();
					return call.call();
				}));
			}

			go.countDown();

			final List<T> results = new ArrayList<>();

			for (Future<T> done : pending) {
				results.add(done.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
			}

			return results;
		} finally {
			loops.shutdownNow();
		}
	}

	@Example
	public void doesNotScanTheClasspathForEachStreamOfAPeer() {
		for (int i = 0; i < STREAMS; i++) {
			PeerPipeline.handlers(Type.SERVER, false, false);
		}

		assertThat("scans of the classpath while the stages of " + STREAMS + " streams were built", scans.get(),
			lessThanOrEqualTo(1));
	}

	/* A group that no codec belongs to, so that nothing else in the test run has asked for it before */
	@Example
	public void scansOnceForEachTypeAndGroupWhenSeveralEventLoopsAskAtOnce() throws Exception {
		final List<Object> results = onSeveralEventLoops(() -> ChunkScanner.scan(ChunkType.DECODER,
			ChunkGroup.DEFAULT));

		assertThat(results, hasSize(EVENT_LOOPS));
		assertThat(scans.get(), equalTo(1));
		results.forEach(result -> assertThat(result, sameInstance(results.get(0))));
	}

	/* The result is shared by every stream, so no stream may change what the others see */
	@Example
	public void handsOutAResultThatNoStreamCanChange() {
		final OptionalMap<GridSpork.Type, ChunkDecoder> codecs = ChunkScanner.scan(ChunkType.DECODER,
			ChunkGroup.GRIDSPORK);

		assertThrows(UnsupportedOperationException.class, () -> codecs.put(GridSpork.Type.MINT_SUPPLY, null));
		assertThrows(UnsupportedOperationException.class, () -> codecs.remove(GridSpork.Type.MINT_SUPPLY));
		assertThrows(UnsupportedOperationException.class, codecs::clear);
	}

	/* The chunk decoders are shared between the streams, so one that kept anything of a spork it read would hand it
	   to the spork of another stream */
	@Example
	@SneakyThrows
	public void decodesTheSporksOfSeveralStreamsAtOnceThroughTheSharedChunkDecoders() {
		final GridSpork spork = mintSupply();
		final List<List<Object>> results = onSeveralEventLoops(() -> {
			final List<Object> read = new ArrayList<>();

			IntStream.range(0, STREAMS).forEach(i -> {
				final EmbeddedChannel stream = new EmbeddedChannel(new FrameDecoder(), new PublishSporkDecoder());

				stream.writeInbound(PreviewFrames.frameOf(new PublishSporkEncoder(),
					PublishSpork.builder().gridSpork(spork).build()));
				read.add(((PublishSpork) stream.readInbound()).getGridSpork());
				stream.finishAndReleaseAll();
			});

			return read;
		});

		results.forEach(read -> {
			assertThat(read, hasSize(STREAMS));
			read.forEach(decoded -> assertThat(decoded, equalTo(spork)));
		});
	}
}
