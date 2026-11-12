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

package org.unigrid.hedgehog.transport.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/* A connection as the server has it, on a clock that an example moves, and the streams that its peer opens on it,
   which a connection reads as it reads anything else */
public class StreamlessConnectionHandlerTest {
	private static final int WAIT = StreamlessConnectionHandler.WAIT_SECONDS;
	private static final int LONG = 3600;

	private final List<Object> reached = new ArrayList<>();

	/* The handler, and one behind it that stands for what sets a stream up */
	private EmbeddedChannel connection() {
		final EmbeddedChannel connection = new EmbeddedChannel(new StreamlessConnectionHandler(),
			new ChannelInboundHandlerAdapter() {
				@Override
				public void channelRead(ChannelHandlerContext ctx, Object msg) {
					reached.add(msg);
				}
			});

		connection.freezeTime();
		return connection;
	}

	private static void after(int seconds, EmbeddedChannel connection) {
		connection.advanceTimeBy(seconds, TimeUnit.SECONDS);
		connection.runScheduledPendingTasks();
	}

	private static EmbeddedChannel openStreamOn(EmbeddedChannel connection) {
		final EmbeddedChannel stream = new EmbeddedChannel();

		connection.pipeline().fireChannelRead(stream);
		return stream;
	}

	@Example
	public void closesAConnectionOnWhichNoStreamWasOpenedFor20Seconds() {
		final EmbeddedChannel connection = connection();

		after(WAIT - 1, connection);
		assertThat(connection.isOpen(), is(true));

		after(1, connection);
		assertThat(connection.isOpen(), is(false));
	}

	@Property(tries = 20)
	public void keepsAConnectionThatHoldsAStreamHoweverLong(@ForAll @IntRange(min = 0, max = WAIT - 1) int opened) {
		final EmbeddedChannel connection = connection();

		after(opened, connection);
		openStreamOn(connection);
		after(LONG, connection);

		assertThat(connection.isOpen(), is(true));
	}

	@Example
	public void closesAConnection20SecondsAfterTheLastOfItsStreamsEnded() {
		final EmbeddedChannel connection = connection();
		final EmbeddedChannel first = openStreamOn(connection);
		final EmbeddedChannel last = openStreamOn(connection);

		first.close();
		after(LONG, connection);
		assertThat(connection.isOpen(), is(true));

		last.close();
		after(WAIT - 1, connection);
		assertThat(connection.isOpen(), is(true));

		after(1, connection);
		assertThat(connection.isOpen(), is(false));
	}

	@Property(tries = 20)
	public void keepsAConnectionOnWhichAStreamIsOpenedBeforeTheWaitIsOver(
		@ForAll @IntRange(min = 0, max = WAIT - 1) int reopened) {

		final EmbeddedChannel connection = connection();

		openStreamOn(connection).close();
		after(reopened, connection);

		final EmbeddedChannel stream = openStreamOn(connection);

		after(LONG, connection);
		assertThat(connection.isOpen(), is(true));

		/* The wait starts over with the stream that was opened in it */
		stream.close();
		after(WAIT - 1, connection);
		assertThat(connection.isOpen(), is(true));

		after(1, connection);
		assertThat(connection.isOpen(), is(false));
	}

	@Example
	public void handsAStreamOnToWhatSetsItUp() {
		final EmbeddedChannel connection = connection();
		final EmbeddedChannel stream = openStreamOn(connection);

		assertThat(reached, contains(sameInstance(stream)));
	}
}
