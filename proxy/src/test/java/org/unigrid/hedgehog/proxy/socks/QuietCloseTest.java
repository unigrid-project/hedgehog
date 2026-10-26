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

package org.unigrid.hedgehog.proxy.socks;

import io.netty.channel.embedded.EmbeddedChannel;
import java.net.SocketException;
import java.util.ArrayDeque;
import java.util.Queue;
import net.jqwik.api.Example;
import org.slf4j.event.Level;
import org.slf4j.event.SubstituteLoggingEvent;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.SubstituteLogger;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public class QuietCloseTest {
	/* The message of a failure may name the client, so only its class is logged, and without a stack trace */
	@Example
	public void shouldCloseAFailedConnectionAndLogTheClassOfTheFailureAtDebugLevel() {
		final Queue<SubstituteLoggingEvent> events = new ArrayDeque<>();
		final EmbeddedChannel channel = new EmbeddedChannel(new QuietClose(new SubstituteLogger("socks", events,
			false)));

		channel.pipeline().fireExceptionCaught(new SocketException("Connection reset by 192.0.2.7"));
		channel.checkException();

		assertThat(channel.isOpen(), is(false));
		assertThat(events.size(), is(1));

		final SubstituteLoggingEvent event = events.peek();
		final String logged = MessageFormatter.arrayFormat(event.getMessage(), event.getArgumentArray()).getMessage();

		assertThat(event.getLevel(), is(Level.DEBUG));
		assertThat(logged, containsString(SocketException.class.getName()));
		assertThat(logged, not(containsString("192.0.2.7")));
		assertThat(event.getThrowable(), is(nullValue()));
	}
}
