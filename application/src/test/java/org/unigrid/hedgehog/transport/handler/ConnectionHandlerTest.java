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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.sameInstance;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.cdi.ProtectedInterceptor;
import org.unigrid.hedgehog.model.network.ChannelMap;
import org.unigrid.hedgehog.model.network.ConnectionLimiter;
import org.unigrid.hedgehog.model.network.Topology;

/* The handler of a QUIC connection acts on the events that tell of the address of the connection and has nothing to say
   of any other, such as the one that tells a handshake is done, which every connection raises: those are left alone
   and go on to the handlers behind it, in a node that has its topology. */
@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class }, scan = false)
public class ConnectionHandlerTest extends BaseMockedWeldTest {
	/* Only starts the container that CDI.current() reaches the topology in */
	@Inject private Topology topology;

	private final Logger logger = (Logger) LoggerFactory.getLogger(ConnectionHandler.class);
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private final List<Object> reached = new ArrayList<>();

	@BeforeTry
	public void listen() {
		reached.clear();
		appender.list.clear();
		appender.start();
		logger.addAppender(appender);
	}

	@AfterTry
	public void stopListening() {
		logger.detachAppender(appender);
		appender.stop();
	}

	@Provide
	Arbitrary<Object> eventsOfNoConcern() {
		return Arbitraries.of(SslHandshakeCompletionEvent.SUCCESS, ChannelInputShutdownEvent.INSTANCE, "an event",
			new Object());
	}

	/* A connection as the server has it: this handler, then the one that follows it */
	private EmbeddedChannel connection() {
		return new EmbeddedChannel(new ConnectionHandler(new ConnectionLimiter()), new ChannelInboundHandlerAdapter() {
			@Override
			public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
				reached.add(evt);
			}
		});
	}

	private List<String> warnings() {
		synchronized (appender) {
			return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
				.map(ILoggingEvent::getFormattedMessage).toList();
		}
	}

	@Property(tries = 4)
	public void raisesNoWarningForAUserEventOfNoConcern(@ForAll("eventsOfNoConcern") Object event) {
		connection().pipeline().fireUserEventTriggered(event);
		assertThat(warnings(), empty());
	}

	@Property(tries = 4)
	public void passesAUserEventOfNoConcernOn(@ForAll("eventsOfNoConcern") Object event) {
		connection().pipeline().fireUserEventTriggered(event);
		assertThat(reached, contains(sameInstance(event)));
	}
}
