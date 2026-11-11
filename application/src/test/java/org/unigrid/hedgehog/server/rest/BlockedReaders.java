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

package org.unigrid.hedgehog.server.rest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/* The threads of the container that wait in the stream of a request body for more of it, as a resource does that reads
   a body which has no end yet. Those that waited when the set was made are left out, so a thread that an earlier test
   left behind is not taken for one of this test. */
final class BlockedReaders {
	private final Set<Thread> before = waiting();

	private static Set<Thread> waiting() {
		return Thread.getAllStackTraces().entrySet().stream()
			.filter(entry -> Stream.of(entry.getValue()).anyMatch(frame -> frame.getClassName()
				.endsWith("NettyInputStream") && "awaitNext".equals(frame.getMethodName())))
			.map(Map.Entry::getKey).collect(Collectors.toSet());
	}

	List<String> started() {
		return waiting().stream().filter(thread -> !before.contains(thread)).map(Thread::getName).sorted().toList();
	}
}
