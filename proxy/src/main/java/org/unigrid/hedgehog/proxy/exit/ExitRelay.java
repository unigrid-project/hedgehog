/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.util.NetUtil;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.proxy.session.Close;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.Connect;
import org.unigrid.hedgehog.proxy.session.Connected;
import org.unigrid.hedgehog.proxy.session.Data;
import org.unigrid.hedgehog.proxy.session.DestinationRefusedException;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.Session;
import org.unigrid.hedgehog.proxy.session.SocksAddress;

/* The exit's end of one session. The host hands it every message the session opens, and the Close the session
   produced, through handle; it calls tick on its schedule and close when it drops the session. On the client's
   Connect the relay checks the spork's flags, admits the destination through the policy, connects to exactly
   the admitted addresses and answers Connected; then it relays the stream until either side closes. */
public final class ExitRelay {
	/* How far, in entries, the destination may fall behind the client before the session is dropped. The chain
	   has no flow control per session, so this bound is what keeps a slow destination from holding the exit's
	   memory without end. */
	public static final int BACKLOG_ENTRIES = 64;

	private static final Logger LOG = LoggerFactory.getLogger(ExitRelay.class);
	private static final Set<Socks5CommandStatus> REFUSALS = Set.of(Socks5CommandStatus.FORBIDDEN,
		Socks5CommandStatus.COMMAND_UNSUPPORTED, Socks5CommandStatus.ADDRESS_UNSUPPORTED);

	private final Session session;
	private final ExitContext context;
	private State state = State.WAITING;
	private Target target;
	private boolean chainWritable = true;

	private enum State {
		/* Before the client's Connect */
		WAITING,
		/* Admitting the destination and connecting to it */
		CONNECTING,
		RELAYING,
		CLOSED
	}

	public ExitRelay(Session session, ExitContext context) {
		this.session = Objects.requireNonNull(session, "session");
		this.context = Objects.requireNonNull(context, "context");
	}

	public synchronized boolean isOpen() {
		return state != State.CLOSED;
	}

	public synchronized void handle(SealedMessage message) {
		if (message instanceof Close) {
			end();
		} else if (state == State.WAITING && message instanceof Connect connect) {
			start(connect);
		} else if (state == State.RELAYING && message instanceof Data data) {
			write(data.bytes());
		} else {
			/* A session carries one Connect, then Data once the exit replied, and nothing else */
			close(CloseReason.BROKEN);
		}
	}

	/* Runs on the host's schedule: a session without traffic either way for sessionIdleSeconds closes */
	public synchronized void tick() {
		try {
			session.tick().ifPresent(idle -> end());
		} finally {
			/* An outbox that refuses the Close has ended the session all the same: the destination must go */
			if (!session.isOpen()) {
				end();
			}
		}
	}

	/* The host calls it with false while its chain refuses sealed entries and with true once the chain takes
	   them again; meanwhile no further read is started on the destination */
	public synchronized void setChainWritable(boolean writable) {
		chainWritable = writable;

		if (state == State.RELAYING) {
			target.setReadable(writable);
		}
	}

	/* Ends the session from the exit's side, tells the client why and drops the destination */
	public synchronized void close(CloseReason reason) {
		try {
			session.close(reason);
		} finally {
			/* An outbox that refuses the Close must not keep the destination socket or the relay slot */
			end();
		}
	}

	private void start(Connect connect) {
		if (!Socks5CommandType.CONNECT.equals(connect.command())) {
			/* UDP ASSOCIATE is not relayed yet */
			refuse(Socks5CommandStatus.COMMAND_UNSUPPORTED);
		} else if (!context.settings().connectAllowed()) {
			refuse(Socks5CommandStatus.FORBIDDEN);
		} else {
			state = State.CONNECTING;
			resolve(connect.destination());
		}
	}

	/* An executor that is full or shut down refuses the lookup, and the client must still get its reply */
	private void resolve(SocksAddress destination) {
		try {
			context.resolving().execute(() -> admit(destination));
		} catch (RejectedExecutionException ex) {
			LOG.warn("Admitting a destination failed with {}", ex.getClass().getName());
			refuse(Socks5CommandStatus.FAILURE);
		}
	}

	/* Runs on the resolving executor and holds no lock, because a lookup blocks. The connector gets the
	   addresses the policy checked and never the name, so a second lookup cannot swap in another address. */
	private void admit(SocksAddress destination) {
		if (!isConnecting()) {
			return;
		}

		try {
			context.connector().connect(context.policy().admit(destination, context.resolver()),
				destination.port(), new Listener());
		} catch (DestinationRefusedException ex) {
			failed(ex.status());
		} catch (RuntimeException ex) {
			/* A resolver that answers null or throws, or a connector that refuses the work, would otherwise
			   leave the relay waiting for a reply that never comes. The message is left out as it may quote
			   the name. */
			LOG.warn("Admitting a destination failed with {}", ex.getClass().getName());
			failed(Socks5CommandStatus.FAILURE);
		}
	}

	/* Short, so the lookup that follows it is not done under the lock; a client that has gone costs no lookup */
	private synchronized boolean isConnecting() {
		return state == State.CONNECTING;
	}

	private synchronized void connected(Target target) {
		if (state == State.CONNECTING) {
			this.target = target;
			state = State.RELAYING;
			send(new Connected(Socks5CommandStatus.SUCCESS, bound(target.localAddress())));

			/* The session may have closed under the send, which ended the relay and closed the target */
			if (state == State.RELAYING) {
				target.setReadable(chainWritable);
			}
		} else {
			target.close();
		}
	}

	private synchronized void failed(Socks5CommandStatus status) {
		if (state == State.CONNECTING) {
			refuse(status);
		}
	}

	private void write(byte[] bytes) {
		target.write(bytes);

		if (target.backlogBytes() > (long) BACKLOG_ENTRIES * session.maxDataBytes()) {
			close(CloseReason.BROKEN);
		}
	}

	/* Cut to what one entry carries */
	private synchronized void received(byte[] bytes) {
		final int piece = session.maxDataBytes();

		for (int from = 0; state == State.RELAYING && from < bytes.length; from += piece) {
			send(new Data(Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + piece))));
		}
	}

	private synchronized void targetClosed() {
		if (state == State.RELAYING) {
			close(CloseReason.DONE);
		}
	}

	private void refuse(Socks5CommandStatus status) {
		send(new Connected(status, SocksAddress.UNSPECIFIED));
		close(REFUSALS.contains(status) ? CloseReason.REFUSED : CloseReason.UNREACHABLE);
	}

	private void send(SealedMessage message) {
		try {
			session.send(message);
		} catch (IllegalStateException closed) {
			/* The session closes itself, maybe on another thread, when an entry of the client fails to open */
			end();
		}
	}

	private void end() {
		if (target != null) {
			target.close();
			target = null;
		}

		state = State.CLOSED;
	}

	private static SocksAddress bound(InetSocketAddress local) {
		final Socks5AddressType type = local.getAddress() instanceof Inet4Address ? Socks5AddressType.IPv4
			: Socks5AddressType.IPv6;

		return new SocksAddress(type, NetUtil.toAddressString(local.getAddress()), local.getPort());
	}

	private final class Listener implements TargetListener {
		@Override
		public void connected(Target target) {
			ExitRelay.this.connected(target);
		}

		@Override
		public void failed(Socks5CommandStatus status) {
			ExitRelay.this.failed(status);
		}

		@Override
		public void received(byte[] bytes) {
			ExitRelay.this.received(bytes);
		}

		@Override
		public void closed() {
			targetClosed();
		}
	}
}
