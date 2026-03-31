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
import org.unigrid.hedgehog.model.network.handler.BaseHandlerTest;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.jqwik.MockOn;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@SuppressWarnings("unchecked")
public class BaseScheduleTest<S extends Schedulable<Channel>, T extends Packet, H> 
        extends BaseHandlerTest<T, H> {

    private Optional<Consumer<Channel>> scheduleCallback = Optional.empty();
    private final Class<S> scheduleType;
    private final int period;
    private final TimeUnit timeUnit;

    public BaseScheduleTest(int period, TimeUnit timeUnit, Class<S> scheduleType) {
        this(period, timeUnit, scheduleType, (Class<H>) Void.class);
    }

    public BaseScheduleTest(int period, TimeUnit timeUnit, Class<S> scheduleType, Class<H> channelType) {
        super(channelType);
        this.scheduleType = scheduleType;
        this.period = period;
        this.timeUnit = timeUnit;
    }

    public void setScheduleCallback(Optional<Consumer<Channel>> scheduleCallback) {
        this.scheduleCallback = scheduleCallback;
    }

    public Optional<Consumer<Channel>> getScheduleCallback() {
        return scheduleCallback;
    }

    @BeforeProperty
    private void mockBefore() {
        // Mocka schemaparametrar
        new MockUp<AbstractSchedule>() {
            @Mock public int getPeriod(Invocation invocation) {
                return MockOn.instance(scheduleType, invocation, period);
            }

            @Mock public TimeUnit getTimeUnit(Invocation invocation) {
                return MockOn.instance(scheduleType, invocation, timeUnit);
            }

            @Mock public boolean isExecuteOnCreation(Invocation invocation) {
                return MockOn.instance(scheduleType, invocation, false);
            }
        };

        // Mocka schemans consumer
        new MockUp<S>() {
            @Mock
            public Consumer<Channel> getConsumer(Invocation invocation) {
                Consumer<Channel> originalConsumer = invocation.proceed();
                return channel -> {
                    originalConsumer.accept(channel);
                    scheduleCallback.ifPresent(cb -> cb.accept(channel));
                };
            }
        };
    }

    @AfterProperty
    private void clearCallback() {
        scheduleCallback = Optional.empty();
    }
}