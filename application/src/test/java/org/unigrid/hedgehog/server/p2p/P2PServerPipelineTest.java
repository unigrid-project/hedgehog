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

package org.unigrid.hedgehog.server.p2p;

import io.netty.channel.ChannelHandler;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.model.network.initializer.LedgerPipeline;
import org.unigrid.hedgehog.model.network.initializer.StoragePipeline;
import org.unigrid.hedgehog.model.network.schedule.LedgerStatusSchedule;

public class P2PServerPipelineTest {
	private static List<Class<?>> classesOf(final List<? extends ChannelHandler> handlers) {
		return handlers.stream().<Class<?>>map(ChannelHandler::getClass).toList();
	}

	@Example
	public void endsWithTheStoragePipelineAndThenTheLedgerPipelineWithTheLedgerProtocol() {
		final List<Class<?>> handlers = classesOf(P2PServer.handlers(true));
		final List<Class<?>> appended = classesOf(Stream.concat(StoragePipeline.handlers().stream(),
			LedgerPipeline.handlers().stream()).toList());

		assertThat(Collections.indexOfSubList(handlers, appended), equalTo(handlers.size() - appended.size()));
	}

	@Example
	public void endsWithTheStoragePipelineAndHasNoLedgerStageWithoutTheLedgerProtocol() {
		final List<Class<?>> handlers = classesOf(P2PServer.handlers(false));
		final List<Class<?>> storage = classesOf(StoragePipeline.handlers());

		assertThat(Collections.indexOfSubList(handlers, storage), equalTo(handlers.size() - storage.size()));
		classesOf(LedgerPipeline.handlers()).stream().filter(type -> !storage.contains(type))
			.forEach(type -> assertThat(handlers, not(hasItem(type))));
	}

	@Example
	public void runsTheLedgerScheduleOnlyWithTheLedgerProtocol() {
		final List<Class<?>> earlier = P2PServer.schedulers(false).stream().<Class<?>>map(Object::getClass).toList();
		final List<Class<?>> later = P2PServer.schedulers(true).stream().<Class<?>>map(Object::getClass).toList();

		assertThat(earlier, not(hasItem(LedgerStatusSchedule.class)));
		assertThat(later, hasItems(earlier.toArray(new Class<?>[0])));
		assertThat(later, hasItem(LedgerStatusSchedule.class));
		assertThat(later.size(), equalTo(earlier.size() + 1));
	}
}
