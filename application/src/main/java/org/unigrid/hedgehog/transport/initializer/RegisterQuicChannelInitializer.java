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

package org.unigrid.hedgehog.transport.initializer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.packet.Hello;
import org.unigrid.hedgehog.transport.handler.ProtocolMismatchHandler;
import org.unigrid.hedgehog.transport.schedule.PublishSporkSchedule;
import org.unigrid.hedgehog.transport.schedule.Schedulable;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

@Slf4j
@RequiredArgsConstructor
public class RegisterQuicChannelInitializer extends ChannelInitializer<QuicStreamChannel> {
	public static final AttributeKey<Type> CHANNEL_TYPE_KEY = AttributeKey.valueOf("CHANNEL_TYPE");
	private final Supplier<List<ChannelHandler>> handlersCreator;
	private final Supplier<List<Schedulable>> schedulersCreator;
	private final Type type;

	public RegisterQuicChannelInitializer(Supplier<List<ChannelHandler>> handlersCreator, Type type) {
		this(handlersCreator, List::of, type);
	}

	public enum Type {
		CLIENT, SERVER;

		public boolean is(Channel channel) {
			return this.equals(channel.pipeline().channel().attr(CHANNEL_TYPE_KEY).get());
		}
	}

	@Override
	protected void initChannel(QuicStreamChannel channel) throws Exception {
		channel.pipeline().channel().attr(CHANNEL_TYPE_KEY).set(type);
		channel.pipeline().addLast(handlersCreator.get().toArray(new ChannelHandler[0]));
		log.atDebug().log("TYPE=CLIENT");
		if (type == Type.CLIENT) {
			log.atTrace().log("Sending HELLO message to {}", channel.remoteAddress());
			channel.writeAndFlush(Hello.builder().port(NetOptions.getPort()).build());
		}
		log.atDebug().log("Schedulers");

		/* Asked once, as a supplier may hand out a different list each time */
		final List<Schedulable> schedulers = schedulersCreator.get();

		if (Objects.nonNull(schedulers)) {
			schedulers.forEach(s -> schedule(channel, s));
		}
		log.atDebug().log("CDIUtil");
		CDIUtil.resolveAndRun(SporkDatabase.class, db -> {
			CDIUtil.resolveAndRun(PendingSporks.class, pendingSporks -> {
				log.atTrace().log("Exchanging sporks with {}", channel.remoteAddress());
				PublishSporkSchedule.writeAndFlush(channel, db, pendingSporks);
			});
		});
	}

	private static void schedule(QuicStreamChannel channel, Schedulable s) {
		/* Netty schedulers just support Callable<A>, so in order to support something like a
		Function<A, B> we wrap it in a Callable and call the callback in our scheduler. */

		if (Objects.nonNull(s.getConsumer())) {
			/* The period is counted from the end of a run, as one counted from the start would make up for
			   the runs that a stalled event loop missed by making them back to back, each as one more
			   ping unanswered. */
			final Future<?> future = channel.eventLoop().scheduleWithFixedDelay(
				() -> runOnce(channel, s), s.isExecuteOnCreation() ? 0 : s.getPeriod(), s.getPeriod(),
				s.getTimeUnit());

			channel.closeFuture().addListener(f -> {
				future.cancel(true);
			});
		}
	}

	/* A periodic task is never run again once a run of it throws, and says nothing of it, so a run that fails
	   is logged here and the next one is still made: a ping that stopped would leave a dead peer open. */
	private static void runOnce(QuicStreamChannel channel, Schedulable s) {
		try {
			s.getConsumer().accept(channel);
		} catch (Throwable ex) {
			log.atWarn().setCause(ex).addArgument(() -> peerOf(channel))
				.log("A scheduled run on the stream to {} failed, the next is still made");
		}
	}

	/* A stream names itself by its number alone, which is the same for the main stream of every peer, so the peer is
	   named by the address that the connection of the stream holds for it */
	private static Object peerOf(QuicStreamChannel channel) {
		return Optional.<Object>ofNullable(channel.parent().attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).get())
			.orElse(channel.remoteAddress());
	}
}
