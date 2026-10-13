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

package org.unigrid.hedgehog.service.chain;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

/* Holds the first line it is given until released, so that a test stops the thread that logs it at that very place
   in the host. It takes no lock of its own, since a thread that logs meanwhile would then wait for the appender and
   not for what the host holds. Not nested in a test, as the module of the tests reads logback-core only when the
   test asks it to, which happens before this class is first used. */
final class StallingAppender extends AppenderBase<ILoggingEvent> {
	private static final int TIMEOUT_SECONDS = 10;

	final CountDownLatch stalled = new CountDownLatch(1);
	final CountDownLatch released = new CountDownLatch(1);
	private final AtomicBoolean holding = new AtomicBoolean(true);
	private final Logger logger;

	StallingAppender(final Class<?> logged) {
		logger = (Logger) LoggerFactory.getLogger(logged);
		setContext(logger.getLoggerContext());
		start();
		logger.addAppender(this);
	}

	void detach() {
		logger.detachAppender(this);
		released.countDown();
	}

	@Override
	public void doAppend(final ILoggingEvent event) {
		if (holding.getAndSet(false)) {
			stalled.countDown();
			await();
		}
	}

	private void await() {
		try {
			released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	protected void append(final ILoggingEvent event) {
		/* Nothing to write, doAppend is all there is to it */
	}
}
