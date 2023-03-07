/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Data;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.apache.commons.configuration2.sync.LockMode;
import org.awaitility.Awaitility;
import static org.awaitility.Awaitility.*;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.*;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.function.VoidFunctionE;

@WeldSetup({ ProtectedInterceptorTest.LockModeProtected.class })
public class ProtectedInterceptorTest extends BaseMockedWeldTest {
	@Data
	@ApplicationScoped
	public static class LockModeProtected {
		@SneakyThrows @Protected @Lock(LockMode.READ)
		void readProtect(VoidFunctionE function) {
			function.apply();
		}

		@SneakyThrows @Protected @Lock(LockMode.WRITE)
		void writeProtect(VoidFunctionE function) {
			function.apply();
		}
	}

	@Inject
	private LockModeProtected lockModeProtected;

	@BeforeContainer
	private static void before() {
		Awaitility.setDefaultPollInterval(5, TimeUnit.MILLISECONDS);
		Awaitility.setDefaultPollDelay(5, TimeUnit.MILLISECONDS);
	}

	@Example
	public void shoulBeAbleToWriteProtectMethods() throws InterruptedException {
		final AtomicInteger counter = new AtomicInteger();

		new Thread(() -> {
			lockModeProtected.writeProtect(() -> {
				counter.incrementAndGet();
				Thread.sleep(50);

				/* 1, because we should not have been able to lock from the main thread */
				assertThat(counter.get(), is(1));
				counter.incrementAndGet();
			});
		}).start();

		/* Make sure the above thread has started and is locking */
		await().untilAtomic(counter, is(1));

		lockModeProtected.writeProtect(() -> {
			counter.incrementAndGet();
		});

		await().untilAtomic(counter, is(3));
	}

	@Example
	public void shoulBeAbleToReadProtectMethods() throws InterruptedException {
		final AtomicInteger counter = new AtomicInteger();

		new Thread(() -> {
			lockModeProtected.writeProtect(() -> {
				counter.incrementAndGet();
				Thread.sleep(50);

				/* 1, because we should not have been able to lock from the main thread */
				assertThat(counter.get(), is(1));
				counter.incrementAndGet();
			});

			lockModeProtected.readProtect(() -> {
				Thread.sleep(50);

				/* 3, because the read lock below should have passed */
				assertThat(counter.get(), is(3));
				counter.incrementAndGet();
			});
		}).start();

		/* Make sure the above thread has started and is locking */
		await().untilAtomic(counter, is(1));

		lockModeProtected.readProtect(() -> {
			counter.incrementAndGet();
			Thread.sleep(50);
		});

		await().untilAtomic(counter, is(4));
	}
}
