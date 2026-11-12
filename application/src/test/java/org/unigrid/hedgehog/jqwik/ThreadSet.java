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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/* The platform threads that ran at one moment, to tell which ones a test left behind. Virtual threads are not seen. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ThreadSet {
	private final Set<Thread> before;

	public static ThreadSet capture() {
		return new ThreadSet(alive());
	}

	private static Set<Thread> alive() {
		return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).collect(Collectors.toSet());
	}

	/* The names of the threads that run now and did not when the set was captured */
	public List<String> started() {
		return alive().stream().filter(thread -> !before.contains(thread)).map(Thread::getName).sorted().toList();
	}
}
