/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.network.schedule.Schedulable;

@RequiredArgsConstructor
public class RegisterQuicChannelHandler extends ChannelInitializer<QuicStreamChannel> {
	public static final AttributeKey<Type> CHANNEL_TYPE_KEY = AttributeKey.valueOf("CHANNEL_TYPE");
	private final Supplier<List<ChannelHandler>> handlersCreator;
	private final Supplier<List<Schedulable>> schedulersCreator;
	private final Type type;

	public RegisterQuicChannelHandler(Supplier<List<ChannelHandler>> handlersCreator, Type type) {
		this(handlersCreator, null, type);
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

		if (Objects.nonNull(schedulersCreator.get())) {
			schedulersCreator.get().forEach(s -> {

				/* Netty schedulers just support Callable<A>, so in order to support something like a
				Function<A, B> we wrap it in a Callable and call the callback in our scheduler. */

				if (Objects.nonNull(s.getConsumer())) {
					final Future<?> future = channel.eventLoop().scheduleAtFixedRate(() -> {
						s.getConsumer().accept(channel);
					}, s.isExecuteOnCreation() ? 0 : s.getPeriod(), s.getPeriod(), s.getTimeUnit());

					channel.closeFuture().addListener(f -> {
						future.cancel(true);
					});
				}
			});
		}
	}
}
