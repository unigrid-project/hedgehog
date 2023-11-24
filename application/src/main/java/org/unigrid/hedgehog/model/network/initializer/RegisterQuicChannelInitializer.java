/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.network.initializer;

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
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.command.option.GridnodeOptions;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.Hello;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.model.network.schedule.PublishAndSaveSporkSchedule;
import org.unigrid.hedgehog.model.network.schedule.Schedulable;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

@Slf4j
@RequiredArgsConstructor
public class RegisterQuicChannelInitializer extends ChannelInitializer<QuicStreamChannel> {
	public static final AttributeKey<Type> CHANNEL_TYPE_KEY = AttributeKey.valueOf("CHANNEL_TYPE");
	private final Supplier<List<ChannelHandler>> handlersCreator;
	private final Supplier<List<Schedulable>> schedulersCreator;
	private final Type type;

	public RegisterQuicChannelInitializer(Supplier<List<ChannelHandler>> handlersCreator, Type type) {
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
		log.atDebug().log("TYPE=CLIENT");
		if (type == Type.CLIENT) {
			log.atTrace().log("Sending HELLO message to {}", channel.remoteAddress());
			channel.writeAndFlush(Hello.builder().port(NetOptions.getPort()).build());
			//TODO: Adam review
			CDIUtil.resolveAndRun(Topology.class, (t) -> {
				log.atTrace().log("Are we sending gridnode to the network");
				if (!StringUtils.isEmpty(GridnodeOptions.getGridnodeKey())) {
					log.atTrace().log("Setting up gridnode and populationg it to the network");
					Gridnode gridnode = Gridnode.builder().id(GridnodeOptions.getGridnodeKey())
						.hostName(NetOptions.getHost() + ":" + NetOptions.getPort()).build();
					t.addGridnode(gridnode);
					channel.writeAndFlush(PublishGridnode.builder().gridnode(gridnode).build());
				}
			});
		}
		log.atDebug().log("type=SERVER");
		if (type == Type.SERVER) {
			try {
				CDIUtil.resolveAndRun(Topology.class, (t) -> {
					if (!StringUtils.isEmpty(GridnodeOptions.getGridnodeKey())) {
						log.atTrace()
							.log("Setting up gridnode and populationg it to the network");
						Gridnode gridnode = Gridnode.builder().id(GridnodeOptions
							.getGridnodeKey())
							.hostName(NetOptions.getHost() + ":" + NetOptions.getPort())
							.build();
						t.addGridnode(gridnode);
						channel.writeAndFlush(PublishGridnode.builder().gridnode(gridnode)
							.build());
					}
				});
			} catch (Exception e) {
				System.out.println(e.getMessage());
				log.error(e.getMessage());
			}
		}
		log.atDebug().log("Schedulers");
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
		log.atDebug().log("CDIUtil");
		CDIUtil.resolveAndRun(SporkDatabase.class, db -> {
			log.atTrace().log("Exchanging sporks with {}", channel.remoteAddress());
			PublishAndSaveSporkSchedule.writeAndFlush(channel, db);
		});
	}
}
