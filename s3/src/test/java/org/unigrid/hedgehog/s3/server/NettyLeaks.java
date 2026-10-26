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

package org.unigrid.hedgehog.s3.server;

import io.netty.buffer.AbstractByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.RequiredArgsConstructor;

/*
   Tracks every Netty buffer, as -Dio.netty.leakDetection.level=paranoid would, and keeps what Netty reports as
   leaked. The detector of the buffers is not public, and its level and listener are the only ways to watch it.
*/
@RequiredArgsConstructor
final class NettyLeaks implements ResourceLeakDetector.LeakListener, AutoCloseable {
	private static final int ROUNDS = 3;
	private static final Duration PAUSE = Duration.ofMillis(100);

	private final ResourceLeakDetector<?> detector;
	private final ResourceLeakDetector.Level level = ResourceLeakDetector.getLevel();
	private final List<String> leaks = new CopyOnWriteArrayList<>();

	public static NettyLeaks watch() throws ReflectiveOperationException {
		final Field field = AbstractByteBuf.class.getDeclaredField("leakDetector");

		field.setAccessible(true);

		final NettyLeaks leaks = new NettyLeaks((ResourceLeakDetector<?>) field.get(null));

		ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
		leaks.detector.setLeakListener(leaks);
		return leaks;
	}

	/* A leak is only noticed once its buffer was collected and another buffer is tracked */
	public List<String> collect() throws InterruptedException {
		for (int round = 0; round < ROUNDS; round++) {
			System.gc();
			Thread.sleep(PAUSE);
			PooledByteBufAllocator.DEFAULT.directBuffer().release();
		}

		return leaks;
	}

	@Override
	public void onLeak(final String type, final String records) {
		leaks.add(type + records);
	}

	@Override
	public void close() {
		detector.setLeakListener(null);
		ResourceLeakDetector.setLevel(level);
	}
}
