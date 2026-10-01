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

package org.unigrid.hedgehog.model.network;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.statistics.Statistics;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.network.packet.FragmentReply;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.storage.StorageStatus;

public class PendingRequestsTest {
	private static final int CHANNELS = 3;
	private static final int MAX_REQUESTS = 6;
	private static final Duration NEVER_DURING_A_TRY = Duration.ofMinutes(1);

	private static final List<LongFunction<Correlated>> REPLIES = List.of(
		id -> StorageAck.builder().requestId(id).status(StorageStatus.OK).build(),
		id -> FragmentReply.builder().requestId(id).status(StorageStatus.OK).fragment(new byte[0]).build(),
		id -> FragmentStatus.builder().requestId(id).entries(List.of()).build());

	private record Request(int channel, int type) {
		/* The channel a request left on and the reply type it waits for */
	}

	private record Reply(int request, int channel, int type) {
		/* A request index outside the registered ones stands for an id nobody waits for */
	}

	private static Class<? extends Correlated> typeOf(final int type) {
		return REPLIES.get(type).apply(0).getClass();
	}

	private static List<Channel> channels() {
		return Stream.generate(EmbeddedChannel::new).limit(CHANNELS).collect(Collectors.toList());
	}

	@Provide
	public Arbitrary<Integer> types() {
		return Arbitraries.integers().between(0, REPLIES.size() - 1);
	}

	@Provide
	public Arbitrary<List<Request>> requests() {
		return Combinators.combine(Arbitraries.integers().between(0, CHANNELS - 1), types()).as(Request::new)
			.list().ofMinSize(1).ofMaxSize(MAX_REQUESTS);
	}

	@Provide
	public Arbitrary<List<Reply>> replies() {
		return Combinators.combine(Arbitraries.integers().between(-1, MAX_REQUESTS),
			Arbitraries.integers().between(0, CHANNELS - 1), types()).as(Reply::new).list().ofMaxSize(40);
	}

	@Property
	public void completesARequestOnlyWithItsIdChannelAndType(@ForAll("requests") List<Request> requests,
		@ForAll("replies") List<Reply> replies) {

		final PendingRequests pending = new PendingRequests(NEVER_DURING_A_TRY);
		final List<Channel> channels = channels();
		final List<Long> ids = new ArrayList<>();
		final List<CompletableFuture<? extends Correlated>> futures = new ArrayList<>();
		final Map<Integer, Correlated> expected = new HashMap<>();

		for (Request request : requests) {
			ids.add(pending.nextRequestId());
			futures.add(pending.register(ids.get(ids.size() - 1), channels.get(request.channel()),
				typeOf(request.type())));
		}

		final long unknownId = pending.nextRequestId();

		for (Reply reply : replies) {
			final boolean known = reply.request() >= 0 && reply.request() < requests.size();
			final Correlated packet = REPLIES.get(reply.type()).apply(known ? ids.get(reply.request()) : unknownId);

			pending.complete(channels.get(reply.channel()), packet);

			if (known && requests.get(reply.request()).equals(new Request(reply.channel(), reply.type()))) {
				expected.putIfAbsent(reply.request(), packet);
			}
		}

		for (int i = 0; i < requests.size(); i++) {
			Statistics.collect(expected.containsKey(i));
			assertThat(futures.get(i).getNow(null), sameInstance(expected.get(i)));
		}

		assertThat(pending.size(), equalTo(requests.size() - expected.size()));

		Statistics.coverage(coverage -> {
			coverage.check(true).count(count -> count > 0);
			coverage.check(false).count(count -> count > 0);
		});
	}

	@Property
	public void leavesARequestPendingThroughRepliesOfOtherTypes(@ForAll("types") int type) {
		final PendingRequests pending = new PendingRequests(NEVER_DURING_A_TRY);
		final Channel channel = new EmbeddedChannel();
		final long id = pending.nextRequestId();
		final CompletableFuture<? extends Correlated> future = pending.register(id, channel, typeOf(type));
		final Correlated reply = REPLIES.get(type).apply(id);

		IntStream.range(0, REPLIES.size()).filter(other -> other != type)
			.forEach(other -> pending.complete(channel, REPLIES.get(other).apply(id)));

		assertThat(future.isDone(), is(false));
		assertThat(pending.size(), equalTo(1));

		pending.complete(channel, reply);
		assertThat(future.getNow(null), sameInstance(reply));
		assertThat(pending.size(), equalTo(0));
	}

	@Property
	public void failsAndForgetsOnlyTheRequestsItIsToldTo(@ForAll("requests") List<Request> requests,
		@ForAll("failures") List<Boolean> failures) {

		final PendingRequests pending = new PendingRequests(NEVER_DURING_A_TRY);
		final List<Channel> channels = channels();
		final List<CompletableFuture<? extends Correlated>> futures = new ArrayList<>();
		final Map<Integer, Throwable> causes = new HashMap<>();

		for (Request request : requests) {
			final long id = pending.nextRequestId();

			futures.add(pending.register(id, channels.get(request.channel()), typeOf(request.type())));

			if (failures.get(futures.size() - 1)) {
				causes.put(futures.size() - 1, new IllegalStateException("write failed"));
				pending.fail(id, causes.get(futures.size() - 1));
			}
		}

		pending.fail(pending.nextRequestId(), new IllegalStateException("nobody waits for this"));

		for (int i = 0; i < requests.size(); i++) {
			final CompletableFuture<? extends Correlated> future = futures.get(i);

			if (causes.containsKey(i)) {
				final ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get());

				assertThat(failure.getCause(), sameInstance(causes.get(i)));
			} else {
				assertThat(future.isDone(), is(false));
			}
		}

		assertThat(pending.size(), equalTo(requests.size() - causes.size()));
	}

	@Provide
	public Arbitrary<List<Boolean>> failures() {
		return Arbitraries.of(true, false).list().ofSize(MAX_REQUESTS);
	}

	@Property(tries = 10)
	public void failsAndForgetsRequestsThatTimeOut(@ForAll("requests") List<Request> requests,
		@ForAll @IntRange(min = 1, max = 50) int timeoutMillis) {

		final PendingRequests pending = new PendingRequests(Duration.ofMillis(timeoutMillis));
		final List<Channel> channels = channels();
		final List<CompletableFuture<? extends Correlated>> futures = new ArrayList<>();

		for (Request request : requests) {
			futures.add(pending.register(pending.nextRequestId(), channels.get(request.channel()),
				typeOf(request.type())));
		}

		for (CompletableFuture<? extends Correlated> future : futures) {
			final ExecutionException failure = assertThrows(ExecutionException.class,
				() -> future.get(10, TimeUnit.SECONDS));

			assertThat(failure.getCause(), instanceOf(TimeoutException.class));
		}

		assertThat(pending.size(), equalTo(0));
	}
}
