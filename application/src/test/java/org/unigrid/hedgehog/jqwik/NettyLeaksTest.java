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

package org.unigrid.hedgehog.jqwik;

import io.netty.buffer.PooledByteBufAllocator;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class NettyLeaksTest {
	private static final int SIZE = 64;

	@Example
	public void shouldReportNothingForABufferThatWasReleased() throws Exception {
		try (NettyLeaks leaks = NettyLeaks.watch()) {
			PooledByteBufAllocator.DEFAULT.directBuffer(SIZE).release();

			assertThat(leaks.collect(), is(empty()));
		}
	}

	@Example
	public void shouldReportABufferThatWasDroppedUnreleased() throws Exception {
		try (NettyLeaks leaks = NettyLeaks.watch()) {
			PooledByteBufAllocator.DEFAULT.directBuffer(SIZE);

			await().until(leaks::collect, is(not(empty())));
		}
	}
}
