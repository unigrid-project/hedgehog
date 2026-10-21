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

package org.unigrid.hedgehog.model.network;

import io.netty.handler.logging.LoggingHandler;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.slf4j.LoggerFactory;

public class StreamLoggingTest {
	/* With the logger enabled, the handler at the head of every stream pipeline writes a hex dump of each frame it
	   reads and writes on the event loop of the stream. A block of a thousand mints is some 70 kilobytes, and
	   dumping it takes the loop for tens of milliseconds each time, so that the streams of a network of nodes in
	   one process drain in seconds instead of milliseconds. The tests start at the debug level, so the logger is
	   set apart in their configuration. */
	@Example
	public void shouldNotHexDumpTheFramesOfStreamsWhileTesting() {
		assertThat(LoggerFactory.getLogger(LoggingHandler.class).isDebugEnabled(), is(false));
	}
}
