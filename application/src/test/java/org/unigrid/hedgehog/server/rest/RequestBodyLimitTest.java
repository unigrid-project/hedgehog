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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
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
import java.util.concurrent.atomic.AtomicInteger;
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
import org.glassfish.jersey.server.monitoring.ApplicationEvent;
import org.glassfish.jersey.server.monitoring.ApplicationEventListener;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.monitoring.RequestEventListener;
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
			send(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status));
		}

		/* One piece of an answer that is written piece by piece */
		private void send(final Object piece) {
			context.writeAndFlush(piece);
		}

		/* As the container closes the connection of a request that it failed */
		private ChannelFuture close() {
			return context.close();
		}
	}

	private Container container;
	private EmbeddedChannel channel;

	/* The time is held still before the connection opens, so that what the limit starts as it opens is exact too */
	@SneakyThrows
	private static EmbeddedChannel channelTo(final Container container) {
		final EmbeddedChannel created = new EmbeddedChannel(false, false,
			new RequestBodyLimit(new BearerTokenFilter(TOKEN), LIMIT, UPLOAD_LIMIT, BODY_TIME, LINGER),
			container);

		created.freezeTime();
		created.register();
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

	private List<HttpResponseStatus> answers() {
		return written().stream().map(FullHttpResponse::status).toList();
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

	/* The container fails a request whose body was cut short, and closes the connection of a request that it failed.
	   The sender is then still to read why it was refused, and may still be sending, so the connection stays until
	   the wait is over, and whoever closed it is told only then that it is closed. */
	@Example
	public void shouldCloseARefusedConnectionOnlyOnceTheWaitIsOverThoughTheContainerClosesItAtOnce() {
		channel.writeInbound(chunked(SUBMIT), chunk(Math.toIntExact(LIMIT) + 1));

		final ChannelFuture closed = container.close();

		assertThat(channel.isActive(), is(true));
		passTime(LINGER.minusMillis(1));
		assertThat(channel.isActive(), is(true));
		assertThat(closed.isDone(), is(false));
		passTime(Duration.ofMillis(1));
		assertThat(channel.isActive(), is(false));
		assertThat(closed.isSuccess(), is(true));
		assertThat(written().get(0).status(), equalTo(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE));
	}

	@Example
	public void shouldCloseAConnectionThatWasNotRefusedAsSoonAsTheContainerClosesIt() {
		channel.writeInbound(chunked(SUBMIT), chunk(10));

		assertThat(container.close().isSuccess(), is(true));
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

	/* A sender that has connected and then says nothing holds a socket of the node for as long as it likes, so the
	   request of a connection is given the time that a body is given, from the moment that the connection opens */
	@Example
	public void shouldAnswerAConnectionThatSendsNoRequestAndCloseIt() {
		passTime(BODY_TIME.minusMillis(1));
		assertThat(written(), empty());
		assertThat(channel.isActive(), is(true));
		passTime(Duration.ofMillis(1));

		final List<FullHttpResponse> responses = written();

		assertThat(responses, hasSize(1));
		assertThat(responses.get(0).status(), equalTo(HttpResponseStatus.REQUEST_TIMEOUT));
		assertThat(textOf(responses.get(0)), equalTo("{\"error\":\"A request has to arrive within "
			+ BODY_TIME.toSeconds() + " seconds\"}"));
		assertThat(responses.get(0).headers().get(HttpHeaderNames.CONNECTION), equalTo("close"));
		assertThat(container.read, empty());
		assertThat(channel.isActive(), is(true));
		passTime(LINGER);
		assertThat(channel.isActive(), is(false));
	}

	/* The wait for a request is over with its head, and the body that follows has its own time from there */
	@Example
	public void shouldGiveABodyItsOwnTimeFromAHeadThatCameInTime() {
		passTime(BODY_TIME.minusMillis(1));
		channel.writeInbound(chunked(SUBMIT));
		passTime(BODY_TIME.minusMillis(1));
		assertThat(written(), empty());
		passTime(Duration.ofMillis(1));
		assertThat(answers(), contains(HttpResponseStatus.REQUEST_TIMEOUT));
	}

	/* A resource takes as long as it needs for its answer. The wait is for a request, so it begins again only once
	   this one is answered. */
	@Example
	public void shouldWaitForTheNextRequestOnlyOnceTheResourceHasAnswered() {
		channel.writeInbound(request(HttpMethod.GET, "/ledger"), LastHttpContent.EMPTY_LAST_CONTENT);
		passTime(BODY_TIME.multipliedBy(2));
		container.answer(HttpResponseStatus.OK);
		passTime(BODY_TIME.minusMillis(1));
		assertThat(answers(), contains(HttpResponseStatus.OK));
		assertThat(channel.isActive(), is(true));
		passTime(Duration.ofMillis(1));
		assertThat(written(), empty());
		assertThat(channel.isActive(), is(false));
	}

	/* A sender keeps a connection that was answered for the request that it sends next, and what is written to it in
	   the meantime lies there until then and is read as the answer to that request. A connection that is kept and
	   says nothing is therefore closed without a word, as one is everywhere. */
	@Example
	public void shouldCloseAConnectionThatIsKeptAndSaysNothingWithoutWritingToIt() {
		channel.writeInbound(request(HttpMethod.GET, "/ledger"), LastHttpContent.EMPTY_LAST_CONTENT);
		container.answer(HttpResponseStatus.OK);
		passTime(BODY_TIME.minusMillis(1));
		assertThat(channel.isActive(), is(true));
		passTime(Duration.ofMillis(1));

		assertThat(answers(), contains(HttpResponseStatus.OK));
		assertThat(channel.isActive(), is(false));
	}

	/* The head of a request that has arrived is not nothing, so a body that then stops short is answered on a
	   connection that was answered before as it is on a new one */
	@Example
	public void shouldStillAnswerABodyThatStaysUnfinishedOnAConnectionThatWasAnsweredBefore() {
		channel.writeInbound(request(HttpMethod.GET, "/ledger"), LastHttpContent.EMPTY_LAST_CONTENT);
		container.answer(HttpResponseStatus.OK);
		channel.writeInbound(chunked(SUBMIT), chunk(10));
		passTime(BODY_TIME);

		assertThat(answers(), contains(HttpResponseStatus.OK, HttpResponseStatus.REQUEST_TIMEOUT));
		assertThat(channel.isActive(), is(true));
		passTime(LINGER);
		assertThat(channel.isActive(), is(false));
	}

	@Example
	public void shouldTakeEachRequestOfAConnectionThatComesInTimeAfterTheOneBefore() {
		for (int i = 0; i < 3; i++) {
			passTime(BODY_TIME.minusMillis(1));
			channel.writeInbound(request(HttpMethod.GET, "/ledger"), LastHttpContent.EMPTY_LAST_CONTENT);
			container.answer(HttpResponseStatus.OK);
		}

		assertThat(answers(), contains(HttpResponseStatus.OK, HttpResponseStatus.OK, HttpResponseStatus.OK));
		assertThat(container.read, hasSize(6));
		assertThat(channel.isActive(), is(true));
	}

	/* An answer that is written piece by piece is under way until its end is written, however long that takes */
	@Example
	public void shouldNotCutAnAnswerThatIsStillBeingWritten() {
		channel.writeInbound(request(HttpMethod.GET, "/ledger"), LastHttpContent.EMPTY_LAST_CONTENT);
		container.send(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
		passTime(BODY_TIME.multipliedBy(2));
		container.send(chunk(10));
		passTime(BODY_TIME.multipliedBy(2));

		assertThat(channel.outboundMessages(), hasSize(2));
		assertThat(channel.isActive(), is(true));
		channel.releaseOutbound();

		container.send(LastHttpContent.EMPTY_LAST_CONTENT);
		passTime(BODY_TIME);

		assertThat(channel.readOutbound(), sameInstance(LastHttpContent.EMPTY_LAST_CONTENT));
		assertThat(written(), empty());
		assertThat(channel.isActive(), is(false));
	}

	/* A sender may send its next request before the one before it is answered, and that one is then under way too */
	@Example
	public void shouldNotWaitForARequestWhileOneThatCameEarlyIsStillToBeAnswered() {
		channel.writeInbound(request(HttpMethod.GET, "/ledger"), LastHttpContent.EMPTY_LAST_CONTENT,
			request(HttpMethod.GET, "/ledger"), LastHttpContent.EMPTY_LAST_CONTENT);

		container.answer(HttpResponseStatus.OK);
		passTime(BODY_TIME.multipliedBy(2));
		assertThat(answers(), contains(HttpResponseStatus.OK));
		assertThat(channel.isActive(), is(true));

		container.answer(HttpResponseStatus.OK);
		passTime(BODY_TIME.minusMillis(1));
		assertThat(channel.isActive(), is(true));
		passTime(Duration.ofMillis(1));
		assertThat(answers(), contains(HttpResponseStatus.OK));
		assertThat(channel.isActive(), is(false));
	}

	/* The container ends an answer without a body twice, with the answer itself and with an empty end after it. That
	   is one answer, and not also the answer of the request that came after. */
	@Example
	public void shouldTakeAnAnswerThatIsEndedTwiceForOneAnswer() {
		channel.writeInbound(request(HttpMethod.GET, "/ledger"), LastHttpContent.EMPTY_LAST_CONTENT,
			request(HttpMethod.GET, "/ledger"), LastHttpContent.EMPTY_LAST_CONTENT);

		container.answer(HttpResponseStatus.OK);
		container.send(LastHttpContent.EMPTY_LAST_CONTENT);
		passTime(BODY_TIME.multipliedBy(2));

		assertThat(channel.outboundMessages(), hasSize(2));
		assertThat(channel.isActive(), is(true));
	}

	/* An answer may be out before the body has ended, as the answer to a path that is not there is. The request is
	   over, and the next one waited for, once its body has ended as well. */
	@Example
	public void shouldWaitForTheNextRequestOnlyOnceABodyThatWasAnsweredEarlyHasEnded() {
		channel.writeInbound(declaring("/storage", UPLOAD_LIMIT), chunk(10));
		container.answer(HttpResponseStatus.NOT_FOUND);
		passTime(BODY_TIME.multipliedBy(2));
		assertThat(answers(), contains(HttpResponseStatus.NOT_FOUND));
		assertThat(channel.isActive(), is(true));

		channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
		passTime(BODY_TIME.minusMillis(1));
		assertThat(channel.isActive(), is(true));
		passTime(Duration.ofMillis(1));
		assertThat(written(), empty());
		assertThat(channel.isActive(), is(false));
	}

	/* A 100 Continue is no answer: the request that it was written for is still to be answered */
	@Example
	public void shouldNotWaitForARequestAfterAContinueToOneThatIsStillToBeAnswered() {
		channel.writeInbound(expecting(declaring(SUBMIT, 10)));
		container.answer(HttpResponseStatus.CONTINUE);
		channel.writeInbound(chunk(10), LastHttpContent.EMPTY_LAST_CONTENT);
		passTime(BODY_TIME.multipliedBy(2));

		assertThat(answers(), contains(HttpResponseStatus.CONTINUE));
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

	/* Stands between the limit and the container and counts the times that the container closed the connection, each
	   one once the close has gone as far as it goes */
	private static final class Closes extends ChannelOutboundHandlerAdapter {
		private final AtomicInteger made = new AtomicInteger();

		@Override
		public void close(final ChannelHandlerContext ctx, final ChannelPromise promise) throws Exception {
			super.close(ctx, promise);
			made.incrementAndGet();
		}
	}

	/* Counts the requests that the container is done with, each one once its answer is written and the container has
	   nothing left to do for it */
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

	/* The container of the server, built the way the server builds it and standing behind the limit, so that what a
	   body cut short does to a resource is what the library does with it. The decoder of the codec stands in front,
	   and in front of that what tells the limit that something has arrived. */
	private static final class Served implements AutoCloseable {
		private static final String CONTAINER = "org.glassfish.jersey.netty.httpserver.NettyHttpContainer";
		private static final String HANDLER = "org.glassfish.jersey.netty.httpserver.JerseyServerHandler";

		private final List<String> taken = new CopyOnWriteArrayList<>();
		private final BlockedReaders readers = new BlockedReaders();
		private final Closes closes = new Closes();
		private final Finished finished = new Finished();
		private final org.glassfish.jersey.server.spi.Container jersey;
		private final EmbeddedChannel channel;

		@SneakyThrows
		private Served() {
			final ResourceConfig config = new ResourceConfig().registerInstances(new Body(taken),
				new CutBodyFilter(), finished);

			jersey = (org.glassfish.jersey.server.spi.Container) Reflection.getConstructor(CONTAINER,
				Application.class).newInstance(config);

			final RequestBodyLimit limit = new RequestBodyLimit(new BearerTokenFilter(TOKEN), LIMIT,
				UPLOAD_LIMIT, BODY_TIME, LINGER);

			channel = new EmbeddedChannel(limit.arrivals(), new HttpServerCodec(), limit, closes,
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

		/* What the connection has been sent since this was last asked, which may be nothing */
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

	/* The container fails the request whose body the stream reports as cut short, and closes its connection with the
	   failure, while the sender is still sending the rest of the body: a connection closed then is reset, which takes
	   the refusal with it. No time has passed here, so the wait before the close is not over. */
	@Example
	public void shouldNotLetTheContainerCloseTheConnectionOfABodyCutAtTheLimitBeforeTheWaitIsOver() {
		try (Served served = new Served()) {
			served.send(Served.head("Transfer-Encoding: chunked") + "a\r\n10.0.0.1:5\r\n");
			served.awaitReader();
			served.send(Long.toHexString(LIMIT) + "\r\n" + "x".repeat(Math.toIntExact(LIMIT)) + "\r\n");

			await().atMost(PATIENCE).until(() -> served.closes.made.get() > 0);
			assertThat(served.refusal(), startsWith("HTTP/1.1 413"));
			assertThat(served.channel.isActive(), is(true));
		}
	}

	/* The decoder hands on the head of a request only once it is whole, so a head that stops half way is a request
	   that has not arrived, and its sender holds the connection as one that says nothing does */
	@Example
	public void shouldAnswerAHeadThatStopsHalfWay() {
		try (Served served = new Served()) {
			served.send("POST /body HTTP/1.1\r\nHost: loc");
			served.passTime(BODY_TIME);

			assertThat(served.answers(), startsWith("HTTP/1.1 408"));
			assertThat(served.taken, empty());
		}
	}

	/* The answer is written by the container here, the way it writes one without a body: it ends it twice, with the
	   answer itself and with an empty end after it. The connection is then waiting for its next request, and its
	   sender, which says nothing, is keeping it for one: not a byte is written to it before it closes. */
	@Example
	public void shouldCloseAConnectionThatSendsNoRequestAfterTheContainerAnsweredOne() {
		try (Served served = new Served()) {
			served.send(Served.head("Content-Length: 10") + "10.0.0.1:5");
			await().atMost(PATIENCE).until(() -> served.finished.requests.get() > 0);
			assertThat(served.answers(), startsWith("HTTP/1.1 200"));

			served.passTime(BODY_TIME.minusMillis(1));
			assertThat(served.answers(), equalTo(""));
			assertThat(served.channel.isActive(), is(true));
			served.passTime(Duration.ofMillis(1));
			assertThat(served.answers(), equalTo(""));
			assertThat(served.channel.isActive(), is(false));
			assertThat(served.taken, contains("10.0.0.1:5"));
		}
	}

	/* The decoder keeps a head to itself until it is whole, so the limit behind it sees nothing of one that stops half
	   way. Its sender has begun a request and is waiting for an answer, so it is told that its time is up, on a
	   connection that was answered before as on a new one. */
	@Example
	public void shouldAnswerAHeadThatStopsHalfWayAfterTheContainerAnsweredARequest() {
		try (Served served = new Served()) {
			served.send(Served.head("Content-Length: 10") + "10.0.0.1:5");
			await().atMost(PATIENCE).until(() -> served.finished.requests.get() > 0);
			assertThat(served.answers(), startsWith("HTTP/1.1 200"));

			served.passTime(BODY_TIME.minusMillis(1));
			served.send("POST /body HTTP/1.1\r\nHost: loc");
			assertThat(served.answers(), equalTo(""));
			served.passTime(Duration.ofMillis(1));
			assertThat(served.answers(), startsWith("HTTP/1.1 408"));
			assertThat(served.channel.isActive(), is(true));
			served.passTime(LINGER);
			assertThat(served.channel.isActive(), is(false));
			assertThat(served.taken, contains("10.0.0.1:5"));
		}
	}
}
