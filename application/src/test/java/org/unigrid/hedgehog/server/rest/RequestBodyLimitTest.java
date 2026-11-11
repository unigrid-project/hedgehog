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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.awaitility.Awaitility.await;
import org.glassfish.jersey.server.ResourceConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.util.Reflection;

/* The limit is exercised on its own, with the time of the channel held still, so that a body that stalls and the
   wait before a connection closes are exact and the server does not have to be waited for */
public class RequestBodyLimitTest {
	private static final long LIMIT = 100;
	private static final long UPLOAD_LIMIT = 10_000;
	private static final Duration BODY_TIME = Duration.ofSeconds(30);
	private static final Duration LINGER = Duration.ofSeconds(2);
	private static final Duration PATIENCE = Duration.ofSeconds(10);
	private static final String SUBMIT = "/ledger/transaction";
	private static final String SPORK = "/gridspork/wire";
	private static final String TOKEN = RestToken.generate();
	private static final String BEARER = BearerTokenFilter.SCHEME + " " + TOKEN;

	/* Stands where the container stands in the pipeline: it takes what the limit lets through and can answer */
	private static final class Container extends ChannelInboundHandlerAdapter {
		private final List<Object> read = new ArrayList<>();
		private ChannelHandlerContext context;

		@Override
		public void handlerAdded(final ChannelHandlerContext ctx) {
			context = ctx;
		}

		@Override
		public void channelRead(final ChannelHandlerContext ctx, final Object message) {
			read.add(message);
		}

