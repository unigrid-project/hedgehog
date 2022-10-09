/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

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

import io.netty.channel.ChannelId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Data;
import lombok.Getter;
import me.alexpanov.net.FreePortFinder;
import mockit.Expectations;
import mockit.Mocked;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Positive;
import net.jqwik.api.constraints.ShortRange;
import org.apache.commons.configuration2.sync.LockMode;
import static org.awaitility.Awaitility.*;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;

@WeldSetup({ ProtectedInterceptorTest.LockModeProtected.class, ProtectedInterceptor.class })
public class ProtectedInterceptorTest extends BaseMockedWeldTest {
	@Data
	@ApplicationScoped
	public static class LockModeProtected {
		final AtomicInteger invocations = new AtomicInteger();

		@Protected(LockMode.READ) void readProtect() { invocations.incrementAndGet(); };
		@Protected(LockMode.WRITE) void writeProtect() { invocations.incrementAndGet(); };
	}

	@Inject
	private LockModeProtected lockModeProtected;

	@Property(tries = 30)
	public boolean shoulBeAbleToProtectMethods(@ForAll @ShortRange short locks, @ForAll LockMode mode) {
		new Thread(() -> {
			for (int i = 0; i < locks; i++) {
				lockModeProtected.writeProtect();
			}
		}).start();

		if (locks > 0) {
			await().until(() -> lockModeProtected.getInvocations().get() > 0);
		}

		lockModeProtected.writeProtect();

		System.out.println("SHIT");



		return true;
	}
}
