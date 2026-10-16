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

package org.unigrid.hedgehog.service.ledger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import org.objenesis.ObjenesisStd;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.BlockRequest;
import org.unigrid.hedgehog.ledger.LedgerDriver;
import org.unigrid.hedgehog.ledger.LedgerPeer;

public class LedgerGatewayTest {
	private static final long MINUTE = 60_000;
	private static final BlockRequest REQUEST = new BlockRequest(1, 16);
	private static final LedgerPeer PEER = new LedgerPeer() {
		@Override
		public String id() {
			return "peer";
		}

		@Override
		public void send(final Object wire) {
			/* Nothing is sent back here */
		}

		@Override
		public CompletableFuture<BlockBatch> request(final BlockRequest request) {
			return new CompletableFuture<>();
		}
	};

	/* A node that advertises the ledger protocol but runs no ledger has no one to pass a block request to, and a peer
	   that waits for an answer has to wait for its timeout. An empty batch says at once that there is nothing here. */
	@Example
	@SneakyThrows
	public void shouldAnswerABlockRequestAtOnceWithAnEmptyBatchWhileNoDriverIsAttached() {
		final LedgerGateway gateway = new LedgerGateway();
		final CompletableFuture<BlockBatch> reply = gateway.onBlockRequest(PEER, REQUEST, 12);

		assertThat(reply.isDone(), is(true));
		assertThat(reply.isCompletedExceptionally(), is(false));
		assertThat(reply.get().blocks(), empty());
	}

	@Example
	public void shouldSayItOnlyOnceAMinuteAtInfoLevel() {
		final AtomicLong now = new AtomicLong(5 * MINUTE);
		final LedgerGateway gateway = new LedgerGateway();
		final Logger logger = (Logger) LoggerFactory.getLogger(LedgerGateway.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();

		gateway.clockOf(now::get);
		appender.start();
		logger.addAppender(appender);

		try {
			for (int n = 0; n < 50; n++) {
				gateway.onBlockRequest(PEER, REQUEST, 12);
			}

			assertThat(infoLines(appender), hasSize(1));
			assertThat(infoLines(appender).get(0), containsString("no ledger is running"));

			now.addAndGet(MINUTE - 1);
			gateway.onBlockRequest(PEER, REQUEST, 12);
			assertThat(infoLines(appender), hasSize(1));

			now.addAndGet(1);
			gateway.onBlockRequest(PEER, REQUEST, 12);
			assertThat(infoLines(appender), hasSize(2));
		} finally {
			logger.detachAppender(appender);
		}
	}

	private static List<String> infoLines(final ListAppender<ILoggingEvent> appender) {
		return appender.list.stream().filter(entry -> entry.getLevel().equals(Level.INFO))
			.map(ILoggingEvent::getFormattedMessage).toList();
	}

	@Example
	@SneakyThrows
	public void shouldPassARequestOnToTheDriverThatIsAttachedAndAnswerAgainWithNothingOnceItIsDetached() {
		final AtomicReference<BlockRequest> asked = new AtomicReference<>();
		final BlockBatch answer = new BlockBatch(List.of());
		final LedgerGateway gateway = new LedgerGateway();
		final LedgerDriver driver = new ObjenesisStd().newInstance(LedgerDriver.class);

		new MockUp<LedgerDriver>() {
			@Mock
			public CompletableFuture<BlockBatch> onBlockRequest(final LedgerPeer from, final BlockRequest request,
				final int bytes) {

				asked.set(request);
				return CompletableFuture.failedFuture(new IllegalStateException("The driver refuses"));
			}
		};

		gateway.attach(driver);
		final CompletableFuture<BlockBatch> refused = gateway.onBlockRequest(PEER, REQUEST, 12);

		assertThat(asked.get(), equalTo(REQUEST));
		assertThat(refused.isCompletedExceptionally(), is(true));

		gateway.detach();
		assertThat(gateway.onBlockRequest(PEER, REQUEST, 12).get(), equalTo(answer));
	}
}
