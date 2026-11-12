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

package org.unigrid.hedgehog.producer;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import org.apache.commons.lang3.reflect.FieldUtils;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.bootstrap.BootstrapSnapshot;
import org.unigrid.hedgehog.model.util.Reflection;

public class BootstrapSnapshotProducerTest {
	private static final long SECONDS = 20;

	/* The first caller is held where the producer reads the data directory, which is after it has seen that no snapshot
	   is held, and a second caller asks meanwhile. The REST resource, the chain height and the installer all inject the
	   snapshot, so callers that ask together on the first use are what the producer is there for, and they must share
	   the one snapshot that is mapped once. */
	@Example
	@SneakyThrows
	public void shouldHandOneSnapshotToCallersThatAskTogetherOnTheFirstUse() {
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicInteger reads = new AtomicInteger();
		final ApplicationDirectory directory = new ApplicationDirectory("test", "test") {
			@Override
			@SneakyThrows
			public Path getUserDataDir() {
				if (reads.incrementAndGet() == 1) {
					release.await();
				}

				return Path.of("/nonexistent");
			}
		};

		final BootstrapSnapshotProducer producer = new BootstrapSnapshotProducer();
		final ExecutorService executor = Executors.newFixedThreadPool(2);

		FieldUtils.writeField(producer, "applicationDirectory", directory, true);

		try {
			final Future<BootstrapSnapshot> first = executor.submit(() -> Reflection.invoke(producer, "produce"));

			await().atMost(SECONDS, TimeUnit.SECONDS).until(() -> reads.get() == 1);

			final Future<BootstrapSnapshot> second = executor.submit(() -> Reflection.invoke(producer, "produce"));

			/* A producer that lets one caller at a time in keeps the second waiting for the first, and one that
			   does not hands the second its own snapshot at once */
			try {
				second.get(1, TimeUnit.SECONDS);
			} catch (TimeoutException expected) {
				/* The second caller waits, which is what the first one needs to finish */
			}

			release.countDown();
			assertThat(first.get(SECONDS, TimeUnit.SECONDS), sameInstance(second.get(SECONDS, TimeUnit.SECONDS)));

		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}
}
