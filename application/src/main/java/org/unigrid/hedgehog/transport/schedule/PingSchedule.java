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

package org.unigrid.hedgehog.transport.schedule;

import io.netty.channel.Channel;
import io.netty.util.Attribute;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.transport.handler.PeerActivityHandler;
import org.unigrid.hedgehog.transport.handler.ProtocolMismatchHandler;
import org.unigrid.hedgehog.model.network.packet.Ping;

@Slf4j
@Data
@EqualsAndHashCode(callSuper = false)
public class PingSchedule extends AbstractSchedule implements Schedulable {
	private static final String UNANSWERED = "answered none of the last " + Ping.MISSED_PINGS_BEFORE_CLOSE + " pings";
	private static final String UNREAD = "read less than " + Ping.LEAST_BYTES_A_PERIOD + " bytes between pings "
		+ Ping.SLOW_PERIODS_BEFORE_CLOSE + " times in a row while its stream took no more";

	public PingSchedule() {
		super(Ping.HEARTBEAT_SECONDS, TimeUnit.SECONDS, true);
	}

	/* A ping still unanswered when the next is due is missed, and anything that arrives on the stream, an answer among
	   it, starts the count again (PeerActivityHandler). A peer that crashed sends no close, so its connection would
	   otherwise stay open until it idles out; closed here, the topology lets go of it as of any closed one.

	   A stream that takes no more is sent no ping, which would only wait on it behind the rest, and what its peer
	   sends meanwhile does not show that the peer reads: it is closed the same way once too little left it. */
	@Override
	public Consumer<Channel> getConsumer() {
		return channel -> {
			final long taken = takenSinceTheRunBefore(channel);

			if (Objects.nonNull(channel.attr(Ping.PING_TIME_KEY).get())
				&& missedOneMore(channel) >= Ping.MISSED_PINGS_BEFORE_CLOSE) {

				closeTheConnectionOf(channel, UNANSWERED);
			} else if (channel.isWritable()) {
				send(channel);
			} else if (readsTooLittle(channel, taken)) {
				closeTheConnectionOf(channel, UNREAD);
			}
		};
	}

	private static void send(Channel channel) {
		final Ping ping = Ping.builder().build();

		channel.attr(Ping.SLOW_PERIODS_KEY).set(null);
		channel.attr(Ping.PING_TIME_KEY).set(ping.getNanoTime());
		channel.writeAndFlush(ping);
	}

	private static int missedOneMore(Channel channel) {
		final int missed = Objects.requireNonNullElse(channel.attr(Ping.MISSED_PINGS_KEY).get(), 0) + 1;

		channel.attr(Ping.MISSED_PINGS_KEY).set(missed);
		return missed;
	}

	/* What left the stream since the run before this one, asked by every run, so that each is told of its own period
	   and of no other */
	private static long takenSinceTheRunBefore(Channel channel) {
		final long taken = PeerActivityHandler.takenOf(channel);

		return taken - Objects.requireNonNullElse(channel.attr(Ping.TAKEN_BEFORE_KEY).getAndSet(taken), 0L);
	}

	/* The periods in a row in which too little left the stream are counted from the run that first finds that it takes
	   no more: the stream still took more in some of the period that this run ends, so the peer is judged by the
	   ones after it. A period in which enough left the stream starts the count again, as a stream that takes more
	   again does (PeerActivityHandler), so only a peer that read too little in every one of them is closed. */
	private static boolean readsTooLittle(Channel channel, long taken) {
		final Attribute<Integer> counted = channel.attr(Ping.SLOW_PERIODS_KEY);
		final int periods = Optional.ofNullable(counted.get()).filter(before -> taken < Ping.LEAST_BYTES_A_PERIOD)
			.map(before -> before + 1).orElse(0);

		counted.set(periods);
		return periods >= Ping.SLOW_PERIODS_BEFORE_CLOSE;
	}

	/* The QUIC connection that the stream runs on, as every stream of a peer that is gone is dead with it, and the
	   stream too: a connection that the peer closed before it went keeps its streams open after a close of its own,
	   until it idles out, and the topology judges a node by its stream */
	private static void closeTheConnectionOf(Channel stream, String fault) {
		final Channel connection = Objects.requireNonNullElse(stream.parent(), stream);

		if (stream.isOpen()) {
			log.atInfo().addArgument(() -> Optional.<Object>ofNullable(connection
				.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).get()).orElse(stream.remoteAddress()))
				.addArgument(fault).log("Closed the connection to {}, which {}");
			connection.close();
			stream.close();
		}
	}
}
