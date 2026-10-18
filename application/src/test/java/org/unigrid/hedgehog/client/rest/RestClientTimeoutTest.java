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

package org.unigrid.hedgehog.client.rest;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Entity;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import org.apache.commons.lang3.exception.ExceptionUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThan;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

/* A node that accepts a request and never answers it must not hold the caller for ever. The node here takes the
   connection, since the system does that without being asked, and then says nothing. */
public class RestClientTimeoutTest {
	private static final Duration PATIENCE = Duration.ofMillis(300);
	private static final Duration LONG_ENOUGH = Duration.ofSeconds(10);

	private static void assertGivesUp(final Callable<?> request) throws Exception {
		final ExecutorService caller = Executors.newSingleThreadExecutor();

		try {
			final Future<?> answer = caller.submit(request);
			final long started = System.nanoTime();
			final ExecutionException failed = assertThrows(ExecutionException.class,
				() -> answer.get(LONG_ENOUGH.toSeconds(), TimeUnit.SECONDS));

			assertThat(failed.getCause(), instanceOf(ProcessingException.class));
			assertThat(ExceptionUtils.getRootCause(failed), instanceOf(SocketTimeoutException.class));
			assertThat(System.nanoTime() - started, lessThan(LONG_ENOUGH.toNanos()));
		} finally {
			caller.shutdownNow();
		}
	}

	@Example
	@SneakyThrows
	public void shouldGiveUpOnAPostThatTheNodeNeverAnswers() {
		try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
			RestClient rest = new RestClient("localhost", silent.getLocalPort(), false, "token", PATIENCE)) {

			assertGivesUp(() -> rest.postBounded("/ledger/transaction", Entity.json("{}")));
		}
	}

	@Example
	@SneakyThrows
	public void shouldGiveUpOnAGetThatTheNodeNeverAnswers() {
		try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
			RestClient rest = new RestClient("localhost", silent.getLocalPort(), false, "token", PATIENCE)) {

			assertGivesUp(() -> rest.get("/ledger"));
		}
	}

	/* An upload is answered only once every byte is placed, which takes as long as the file needs */
	@Example
	@SneakyThrows
	public void shouldNotGiveUpOnAnUploadThatTheNodeIsSlowToAnswer() {
		try (ServerSocket slow = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
			RestClient rest = new RestClient("localhost", slow.getLocalPort(), false, "token", PATIENCE)) {

			final ExecutorService caller = Executors.newSingleThreadExecutor();

			try {
				final Future<?> upload = caller.submit(() -> rest.postStream("/storage",
					new ByteArrayInputStream(new byte[1]), 1));

				assertThrows(TimeoutException.class,
					() -> upload.get(PATIENCE.multipliedBy(5).toMillis(), TimeUnit.MILLISECONDS));
			} finally {
				caller.shutdownNow();
			}
		}
	}
}
