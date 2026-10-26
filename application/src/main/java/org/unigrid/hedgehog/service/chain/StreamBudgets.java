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

import io.netty.channel.Channel;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/* How much one stream may ask of the host in one second. A request over the budget gets nothing for it: a join is
   answered FULL and the rest are dropped, so a stream that floods uses up its own budget only, and a member that
   really bursts catches up through the reconcile rounds. */
final class StreamBudgets {
	/* The units are what the request costs the host: joins for a join, entries to verify for a CHAIN_ENTRY,
	   one per CHAIN_HEADS, ranges served for a fetch and the bytes of what it answers, since an answer is up
	   to a frame of entries and the ranges alone would let a stream have eight of them in a second. The
	   signature checks are the dear part, so the entries keep one stream to a fraction of a core while every
	   honest flow stays far below it. */
	@Getter @RequiredArgsConstructor
	enum Kind {
		JOINS(8), ENTRIES(4096), HEADS(16), RANGES(64), FETCH_BYTES(32 * 1024 * 1024);

		private final int perSecond;
	}

	private static final long WINDOW_MILLIS = 1000;

	/* A window opens with the first request after the last one closed and lasts a second of the clock. Being fixed, it
	   lets a stream have up to twice its budget across the turn from one window to the next, so at most twice the
	   budget in any 1000 ms and the budget per second sustained. A clock that steps back opens the window again where
	   it now stands and keeps the amount used in it: the step hands out nothing, and a stream that used up its budget
	   waits a second, not until the clock is back where it was. */
	private static final class Window {
		private long opened;
		private int used;

		Window(final long now) {
			opened = now;
		}

		/* A negative count would hand budget back, and a count is held against what is left rather than added to
		   what was used, since the sum can overflow and get in */
		synchronized boolean take(final long now, final int units, final int limit) {
			if (now < opened) {
				opened = now;
			} else if (now - opened >= WINDOW_MILLIS) {
				opened = now;
				used = 0;
			}

			if (units < 0 || units > limit - used) {
				return false;
			}

			used += units;
			return true;
		}
	}

	private final LongSupplier clock;

	/* Weakly keyed, so the windows of a closed stream go with the stream */
	private final Map<Channel, Map<Kind, Window>> windows = Collections.synchronizedMap(new WeakHashMap<>());

	StreamBudgets(final LongSupplier clock) {
		this.clock = clock;
	}

	private static Map<Kind, Window> fresh(final long now) {
		final Map<Kind, Window> own = new EnumMap<>(Kind.class);

		for (final Kind kind : Kind.values()) {
			own.put(kind, new Window(now));
		}

		return own;
	}

	/* A request from no stream is refused, as the map would keep one window for all of them and never let it go */
	boolean take(final Channel peer, final Kind kind, final int units) {
		if (peer == null) {
			return false;
		}

		/* Read outside the lock of the window: one event loop serves a stream, so its readings come in order */
		final long now = clock.getAsLong();

		return windows.computeIfAbsent(peer, stream -> fresh(now)).get(kind).take(now, units, kind.getPerSecond());
	}
}
