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
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.packet.Hello;
import org.unigrid.hedgehog.model.network.schedule.Schedulable;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.network.schedule.PublishAndSaveSporkSchedule;

public class RegisterQuicChannelInitializer
        extends ChannelInitializer<QuicStreamChannel> {

    private static final Logger log =
            LoggerFactory.getLogger(RegisterQuicChannelInitializer.class);

    public static final AttributeKey<Type> CHANNEL_TYPE_KEY =
            AttributeKey.valueOf("CHANNEL_TYPE");

    private final Supplier<List<ChannelHandler>> handlersCreator;
    private final Supplier<List<Schedulable>> schedulersCreator;
    private final Type type;

    /* ====== KONSTRUKTORER ====== */

    public RegisterQuicChannelInitializer(
            Supplier<List<ChannelHandler>> handlersCreator,
            Type type) {

        this.handlersCreator = handlersCreator;
        this.schedulersCreator = null;
        this.type = type;
    }

    public RegisterQuicChannelInitializer(
            Supplier<List<ChannelHandler>> handlersCreator,
            Supplier<List<Schedulable>> schedulersCreator,
            Type type) {

        this.handlersCreator = handlersCreator;
        this.schedulersCreator = schedulersCreator;
        this.type = type;
    }

    /* ====== TYPE ====== */

    public enum Type {
        CLIENT,
        SERVER;

        public boolean is(Channel channel) {
            return this.equals(
                    channel.attr(CHANNEL_TYPE_KEY).get()
            );
        }
    }

    /* ====== INIT ====== */

    @Override
    protected void initChannel(QuicStreamChannel channel) {

        channel.attr(CHANNEL_TYPE_KEY).set(type);

        channel.pipeline().addLast(
                handlersCreator.get().toArray(new ChannelHandler[0])
        );

        /* ==== SEND HELLO ==== */

        if (type == Type.CLIENT) {

            CDIUtil.resolveAndRun(NetOptions.class, options -> {

                log.trace("Sending HELLO to {}", channel.remoteAddress());

                Hello hello = new Hello();
                hello.setPort(options.getPort());

                channel.writeAndFlush(hello);
            });
        }

        /* ==== START SCHEDULERS ==== */

        if (schedulersCreator != null) {

            List<Schedulable> schedulers = schedulersCreator.get();

            if (schedulers != null) {

                for (Schedulable s : schedulers) {

                    if (s.getConsumer() != null) {

                        Future<?> future =
                                channel.eventLoop().scheduleAtFixedRate(
                                        () -> s.getConsumer().accept(channel),
                                        s.isExecuteOnCreation() ? 0 : s.getPeriod(),
                                        s.getPeriod(),
                                        s.getTimeUnit()
                                );

                        channel.closeFuture().addListener(f ->
                                future.cancel(true));
                    }
                }
            }
        }

        /* ==== SEND SPORKS ==== */

        CDIUtil.resolveAndRun(SporkDatabase.class, db -> {

            log.trace("Exchanging sporks with {}",
                    channel.remoteAddress());

            PublishAndSaveSporkSchedule schedule =
                    new PublishAndSaveSporkSchedule();

            schedule.getConsumer().accept(channel);
        });
    }
}