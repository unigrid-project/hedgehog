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

package org.unigrid.hedgehog.server.rest;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.ReferenceCountUtil;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.monitoring.ApplicationEvent;
import org.glassfish.jersey.server.monitoring.ApplicationEventListener;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.monitoring.RequestEventListener;
import org.glassfish.jersey.server.spi.Container;
import org.unigrid.hedgehog.common.model.Version;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.NodeStatus;
import org.unigrid.hedgehog.model.bootstrap.BlockFixture;
import org.unigrid.hedgehog.model.bootstrap.BootstrapSnapshot;
import org.unigrid.hedgehog.model.bootstrap.SnapshotBuilder;
import org.unigrid.hedgehog.model.bootstrap.SnapshotInstaller;
import org.unigrid.hedgehog.model.bootstrap.SnapshotSignature;
import org.unigrid.hedgehog.model.cdi.CDIContext;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.ReleaseKeyFixture;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.util.Reflection;
import org.unigrid.hedgehog.server.rest.entity.StatusResponse;

public class UtilResourceTest extends BaseRestClientTest {
	private static final Duration PATIENCE = Duration.ofSeconds(10);

	/* How long the node waits at most for the answer to a stop to be written before it shuts down */
	private static final Duration LATEST = Duration.ofSeconds(2);

	private static final String STOP = "POST /stop HTTP/1.1";
	private static final String ACCEPTED = "HTTP/1.1 202";

	@Inject
	private NodeStatus nodeStatus;

	@Inject
	private SnapshotInstaller snapshotInstaller;

	@Inject
	private BootstrapSnapshot snapshot;

	@BeforeTry
	public void run() {
		nodeStatus.running();
	}

	@Example
	@SneakyThrows
	public void shouldReportRunningWhenIdle() {
		assertThat(client.getEntity("/status", StatusResponse.class),
			equalTo(new StatusResponse("running", NodeStatus.COMPLETE, null)));
	}

	@Example
	@SneakyThrows
	public void shouldAnswerVersionWithOk() {
		final Response response = client.get("/version");

		assertThat(Status.fromStatusCode(response.getStatus()), equalTo(Status.OK));
		assertThat(response.readEntity(String.class), containsString(Version.getVersionNumber()));
	}

	@Example
	@SneakyThrows
	public void shouldReportTheProtocolsOfTheNodeAndNoLedgerProtocolWhileTheLedgerIsOff() {
		final String body = client.get("/version").readEntity(String.class);

		Stream.of(Network.getProtocols()).forEach(protocol -> assertThat(body, containsString(protocol)));
		assertThat(body, not(containsString(Network.LEDGER_PROTOCOL)));
	}

	@Example
	@SneakyThrows
	public void shouldReportTheProgressOfADownload() {
		nodeStatus.downloading(42);
		assertThat(client.getEntity("/status", StatusResponse.class), equalTo(new StatusResponse("downloading", 42, null)));
	}

	@Example
	@SneakyThrows
	public void shouldInstallAMissingSnapshotAndRunAgain() {
		Files.deleteIfExists(snapshot.getPath());
		snapshotInstaller.installIfMissing(signedSnapshot().toUri().toURL());

		assertThat(Files.exists(snapshot.getPath()), equalTo(true));
		assertThat(client.getEntity("/status", StatusResponse.class),
			equalTo(new StatusResponse("running", NodeStatus.COMPLETE, null)));
	}

	@Example
	@SneakyThrows
	public void shouldReportAFailedDownloadWithoutASnapshot() {
		Files.deleteIfExists(snapshot.getPath());
		snapshotInstaller.installIfMissing(Path.of("absent-bootstrap.dat").toUri().toURL());

		final StatusResponse response = client.getEntity("/status", StatusResponse.class);

		assertThat(Files.exists(snapshot.getPath()), equalTo(false));
		assertThat(response.status(), equalTo("failed"));
		assertThat(response.progress(), nullValue());
		assertThat(response.message(), notNullValue());
	}

