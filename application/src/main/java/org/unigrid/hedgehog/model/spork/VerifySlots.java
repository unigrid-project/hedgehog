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

package org.unigrid.hedgehog.model.spork;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

/**
* Gives out the right to check the signatures of a spork, which is costly and may be asked for by anyone. A source,
* which is the peer that sent the spork, has one right a second for each type, so a peer that sends forged sporks
* uses up only its own and leaves the genuine spork of another peer its turn. Of a type at most {@link #CEILING} are
* given in a second whoever asks, so that many sources cost a bounded amount of work.
*
* <p>Only a right that was given is remembered, and it is forgotten once a second has passed, so what is held
* never exceeds the rights of two seconds, however many sources there are.
*/
final class VerifySlots {
	/* A check of a signature is milliseconds of one core, so a few dozen a second for each type cost nothing that
	   is noticed. A genuine spork is sent by every honest peer again and again, and needs only one of them to
	   get a turn. */
	static final int CEILING = 32;

	private record Slot(Type type, String source) {
	}

	private record Window(Instant start, int taken) {
	}

	private record Taken(Instant at, int charged) {
	}

	private final Supplier<Instant> clock;
	private final Map<Slot, Taken> held = new HashMap<>();
	private final Map<Type, Window> windows = new EnumMap<>(Type.class);
	private Instant sweptAt;

	VerifySlots(final Supplier<Instant> clock) {
		this.clock = clock;
	}

	synchronized boolean take(final Type type, final String source) {
		return take(type, source, 1);
	}

	/* The lock is held for the decision only, never while a signature is checked. A spork that costs more than one
	   check, as one of the board keys does, is charged as many, but never more than the ceiling, so that one which
	   costs that much is checked in a second of its own. */
	synchronized boolean take(final Type type, final String source, final int checks) {
		final Instant now = clock.get();
		final Slot slot = new Slot(type, source);
		final Window window = windowOf(type, now);
		final int charged = Math.clamp(checks, 1, CEILING);

		sweep(now);

		if (hasPassed(atOf(held.get(slot)), now) && window.taken() + charged <= CEILING) {
			held.put(slot, new Taken(now, charged));
			windows.put(type, new Window(window.start(), window.taken() + charged));
			return true;
		}

		return false;
	}

	/* A right that was used on a genuine spork is given back, so a peer that sends several in a row has each of them
	   checked, and only a check that fails costs a peer its turn */
	synchronized void giveBack(final Type type, final String source) {
		final Taken taken = held.remove(new Slot(type, source));

		if (Objects.nonNull(taken)) {
			windows.computeIfPresent(type, (key, window) -> new Window(window.start(),
				Math.max(0, window.taken() - taken.charged())));
		}
	}

	private static Instant atOf(final Taken taken) {
		return Objects.isNull(taken) ? null : taken.at();
	}

	synchronized int tracked() {
		return held.size();
	}

	private Window windowOf(final Type type, final Instant now) {
		final Window window = windows.get(type);

		return Objects.isNull(window) || hasPassed(window.start(), now) ? new Window(now, 0) : window;
	}

	private void sweep(final Instant now) {
		if (hasPassed(sweptAt, now)) {
			held.values().removeIf(taken -> hasPassed(taken.at(), now));
			sweptAt = now;
		}
	}

	private static boolean hasPassed(final Instant since, final Instant now) {
		return Objects.isNull(since) || !now.isBefore(since.plus(SporkLag.VERIFY_GAP));
	}
}
