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

package org.unigrid.hedgehog.proxy.exit;

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.Close;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.Connect;
import org.unigrid.hedgehog.proxy.session.Data;
import org.unigrid.hedgehog.proxy.session.DestinationPolicy;
import org.unigrid.hedgehog.proxy.session.Resolver;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class ExitPacingTest {
	private static final Connect CONNECT = new Connect(Socks5CommandType.CONNECT, new SocksAddress(
		Socks5AddressType.IPv4, "93.184.215.14", 443));

	private final FakeConnector connector = new FakeConnector();
	private final SessionPair pair = new SessionPair(CONNECT);
	private final ExitRelay relay = new ExitRelay(pair.exit, new ExitContext(ProxySettings.DEFAULTS,
		DestinationPolicy.publicOnly(), Resolver.system(), Runnable::run, connector));
	private final FakeConnector.FakeTarget target;

	public ExitPacingTest() throws UnknownHostException {
		target = new FakeConnector.FakeTarget(new InetSocketAddress(ExitRelayTest.address("192.0.2.7"), 40_000));
		pair.toExit().forEach(relay::handle);
	}

	private void sendData() {
		pair.client.send(new Data(new byte[] { 1 }));
		pair.toExit().forEach(relay::handle);
	}

	@Example
	public void shouldStopReadingTheDestinationWhileTheChainIsFull() {
		connector.last().listener().connected(target);
		assertThat(target.readable, is(true));
		relay.setChainWritable(false);
		assertThat(target.readable, is(false));
		relay.setChainWritable(true);
		assertThat(target.readable, is(true));
	}

	@Example
	public void shouldStartPausedWhenTheChainIsFullAlready() {
		relay.setChainWritable(false);
		connector.last().listener().connected(target);
		assertThat(target.readable, is(false));
	}

	@Example
	public void shouldStartPausedWhenTheHostPausedBeforeTheConnect() throws UnknownHostException {
		final SessionPair fresh = new SessionPair(CONNECT);
		final ExitRelay waiting = new ExitRelay(fresh.exit, new ExitContext(ProxySettings.DEFAULTS,
			DestinationPolicy.publicOnly(), Resolver.system(), Runnable::run, connector));

		waiting.setChainWritable(false);
		fresh.toExit().forEach(waiting::handle);
		connector.last().listener().connected(target);
		assertThat(target.readable, is(false));
	}

	@Example
	public void shouldNotResumeReadingOnceTheSessionClosed() {
		connector.last().listener().connected(target);
		relay.setChainWritable(false);
		relay.close(CloseReason.DONE);
		relay.setChainWritable(true);
		assertThat(target.readable, is(false));
	}

	@Example
	public void shouldDropASessionWhoseDestinationFallsTooFarBehind() {
		connector.last().listener().connected(target);
		pair.toClient();
		target.backlog = backlogLimit();
		sendData();
		assertThat(relay.isOpen(), is(true));
		target.backlog = backlogLimit() + 1;
		sendData();
		assertThat(relay.isOpen(), is(false));
		assertThat(target.closed, is(true));
		assertThat(pair.toClient(), equalTo(List.of(new Close(CloseReason.BROKEN))));
	}

	@Example
	public void shouldCountTheDataItJustQueuedTowardsTheBacklog() {
		connector.last().listener().connected(new StalledTarget(target));
		target.backlog = backlogLimit() - 1;
		sendData();
		assertThat(relay.isOpen(), is(true));
		sendData();
		assertThat(relay.isOpen(), is(false));
	}

	/* The 64 entries are written out on purpose: a node's relay budget is sized on them, so a change of the
	   constant must fail here */
	private long backlogLimit() {
		return 64L * pair.exit.maxDataBytes();
	}

	/* A destination that takes nothing: what the relay writes stays queued on top of the backlog the test sets */
	private static final class StalledTarget implements Target {
		private final FakeConnector.FakeTarget queue;

		StalledTarget(FakeConnector.FakeTarget queue) {
			this.queue = queue;
		}

		@Override
		public InetSocketAddress localAddress() {
			return queue.localAddress();
		}

		@Override
		public void write(byte[] bytes) {
			queue.write(bytes);
		}

		@Override
		public long backlogBytes() {
			return queue.backlog + queue.written.size();
		}

		@Override
		public void setReadable(boolean readable) {
			queue.setReadable(readable);
		}

		@Override
		public void close() {
			queue.close();
		}
	}
}
