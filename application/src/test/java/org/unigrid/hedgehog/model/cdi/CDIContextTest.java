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

package org.unigrid.hedgehog.model.cdi;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import jakarta.enterprise.event.Observes;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.jboss.weld.environment.se.events.ContainerInitialized;
import org.unigrid.hedgehog.jqwik.MockitHook;

/* The node serves from the moment its container starts, the REST server included, so a stop can arrive before the
   thread that started the container gets to wait for it. The container is faked, so that the test decides when its
   start is over. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class CDIContextTest {
	private static final Duration AT_ONCE = Duration.ofSeconds(1);
	private static final Duration STILL_RUNNING = Duration.ofMillis(500);
	private static final long TIMEOUT_SECONDS = 10;

	private static class TestContext extends CDIContext {
		@Override
		protected void start(@Observes ContainerInitialized event) { /* The container is faked */ }
	}

	/* A node that runs on a thread of its own, held inside the start of its container until it is let go */
	private static final class RunningNode {
		private final CountDownLatch starting = new CountDownLatch(1);
		private final CountDownLatch started = new CountDownLatch(1);
		private final Thread thread = new Thread(new TestContext());

		RunningNode() {
			new MockUp<Weld>() {
				@Mock
				@SneakyThrows
				public WeldContainer initialize() {
					starting.countDown();

					if (!started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
						throw new IllegalStateException("The start of the container was never let go");
					}

					return null;
				}
			};

			thread.setDaemon(true);
			thread.start();
		}

		@SneakyThrows
		void awaitStarting() {
			assertThat("The node reached its container", starting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
		}

		void letStartFinish() {
			started.countDown();
		}

		void awaitWaitingForStop() {
			await("the node to wait for its stop").until(() -> thread.getState() == Thread.State.WAITING);
		}

		void assertKeepsRunning() {
			await("the node to keep running").during(STILL_RUNNING).atMost(AT_ONCE.plus(STILL_RUNNING))
				.until(thread::isAlive);
		}

		void awaitReturn() {
			await("the node to return from its run").atMost(AT_ONCE).until(() -> !thread.isAlive());
		}

		/* Frees a node that a failed example left waiting */
		void release() {
			CDIContext.stop();
			letStartFinish();
		}
	}

	/* A wakeup that no stop has caused, which Object.wait is allowed to give at any time */
	@SneakyThrows
	private static void wakeWithoutAStop() {
		final Field field = CDIContext.class.getDeclaredField("MONITOR");

		field.setAccessible(true);
		final Object monitor = field.get(null);

		synchronized (monitor) {
			monitor.notifyAll();
		}
	}

	@Example
	public void shouldStopWhenTheStopArrivedBeforeTheNodeWaited() {
		final RunningNode node = new RunningNode();

		try {
			node.awaitStarting();
			CDIContext.stop();
			node.letStartFinish();
			node.awaitReturn();
		} finally {
			node.release();
		}
	}

	@Example
	public void shouldRunUntilItIsStopped() {
		final RunningNode node = new RunningNode();

		try {
			node.awaitStarting();
			node.letStartFinish();
			node.awaitWaitingForStop();
			node.assertKeepsRunning();

			CDIContext.stop();
			node.awaitReturn();
		} finally {
			node.release();
		}
	}

	@Example
	public void shouldKeepRunningWhenItIsWokenWithoutAStop() {
		final RunningNode node = new RunningNode();

		try {
			node.awaitStarting();
			node.letStartFinish();
			node.awaitWaitingForStop();

			wakeWithoutAStop();
			node.assertKeepsRunning();

			CDIContext.stop();
			node.awaitReturn();
		} finally {
			node.release();
		}
	}
}