	@SneakyThrows
	private static Path signedSnapshot() {
		final Path path = Files.createTempFile("hhg-status-", ".dat");
		final Signature key = new Signature();

		path.toFile().deleteOnExit();
		SnapshotBuilder.build(BlockFixture.directory(), path);

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { key.getPublicKey() };
			}
		};

		SnapshotSignature.signAndAppend(path, key.getPrivateKey());
		ReleaseKeyFixture.trusted().publish(path);
		return path;
	}

	/* Counts the times that the node is told to shut down, which it then does not do */
	private static AtomicInteger stops() {
		final AtomicInteger stops = new AtomicInteger();

		new MockUp<CDIContext>() {
			@Mock public /* static */ void stop() {
				stops.incrementAndGet();
			}
		};

		return stops;
	}

	/* On the server as it is built: the node is told to stop as its answer is written, long before the wait for an
	   answer that stays unwritten is over */
	@Example
	@SneakyThrows
	public void shouldTellTheNodeToStopAsSoonAsItHasAnsweredTheCall() {
		final AtomicInteger stops = stops();

		assertThat(Status.fromStatusCode(client.post("/stop", null).getStatus()), equalTo(Status.ACCEPTED));
		await().atMost(LATEST.dividedBy(2)).untilAtomic(stops, equalTo(1));
	}

	/* Stands where the socket of a connection is. A socket takes what is written to it, takes no more while the other
	   end does not read, and fails what is written to it once the other end has reset the connection. */
	private static final class Socket extends ChannelOutboundHandlerAdapter {
		private final Queue<Runnable> unwritten = new ConcurrentLinkedQueue<>();
		private volatile boolean full;
		private volatile boolean reset;

		@Override
		public void write(final ChannelHandlerContext ctx, final Object message, final ChannelPromise promise) {
			if (reset) {
				ReferenceCountUtil.release(message);
				promise.setFailure(new IOException("Connection reset by peer"));
			} else if (full) {
				unwritten.add(() -> ctx.writeAndFlush(message, promise));
			} else {
				ctx.write(message, promise);
			}
		}

		/* The other end reads again */
		private void drain() {
			full = false;

			for (Runnable write = unwritten.poll(); write != null; write = unwritten.poll()) {
				write.run();
			}
		}
	}

	/* Counts the requests that the container is done with, each one once it has handed its answer to the connection
	   and has nothing left to do for it */
	private static final class Finished implements ApplicationEventListener, RequestEventListener {
		private final AtomicInteger requests = new AtomicInteger();

		@Override
		public void onEvent(final ApplicationEvent event) {
			/* Only what becomes of a request is counted */
		}

		@Override
		public RequestEventListener onRequest(final RequestEvent event) {
			return this;
		}

		@Override
		public void onEvent(final RequestEvent event) {
			if (event.getType() == RequestEvent.Type.FINISHED) {
				requests.incrementAndGet();
			}
		}
	}

	/* A connection of the server that the test holds: the container with the resource, built the way the server
	   builds it, behind the codec and what holds the stop back, as they stand in the server. The time of the
	   connection is held still, so the wait for an answer is exact and is not waited out. */
	private static final class Connection implements AutoCloseable {
		private static final String CONTAINER = "org.glassfish.jersey.netty.httpserver.NettyHttpContainer";
		private static final String HANDLER = "org.glassfish.jersey.netty.httpserver.JerseyServerHandler";
		private static final String TOKEN = RestToken.generate();
		private static final String BEARER = "Authorization: " + BearerTokenFilter.SCHEME + " " + TOKEN;
		private static final String EMPTY = "Content-Length: 0";

		private final Socket socket = new Socket();
		private final Finished finished = new Finished();
		private final Container jersey;
		private final EmbeddedChannel channel;

		@SneakyThrows
		private Connection() {
			final ResourceConfig config = new ResourceConfig(UtilResource.class)
				.registerInstances(new BearerTokenFilter(TOKEN), finished);

			jersey = (Container) Reflection.getConstructor(CONTAINER, Application.class).newInstance(config);

			channel = new EmbeddedChannel(socket, new HttpServerCodec(), new StopAfterAnswer(),
				(ChannelHandler) Reflection.getConstructor(HANDLER, URI.class, jersey.getClass(),
					ResourceConfig.class).newInstance(URI.create("https://localhost/"), jersey, config));

			channel.freezeTime();
		}

		/* The container answers on a thread of its own, so the request is sent and then waited for until the
		   container is done with it, which is when nothing but the test touches the connection */
		private void send(final String... head) {
			final int before = finished.requests.get();

			channel.writeInbound(Unpooled.copiedBuffer(String.join("\r\n", head) + "\r\nHost: localhost\r\n\r\n",
				StandardCharsets.US_ASCII));

			await().atMost(PATIENCE).until(() -> finished.requests.get() > before);
		}

		private void passTime(final Duration time) {
			channel.advanceTimeBy(time.toMillis(), TimeUnit.MILLISECONDS);
			channel.runScheduledPendingTasks();
		}

		/* What the connection has been sent, which may be nothing */
		private String answers() {
			final StringBuilder answers = new StringBuilder();

			for (ByteBuf out = channel.readOutbound(); out != null; out = channel.readOutbound()) {
				answers.append(out.toString(StandardCharsets.US_ASCII));
				out.release();
			}

			return answers.toString();
		}

		@Override
		public void close() {
			socket.drain();
			channel.finishAndReleaseAll();
			jersey.getApplicationHandler().onShutdown(jersey);
		}
	}

	/* The time of the wait passes here after the answer is written, and what would have told the node at the end
	   of it is gone by then */
	@Example
	public void shouldTellTheNodeToStopOnceWhenItsAnswerIsWrittenAndNotAgainAfterTheWait() {
		final AtomicInteger stops = stops();

		try (Connection connection = new Connection()) {
			connection.send(STOP, Connection.BEARER, Connection.EMPTY);

			assertThat(connection.answers(), startsWith(ACCEPTED));
			assertThat(stops.get(), equalTo(1));

			connection.passTime(LATEST.multipliedBy(2));
			assertThat(stops.get(), equalTo(1));
		}
	}

	/* A caller that does not read leaves the answer unwritten for as long as it likes, and so does one that is gone
	   while its connection has yet to notice. Neither keeps the node up: it is told to stop when the wait is over,
	   and not a second time when the answer is written after all. The container ends its answer a second time in the
	   meantime, with an empty end that the codec refuses at once, and that is not the answer being written. */
	@Example
	public void shouldTellTheNodeToStopOnceTheWaitIsOverWhileItsAnswerStaysUnwritten() {
		final AtomicInteger stops = stops();

		try (Connection connection = new Connection()) {
			connection.socket.full = true;
			connection.send(STOP, Connection.BEARER, Connection.EMPTY);

			connection.passTime(LATEST.minusMillis(1));
			assertThat(connection.answers(), equalTo(""));
			assertThat(stops.get(), equalTo(0));

			connection.passTime(Duration.ofMillis(1));
			assertThat(stops.get(), equalTo(1));

			connection.socket.drain();
			connection.passTime(LATEST.multipliedBy(2));

			assertThat(connection.answers(), startsWith(ACCEPTED));
			assertThat(stops.get(), equalTo(1));
		}
	}

	/* An answer whose write fails, as one does on a connection that the caller has reset, is over as well, and there
	   is nothing left to wait for */
	@Example
	public void shouldTellTheNodeToStopAtOnceWhenTheWriteOfItsAnswerFails() {
		final AtomicInteger stops = stops();

		try (Connection connection = new Connection()) {
			connection.socket.reset = true;
			connection.send(STOP, Connection.BEARER, Connection.EMPTY);

			assertThat(stops.get(), equalTo(1));

			connection.passTime(LATEST.multipliedBy(2));
			assertThat(stops.get(), equalTo(1));
		}
	}

	/* The node is told to stop by the resource alone, which a request without the token, one of another method and
	   one with a body of a kind that the resource does not take never reach. Its answer is written like any other,
	   and the node is not told when the wait is over either. */
	@Example
	public void shouldNotTellTheNodeToStopForARequestThatIsRefused() {
		final AtomicInteger stops = stops();

		final Map<String, List<String>> refused = Map.of(
			"HTTP/1.1 401", List.of(STOP, Connection.EMPTY),
			"HTTP/1.1 405", List.of("GET /stop HTTP/1.1", Connection.BEARER),
			"HTTP/1.1 415", List.of(STOP, Connection.BEARER, "Content-Type: text/plain", Connection.EMPTY)
		);

		refused.forEach((status, request) -> {
			try (Connection connection = new Connection()) {
				connection.send(request.toArray(String[]::new));
				connection.passTime(LATEST.multipliedBy(2));

				assertThat(connection.answers(), startsWith(status));
				assertThat(stops.get(), equalTo(0));
			}
		});
	}

	/* A request that came over no connection of the server has no answer that could be waited for */
	@Example
	public void shouldTellTheNodeToStopAtOnceWhenTheRequestCameOverNoConnection() {
		final AtomicInteger stops = stops();
		final URI address = URI.create("https://localhost/");

		final Response response = new UtilResource().stop(new ContainerRequest(address, address.resolve("stop"),
			"POST", null, new MapPropertiesDelegate(), null));

		assertThat(Status.fromStatusCode(response.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(stops.get(), equalTo(1));
	}
}