		private void answer(final HttpResponseStatus status) {
			context.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status));
		}
	}

	private Container container;
	private EmbeddedChannel channel;

	private static EmbeddedChannel channelTo(final Container container) {
		final EmbeddedChannel created = new EmbeddedChannel(
			new RequestBodyLimit(new BearerTokenFilter(TOKEN), LIMIT, UPLOAD_LIMIT, BODY_TIME, LINGER),
			container);

		created.freezeTime();
		return created;
	}

	@BeforeTry
	public void openChannel() {
		container = new Container();
		channel = channelTo(container);
	}

	@AfterTry
	public void releaseChannel() {
		channel.finishAndReleaseAll();
	}

	private static HttpRequest request(final HttpMethod method, final String path) {
		final HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, path);

		request.headers().set(HttpHeaderNames.AUTHORIZATION, BEARER);
		return request;
	}

	private static HttpRequest request(final String path, final String framing, final String value) {
		final HttpRequest request = request(HttpMethod.POST, path);

		request.headers().set(framing, value);
		return request;
	}

	private static HttpRequest authorizedBy(final String authorization, final HttpRequest request) {
		request.headers().remove(HttpHeaderNames.AUTHORIZATION);

		if (authorization != null) {
			request.headers().set(HttpHeaderNames.AUTHORIZATION, authorization);
		}

		return request;
	}

	private static HttpRequest chunked(final String path) {
		return request(path, HttpHeaderNames.TRANSFER_ENCODING.toString(), HttpHeaderValues.CHUNKED.toString());
	}

	private static HttpRequest declaring(final String path, final long length) {
		return request(path, HttpHeaderNames.CONTENT_LENGTH.toString(), String.valueOf(length));
	}

	private static HttpRequest expecting(final HttpRequest request) {
		request.headers().set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);
		return request;
	}

	private static HttpContent chunk(final int size) {
		return new DefaultHttpContent(Unpooled.wrappedBuffer(new byte[size]));
	}

	private void passTime(final Duration time) {
		channel.advanceTimeBy(time.toMillis(), TimeUnit.MILLISECONDS);
		channel.runScheduledPendingTasks();
	}

	private List<FullHttpResponse> written() {
		final List<FullHttpResponse> responses = new ArrayList<>();

		for (Object out = channel.readOutbound(); out != null; out = channel.readOutbound()) {
			responses.add((FullHttpResponse) out);
		}

		return responses;
	}

	private static String textOf(final FullHttpResponse response) {
		return response.content().toString(StandardCharsets.UTF_8);
	}

	@Property
	public void shouldPassOnWhatStaysWithinTheLimitJustAsItCame(
		@ForAll @Size(max = 10) final List<@IntRange(min = 0, max = 10) Integer> sizes) {

		final List<Object> sent = new ArrayList<>();

		sent.add(chunked(SUBMIT));
		sizes.forEach(size -> sent.add(chunk(size)));
		sent.add(LastHttpContent.EMPTY_LAST_CONTENT);
		sent.forEach(channel::writeInbound);

		assertThat(container.read.size(), equalTo(sent.size()));

		for (int i = 0; i < sent.size(); i++) {
			assertThat(container.read.get(i), sameInstance(sent.get(i)));
		}

		assertThat(written(), empty());
		assertThat(channel.isActive(), is(true));
	}

	@Example
	public void shouldCountEachRequestOnItsOwnOnAConnectionThatStaysOpen() {
		for (int i = 0; i < 3; i++) {
			channel.writeInbound(declaring(SUBMIT, LIMIT), chunk(Math.toIntExact(LIMIT)),
				LastHttpContent.EMPTY_LAST_CONTENT);
		}

		assertThat(container.read, hasSize(9));
		assertThat(written(), empty());
	}

	@Property
	public void shouldRefuseALengthPastTheLimitAtOnceAndPassNothingOn(
		@ForAll @IntRange(min = (int) LIMIT + 1, max = Integer.MAX_VALUE) final int length) {

		final HttpRequest announcing = declaring(SUBMIT, length);

		channel.writeInbound(announcing, chunk(10));

		final List<FullHttpResponse> responses = written();

		assertThat(container.read, empty());
		assertThat(responses, hasSize(1));
		assertThat(responses.get(0).status(), equalTo(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE));
		assertThat(textOf(responses.get(0)), equalTo("{\"error\":\"A request is at most " + LIMIT + " bytes\"}"));
		assertThat(responses.get(0).headers().get(HttpHeaderNames.CONNECTION), equalTo("close"));
		assertThat(responses.get(0).headers().get(HttpHeaderNames.CONTENT_TYPE), equalTo("application/json"));
	}

	@Example
	public void shouldStopAChunkedBodyWhereItCrossesTheLimitAndEndItForTheContainer() {
		final ByteBuf crossing = Unpooled.buffer().writeZero(1);

		channel.writeInbound(chunked(SUBMIT), chunk(60), chunk(40), new DefaultHttpContent(crossing), chunk(5),
			LastHttpContent.EMPTY_LAST_CONTENT);

		assertThat(container.read, hasSize(4));
		assertThat(container.read.get(3), instanceOf(LastHttpContent.class));
		assertThat(((HttpContent) container.read.get(3)).content().readableBytes(), equalTo(0));
		assertThat(crossing.refCnt(), equalTo(0));
		assertThat(written(), hasSize(1));
	}

	@Example
	public void shouldTakeABodyThatIsExactlyAtTheLimit() {
		channel.writeInbound(chunked(SUBMIT), chunk(Math.toIntExact(LIMIT)), LastHttpContent.EMPTY_LAST_CONTENT);

		assertThat(container.read, hasSize(3));
		assertThat(written(), empty());
	}

	@Example
	public void shouldNotAnswerASecondTimeWhenTheResourceHasAnsweredBeforeTheBodyWentPastTheLimit() {
		channel.writeInbound(chunked(SUBMIT), chunk(60));
		container.answer(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
		channel.writeInbound(chunk(60));

		assertThat(written(), hasSize(1));
		assertThat(container.read, hasSize(3));
		assertThat(container.read.get(2), instanceOf(LastHttpContent.class));
	}

	/* The handler that answers Expect: 100-continue stands between the limit and the container, and its 100 Continue
	   is an interim answer: the request is not answered yet, and a refusal after it still has to reach the sender */
	@Example
	public void shouldStillRefuseABodyPastTheLimitAfterTheContainerSaidToContinue() {
		channel.writeInbound(expecting(chunked(SUBMIT)));
		container.answer(HttpResponseStatus.CONTINUE);
		channel.writeInbound(chunk(60), chunk(60));

		final List<FullHttpResponse> responses = written();

		assertThat(responses.stream().map(FullHttpResponse::status).toList(),
			contains(HttpResponseStatus.CONTINUE, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE));
		assertThat(container.read.get(container.read.size() - 1), instanceOf(LastHttpContent.class));
	}

	@Example
	public void shouldStillAnswerABodyThatStaysUnfinishedAfterTheContainerSaidToContinue() {
		channel.writeInbound(expecting(chunked(SUBMIT)));
		container.answer(HttpResponseStatus.CONTINUE);
		channel.writeInbound(chunk(10));
		passTime(BODY_TIME);

		assertThat(written().stream().map(FullHttpResponse::status).toList(),
			contains(HttpResponseStatus.CONTINUE, HttpResponseStatus.REQUEST_TIMEOUT));
	}

	@Example
	public void shouldDropWhatTheContainerWritesOnceTheRequestIsRefused() {
		channel.writeInbound(chunked(SUBMIT), chunk(Math.toIntExact(LIMIT) + 1));
		container.answer(HttpResponseStatus.BAD_REQUEST);

		final List<FullHttpResponse> responses = written();

		assertThat(responses, hasSize(1));
		assertThat(responses.get(0).status(), equalTo(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE));
	}

	@Example
	public void shouldCloseTheConnectionOnlyOnceTheSenderHasHadTimeToReadTheAnswer() {
		channel.writeInbound(declaring(SUBMIT, LIMIT + 1));
		channel.writeInbound(chunk(10));

		assertThat(channel.isActive(), is(true));
		passTime(LINGER.minusMillis(1));
		assertThat(channel.isActive(), is(true));
		passTime(Duration.ofMillis(1));
		assertThat(channel.isActive(), is(false));
	}

	@Example
	public void shouldAnswerABodyThatStaysUnfinishedAndEndItForTheContainer() {
		channel.writeInbound(chunked(SUBMIT), chunk(10));
		passTime(BODY_TIME);

		final List<FullHttpResponse> responses = written();

		assertThat(responses, hasSize(1));
		assertThat(responses.get(0).status(), equalTo(HttpResponseStatus.REQUEST_TIMEOUT));
		assertThat(container.read, hasSize(3));
		assertThat(container.read.get(2), instanceOf(LastHttpContent.class));
		passTime(LINGER);
		assertThat(channel.isActive(), is(false));
	}

	@Example
	public void shouldLeaveABodyAloneThatFinishesInTime() {
		channel.writeInbound(chunked(SUBMIT), chunk(10));
		passTime(BODY_TIME.minusMillis(1));
		channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
		passTime(BODY_TIME.multipliedBy(2));

		assertThat(written(), empty());
		assertThat(channel.isActive(), is(true));
		assertThat(container.read, hasSize(3));
	}

	@Example
	public void shouldNotTimeARequestWithoutABody() {
		channel.writeInbound(request(HttpMethod.GET, "/ledger"), LastHttpContent.EMPTY_LAST_CONTENT);
		passTime(BODY_TIME.multipliedBy(2));

		assertThat(written(), empty());
		assertThat(channel.isActive(), is(true));
	}

	@Example
	public void shouldGiveAnUploadItsOwnLargerLimitAndNoTimeLimit() {
		for (final String path : List.of("/storage", "/storage?x=1", "/storage-object/bucket/key",
			"/storage-object/bucket/a%20key")) {

			channel.writeInbound(declaring(path, UPLOAD_LIMIT), chunk(Math.toIntExact(UPLOAD_LIMIT)));
			passTime(BODY_TIME.multipliedBy(2));
			channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
		}

		assertThat(written(), empty());
		assertThat(container.read, hasSize(12));
		assertThat(channel.isActive(), is(true));
	}

	@Example
	public void shouldRefuseAnUploadPastItsLimitToo() {
		channel.writeInbound(declaring("/storage", UPLOAD_LIMIT + 1));

		assertThat(written().get(0).status(), equalTo(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE));
		assertThat(container.read, empty());
	}

	@Example
	public void shouldTakeASporkAsLargeAsAPeerMaySendWithinTheTimeOfARequest() {
		channel.writeInbound(declaring(SPORK, SporkWire.MAX_SIZE), chunk(Math.toIntExact(UPLOAD_LIMIT)));

		assertThat(written(), empty());
		assertThat(container.read, hasSize(2));

		passTime(BODY_TIME);
		assertThat(written().get(0).status(), equalTo(HttpResponseStatus.REQUEST_TIMEOUT));
	}

	@Example
	public void shouldRefuseASporkLargerThanAPeerMaySend() {
		for (final String path : List.of(SPORK, SPORK + "?x=1")) {
			final EmbeddedChannel other = channelTo(new Container());

			other.writeInbound(declaring(path, SporkWire.MAX_SIZE + 1L));

			final FullHttpResponse refusal = other.readOutbound();

			assertThat(refusal.status(), equalTo(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE));
			other.finishAndReleaseAll();
		}
	}

	@Example
	public void shouldNotMistakeOtherPathsForAnUpload() {
		for (final String path : List.of("/gridspork/storage", "/storages", "/ledger/storage", "/bucket/storage")) {
			final EmbeddedChannel other = channelTo(new Container());

			other.writeInbound(declaring(path, LIMIT + 1));

			final FullHttpResponse refusal = other.readOutbound();

			assertThat(refusal.status(), equalTo(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE));
			other.finishAndReleaseAll();
		}
	}

	@Example
	public void shouldWriteWhatTheContainerAnswersWithinTheLimitJustAsItIs() {
		channel.writeInbound(declaring(SUBMIT, 0), LastHttpContent.EMPTY_LAST_CONTENT);
		container.answer(HttpResponseStatus.ACCEPTED);

		assertThat(written().stream().map(FullHttpResponse::status).toList(), contains(HttpResponseStatus.ACCEPTED));
	}

	@Provide
	public Arbitrary<String> strangers() {
		return Arbitraries.oneOf(Arbitraries.of("", BearerTokenFilter.SCHEME, "Basic " + TOKEN, "bearer " + TOKEN,
			BEARER + " ", BEARER.substring(0, BEARER.length() - 1)),
			Arbitraries.strings().alpha().ofMaxLength(64).map(token -> BearerTokenFilter.SCHEME + " " + token)
		).filter(Predicate.not(BEARER::equals)).injectNull(0.2);
	}

	@Property
	public void shouldRefuseARequestWithoutTheTokenAtItsHeadAndDropItsBody(
		@ForAll("strangers") final String authorization,
		@ForAll @IntRange(min = 1, max = (int) UPLOAD_LIMIT) final int size) {

		final ByteBuf body = Unpooled.buffer().writeZero(size);

		channel.writeInbound(authorizedBy(authorization, chunked("/storage-object/bucket/key")),
			new DefaultHttpContent(body), LastHttpContent.EMPTY_LAST_CONTENT);

		final List<FullHttpResponse> responses = written();

		assertThat(container.read, empty());
		assertThat(body.refCnt(), equalTo(0));
		assertThat(responses, hasSize(1));
		assertThat(responses.get(0).status(), equalTo(HttpResponseStatus.UNAUTHORIZED));
		assertThat(responses.get(0).headers().get(HttpHeaderNames.WWW_AUTHENTICATE),
			equalTo(BearerTokenFilter.SCHEME));
		assertThat(responses.get(0).headers().get(HttpHeaderNames.CONNECTION), equalTo("close"));
		assertThat(textOf(responses.get(0)), equalTo(""));
		assertThat(channel.isActive(), is(true));
		passTime(LINGER);
		assertThat(channel.isActive(), is(false));
	}

	@Example
	public void shouldRefuseForWantOfTheTokenBeforeLookingAtTheLength() {
		channel.writeInbound(authorizedBy(null, declaring(SUBMIT, LIMIT + 1)));

		assertThat(written().get(0).status(), equalTo(HttpResponseStatus.UNAUTHORIZED));
	}

	@Example
	public void shouldRefuseARequestWithoutTheTokenAfterOneWithItWasAnswered() {
		channel.writeInbound(declaring(SUBMIT, 0), LastHttpContent.EMPTY_LAST_CONTENT);
		container.answer(HttpResponseStatus.ACCEPTED);
		channel.writeInbound(authorizedBy(null, request(HttpMethod.GET, "/ledger")),
			LastHttpContent.EMPTY_LAST_CONTENT);

		assertThat(written().stream().map(FullHttpResponse::status).toList(),
			contains(HttpResponseStatus.ACCEPTED, HttpResponseStatus.UNAUTHORIZED));
		assertThat(container.read, hasSize(2));
	}

	/* A resource of the server that is shown a body, and keeps what it is given */
	@Path("/body")
	public static final class Body {
		private final List<String> taken;

		private Body(final List<String> taken) {
			this.taken = taken;
		}

		@POST
		@Consumes(MediaType.TEXT_PLAIN)
		public Response take(final String body) {
			taken.add(body);
			return Response.ok().build();
		}
	}

	/* The container of the server, built the way the server builds it and standing behind the limit, so that what a
	   body cut short does to a resource is what the library does with it. The decoder of the codec stands in front. */
	private static final class Served implements AutoCloseable {
		private static final String CONTAINER = "org.glassfish.jersey.netty.httpserver.NettyHttpContainer";
		private static final String HANDLER = "org.glassfish.jersey.netty.httpserver.JerseyServerHandler";

		private final List<String> taken = new CopyOnWriteArrayList<>();
		private final BlockedReaders readers = new BlockedReaders();
		private final org.glassfish.jersey.server.spi.Container jersey;
		private final EmbeddedChannel channel;

		@SneakyThrows
		private Served() {
			final ResourceConfig config = new ResourceConfig().registerInstances(new Body(taken),
				new CutBodyFilter());

			jersey = (org.glassfish.jersey.server.spi.Container) Reflection.getConstructor(CONTAINER,
				Application.class).newInstance(config);

			channel = new EmbeddedChannel(new HttpServerCodec(),
				new RequestBodyLimit(new BearerTokenFilter(TOKEN), LIMIT, UPLOAD_LIMIT, BODY_TIME, LINGER),
				(ChannelHandler) Reflection.getConstructor(HANDLER, URI.class, jersey.getClass(),
					ResourceConfig.class).newInstance(URI.create("https://localhost/"), jersey, config));

			channel.freezeTime();
		}

		private void awaitReader() {
			await().atMost(PATIENCE).until(() -> !readers.started().isEmpty());
		}

		private static String head(final String framing) {
			return "POST /body HTTP/1.1\r\nHost: localhost\r\nAuthorization: " + BEARER
				+ "\r\nContent-Type: text/plain\r\n" + framing + "\r\n\r\n";
		}

		private void send(final String text) {
			channel.writeInbound(Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII));
		}

		private void passTime(final Duration time) {
			channel.advanceTimeBy(time.toMillis(), TimeUnit.MILLISECONDS);
			channel.runScheduledPendingTasks();
		}

		private String refusal() {
			final ByteBuf out = channel.readOutbound();

			try {
				return out.toString(StandardCharsets.US_ASCII);
			} finally {
				out.release();
			}
		}

		@Override
		public void close() {
			channel.finishAndReleaseAll();
			jersey.getApplicationHandler().onShutdown(jersey);
		}
	}

	/* A body that a connection cut short has no end for the decoder, which reports none for a request, so the
	   resource that reads it waits for ever and holds a thread of the container while it does */
	@Example
	public void shouldEndTheBodyThatAResourceIsReadingOnceItsConnectionIsGone() {
		try (Served served = new Served()) {
			served.send(Served.head("Content-Length: 14") + "10.0.0.1:5");
			served.awaitReader();
			served.channel.close();

			await().atMost(PATIENCE).untilAsserted(() -> assertThat(served.readers.started(), empty()));
			await().during(Duration.ofMillis(500)).atMost(PATIENCE)
				.untilAsserted(() -> assertThat(served.taken, empty()));
		}
	}

	/* The sender was told that the time is up, so what it had sent is no request that the resource may act on */
	@Example
	public void shouldNotLetAResourceTakeABodyThatStoppedShortForAWholeOne() {
		try (Served served = new Served()) {
			served.send(Served.head("Content-Length: 14") + "10.0.0.1:5");
			served.awaitReader();
			served.passTime(BODY_TIME);

			await().atMost(PATIENCE).untilAsserted(() -> assertThat(served.readers.started(), empty()));
			await().during(Duration.ofMillis(500)).atMost(PATIENCE)
				.untilAsserted(() -> assertThat(served.taken, empty()));
			assertThat(served.refusal(), startsWith("HTTP/1.1 408"));
		}
	}

	/* The body is cut where it crosses the limit, and what comes before the cut is a part of it, however whole it
	   looks */
	@Example
	public void shouldNotLetAResourceTakeABodyThatWasCutOffAtTheLimitForAWholeOne() {
		try (Served served = new Served()) {
			served.send(Served.head("Transfer-Encoding: chunked") + "a\r\n10.0.0.1:5\r\n");
			served.awaitReader();
			served.send(Long.toHexString(LIMIT) + "\r\n" + "x".repeat(Math.toIntExact(LIMIT)) + "\r\n");

			await().atMost(PATIENCE).untilAsserted(() -> assertThat(served.readers.started(), empty()));
			await().during(Duration.ofMillis(500)).atMost(PATIENCE)
				.untilAsserted(() -> assertThat(served.taken, empty()));
			assertThat(served.refusal(), startsWith("HTTP/1.1 413"));
		}
	}
}
