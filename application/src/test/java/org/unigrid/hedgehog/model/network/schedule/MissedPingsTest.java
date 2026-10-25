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

package org.unigrid.hedgehog.model.network.schedule;

import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.function.Consumer;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.handler.PingChannelHandler;
import org.unigrid.hedgehog.model.network.packet.Ping;

/* The pings that a peer leaves unanswered, counted by the schedule that sends them and started again by the handler
   that takes an answer, on a stream of its own whose parent stands for the QUIC connection. The network examples of
   PingScheduleTest and PingChannelHandlerTest fail once another example of their class has run before them, so these
   are kept apart. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class MissedPingsTest {
	/* The round trip of an answer goes to the node in the topology, which a stream of its own is in none of */
	@BeforeTry
	public void withoutATopology() {
		new MockUp<CDIUtil>() {
			@Mock void resolveAndRun(Class<?> type, Consumer<?> consumer) { }
		};
	}

	private static EmbeddedChannel streamOf(EmbeddedChannel connection) {
		return new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false, new PingChannelHandler());
	}

	private static void answer(EmbeddedChannel stream) {
		stream.writeInbound(Ping.builder().response(true).build());
	}

	@Example
	public void countsEachPingThatIsStillUnansweredWhenTheNextIsSent() {
		final EmbeddedChannel connection = new EmbeddedChannel();
		final EmbeddedChannel stream = streamOf(connection);
		final Consumer<Channel> ping = new PingSchedule().getConsumer();

		ping.accept(stream);
		assertThat(stream.attr(Ping.MISSED_PINGS_KEY).get(), is(nullValue()));

		for (int missed = 1; missed < Ping.MISSED_PINGS_BEFORE_CLOSE; missed++) {
			ping.accept(stream);
			assertThat(stream.attr(Ping.MISSED_PINGS_KEY).get(), is(missed));
		}

		assertThat(connection.isOpen(), is(true));
		assertThat(stream.outboundMessages(), hasSize(Ping.MISSED_PINGS_BEFORE_CLOSE));
	}

	@Example
	public void closesTheConnectionOfAStreamAfterTheThirdUnansweredPingInARow() {
		final EmbeddedChannel connection = new EmbeddedChannel();
		final EmbeddedChannel stream = streamOf(connection);
		final Consumer<Channel> ping = new PingSchedule().getConsumer();

		for (int sent = 0; sent < Ping.MISSED_PINGS_BEFORE_CLOSE; sent++) {
			ping.accept(stream);
		}

		assertThat(connection.isOpen(), is(true));
		ping.accept(stream);
		assertThat(connection.isOpen(), is(false));
		assertThat(stream.isOpen(), is(false));
	}

	@Example
	public void keepsTheConnectionOfAPeerThatAnswers() {
		final EmbeddedChannel connection = new EmbeddedChannel();
		final EmbeddedChannel stream = streamOf(connection);
		final Consumer<Channel> ping = new PingSchedule().getConsumer();

		for (int sent = 0; sent < Ping.MISSED_PINGS_BEFORE_CLOSE * 4; sent++) {
			ping.accept(stream);
			answer(stream);
		}

		assertThat(connection.isOpen(), is(true));
		assertThat(stream.attr(Ping.MISSED_PINGS_KEY).get(), is(0));
	}

	/* Only misses in a row count: an answer in between starts the count again */
	@Example
	public void startsTheCountAgainAfterAnAnswer() {
		final EmbeddedChannel connection = new EmbeddedChannel();
		final EmbeddedChannel stream = streamOf(connection);
		final Consumer<Channel> ping = new PingSchedule().getConsumer();

		for (int round = 0; round < 3; round++) {
			for (int sent = 0; sent < Ping.MISSED_PINGS_BEFORE_CLOSE; sent++) {
				ping.accept(stream);
			}

			assertThat(stream.attr(Ping.MISSED_PINGS_KEY).get(), is(Ping.MISSED_PINGS_BEFORE_CLOSE - 1));
			answer(stream);
			assertThat(stream.attr(Ping.MISSED_PINGS_KEY).get(), is(0));
		}

		assertThat(connection.isOpen(), is(true));
	}

	/* A request is answered and counts nothing, whatever this node still waits for */
	@Example
	public void leavesTheCountAsItIsOnARequest() {
		final EmbeddedChannel stream = streamOf(new EmbeddedChannel());

		stream.attr(Ping.MISSED_PINGS_KEY).set(1);
		stream.writeInbound(Ping.builder().build());

		assertThat(stream.attr(Ping.MISSED_PINGS_KEY).get(), is(1));
		assertThat(((Ping) stream.readOutbound()).isResponse(), is(true));
	}
}
