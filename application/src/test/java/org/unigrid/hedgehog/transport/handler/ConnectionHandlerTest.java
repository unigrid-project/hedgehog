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
import io.netty.incubator.codec.quic.QuicConnectionEvent;
import jakarta.inject.Inject;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
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

	private static final int PORT = 4000;

	/* A ceiling that an operator gave: more than one address may hold, and few enough to fill with a host each */
	private static final int CEILING = 50;
	private static final int REFUSED = 100;
	private static final long MINUTE = Duration.ofMinutes(1).toNanos();

	private final Logger logger = (Logger) LoggerFactory.getLogger(ConnectionHandler.class);
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private final List<Object> reached = new ArrayList<>();

	/* The clock of the limiter, which stands still unless an example moves it */
	private final AtomicLong clock = new AtomicLong();

	@BeforeTry
	public void listen() {
		clock.set(0);
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

	/* The event of the codec that tells where a connection comes from, which only the codec can make */
	@SneakyThrows
	private static QuicConnectionEvent comingFrom(InetAddress address) {
		final Constructor<QuicConnectionEvent> constructor = QuicConnectionEvent.class
			.getDeclaredConstructor(SocketAddress.class, SocketAddress.class);

		constructor.setAccessible(true);
		return constructor.newInstance(null, new InetSocketAddress(address, PORT));
	}

	/* Hosts that have nothing to do with each other, none of them the loopback address: 10.0.0.1, 10.0.1.1 and on */
	@SneakyThrows
	private static InetAddress host(int n) {
		return InetAddress.getByAddress(new byte[] { 10, (byte) (n / 256), (byte) (n % 256), 1 });
	}

	/* A connection as the server sets one up, with a handler of its own and the limiter that all of them share, once
	   the codec has told where it comes from. One that the handler refuses is closed by then. */
	private static EmbeddedChannel connectionFrom(ConnectionLimiter limiter, InetAddress address) {
		final EmbeddedChannel connection = new EmbeddedChannel(new ConnectionHandler(limiter));

		connection.pipeline().fireUserEventTriggered(comingFrom(address));
		return connection;
	}

	/* Each host connects once, until the node holds all that the limiter admits */
	private static void fill(ConnectionLimiter limiter) {
		IntStream.range(0, limiter.getCeiling())
			.forEach(n -> assertThat(connectionFrom(limiter, host(n)).isOpen(), is(true)));
	}

	/* A host that holds nothing yet, which a node that is full refuses for its ceiling alone */
	private static boolean refusesOneMore(ConnectionLimiter limiter, int n) {
		return !connectionFrom(limiter, host(limiter.getCeiling() + n)).isOpen();
	}

	private static String ceilingReached(int ceiling) {
		return "The ceiling of " + ceiling + " inbound connections is reached, new peers are refused; a seed may "
			+ "need more: option --netmaxinbound";
	}

	/* A node that holds all it admits may refuse peers by the thousand, and tells its operator of it once */
	@Example
	public void warnsOnceOfTheCeilingForAHundredConnectionsRefusedWithinAMinute() {
		final ConnectionLimiter limiter = new ConnectionLimiter(CEILING, clock::get);

		fill(limiter);
		assertThat(warnings(), empty());

		for (int n = 0; n < REFUSED; n++) {
			assertThat(refusesOneMore(limiter, n), is(true));
			clock.addAndGet((MINUTE - 1) / REFUSED);
		}

		assertThat(warnings(), contains(ceilingReached(CEILING)));
	}

	/* The next warning comes with the first connection that is refused a minute or more after the last one */
	@Example
	public void warnsOfTheCeilingOnceMoreAfterAMinute() {
		final ConnectionLimiter limiter = new ConnectionLimiter(CEILING, clock::get);

		fill(limiter);
		assertThat(refusesOneMore(limiter, 0), is(true));

		clock.addAndGet(MINUTE - 1);
		assertThat(refusesOneMore(limiter, 1), is(true));
		assertThat(warnings(), contains(ceilingReached(CEILING)));

		clock.addAndGet(1);

		for (int n = 0; n < REFUSED; n++) {
			assertThat(refusesOneMore(limiter, 2 + n), is(true));
		}

		assertThat(warnings(), contains(ceilingReached(CEILING), ceilingReached(CEILING)));
	}

	/* The ceiling that a node has unless it is given another, which the warning names */
	@Example
	public void namesTheCeilingOfANodeThatWasGivenNone() {
		final ConnectionLimiter limiter = new ConnectionLimiter();

		IntStream.range(0, limiter.getCeiling()).forEach(n -> assertThat(limiter.admit(host(n)), is(true)));

		assertThat(refusesOneMore(limiter, 0), is(true));
		assertThat(warnings(), contains(ceilingReached(ConnectionLimiter.MAX_IN_ALL)));
	}

	/* An address that holds its share is refused for that, a minute apart each time so that a warning would be due
	   with every one, and no ceiling that the operator could raise has a part in it */
	@Example
	public void raisesNoWarningForAnAddressThatHoldsItsShare() {
		final ConnectionLimiter limiter = new ConnectionLimiter(CEILING, clock::get);

		for (int n = 0; n < ConnectionLimiter.MAX_PER_ADDRESS; n++) {
			assertThat(connectionFrom(limiter, host(0)).isOpen(), is(true));
		}

		for (int n = 0; n < REFUSED; n++) {
			assertThat(connectionFrom(limiter, host(0)).isOpen(), is(false));
			clock.addAndGet(MINUTE);
		}

		assertThat(warnings(), empty());
	}

	/* The last connection that the node admits fills it, and is no reason to warn: nothing was refused yet */
	@Example
	public void raisesNoWarningBelowTheCeiling() {
		final ConnectionLimiter limiter = new ConnectionLimiter(CEILING, clock::get);

		fill(limiter);

		assertThat(warnings(), empty());
	}

	/* The least ceiling there is, which one address fills alone: one more of that address is refused for its share,
	   and one of any other address for the ceiling */
	@Example
	public void warnsOfTheCeilingForAnotherAddressThanTheOneThatHoldsAllTheNodeAdmits() {
		final ConnectionLimiter limiter = new ConnectionLimiter(ConnectionLimiter.MAX_PER_ADDRESS, clock::get);

		for (int n = 0; n < ConnectionLimiter.MAX_PER_ADDRESS; n++) {
			assertThat(connectionFrom(limiter, host(0)).isOpen(), is(true));
		}

		assertThat(connectionFrom(limiter, host(0)).isOpen(), is(false));
		assertThat(warnings(), empty());

		assertThat(connectionFrom(limiter, host(1)).isOpen(), is(false));
		assertThat(warnings(), contains(ceilingReached(ConnectionLimiter.MAX_PER_ADDRESS)));
	}
}
