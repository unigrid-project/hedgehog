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
 package org.unigrid.hedgehog.model.network.schedule;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import java.util.concurrent.ScheduledFuture;

public abstract class AbstractSchedule implements Schedulable<Channel> {

    private ScheduledFuture<?> future;

    public final void start(EventLoop eventLoop, Channel channel) {
        if (future != null && !future.isCancelled()) return;

        future = eventLoop.scheduleAtFixedRate(
                () -> getConsumer().accept(channel),
                isExecuteOnCreation() ? 0 : getPeriod(),
                getPeriod(),
                getTimeUnit()
        );
    }

    public final void stop() {
        if (future != null) future.cancel(false);
    }
}