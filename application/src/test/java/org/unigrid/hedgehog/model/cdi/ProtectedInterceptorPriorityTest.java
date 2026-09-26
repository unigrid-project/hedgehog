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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import org.apache.commons.configuration2.sync.LockMode;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.*;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.function.VoidFunctionE;

/* Boots the container like the application does, without enabling the interceptor explicitly */
@WeldSetup(value = { ProtectedInterceptor.class, ProtectedInterceptorPriorityTest.WriteProtected.class }, scan = false)
public class ProtectedInterceptorPriorityTest extends BaseMockedWeldTest {
	private static final long BLOCKED_MS = 200;

	@ApplicationScoped
	public static class WriteProtected {
		@SneakyThrows @Protected @Lock(LockMode.WRITE)
		void writeProtect(VoidFunctionE function) {
			function.apply();
		}
	}

	@Inject
	private WriteProtected writeProtected;

	@Example
	public void shouldEnableInterceptorWithoutExplicitRegistration() throws InterruptedException {
		final CountDownLatch holding = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch entered = new CountDownLatch(1);

		new Thread(() -> writeProtected.writeProtect(() -> {
			holding.countDown();
			release.await();
		})).start();

		holding.await();
		new Thread(() -> writeProtected.writeProtect(entered::countDown)).start();

		assertThat(entered.await(BLOCKED_MS, TimeUnit.MILLISECONDS), is(false));
		release.countDown();
		assertThat(entered.await(BLOCKED_MS, TimeUnit.MILLISECONDS), is(true));
	}
}
