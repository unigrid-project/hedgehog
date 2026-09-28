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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
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
import org.unigrid.hedgehog.proxy.session.Datagram;
import org.unigrid.hedgehog.proxy.session.DestinationRefusedException;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.Session;
import org.unigrid.hedgehog.proxy.session.SocksAddress;

/* The exit's end of one session. The host hands it every message the session opens, and the Close the session
   produced, through handle; it calls tick on its schedule and close when it drops the session. On the client's
   Connect the relay checks the spork's flags, admits the destination through the policy, connects to exactly
   the admitted addresses and answers Connected; then it relays the stream until either side closes. A UDP
   association is relayed the same way, over a datagram socket and through DatagramRelay. */
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
	private final Logger log;
	private State state = State.WAITING;
	private ExitConnector.Attempt attempt = ExitConnector.Attempt.NONE;
	private Target target;
	private DatagramRelay datagrams;
	private boolean chainWritable = true;

	private enum State {
		/* Before the client's Connect */
		WAITING,
		/* Admitting the destination and connecting to it */
		CONNECTING,
		/* Opening the datagram socket of an association */
		OPENING,
		RELAYING,
		/* Relaying the datagrams of an association */
		ASSOCIATED,
		CLOSED
	}

	public ExitRelay(Session session, ExitContext context) {
		this(session, context, LOG);
	}

	ExitRelay(Session session, ExitContext context, Logger log) {
		this.session = Objects.requireNonNull(session, "session");
		this.context = Objects.requireNonNull(context, "context");
		this.log = log;
	}

	public synchronized boolean isOpen() {
		return state != State.CLOSED;
	}

	/* How many datagrams of the association were dropped: those its destinations refused or could not hold, those
	   the socket did not take, and replies from strangers or too large for an entry */
	public synchronized long droppedDatagrams() {
		return datagrams == null ? 0 : datagrams.dropped();
	}

	/* The socket of an association is opened once the lock is released, as a connector may answer at once and on
	   a thread of its own */
	public void handle(SealedMessage message) {
		if (take(message) == State.OPENING) {
			open();
		}
	}

	private synchronized State take(SealedMessage message) {
		if (message instanceof Close) {
			end();
		} else if (!(takeStream(message) || takeDatagram(message))) {
			/* A session carries one Connect, then Data or Datagrams once the exit replied, and nothing else */
			close(CloseReason.BROKEN);
		}

		return state;
	}

	private boolean takeStream(SealedMessage message) {
		if (state == State.WAITING && message instanceof Connect connect) {
			start(connect);
			return true;
		}

		if (state == State.RELAYING && message instanceof Data data) {
			write(data.bytes());
			return true;
		}

		return false;
	}

	private boolean takeDatagram(SealedMessage message) {
		if (state == State.ASSOCIATED && message instanceof Datagram datagram) {
			datagrams.forward(datagram);
			return true;
		}

		return false;
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
		} else if (state == State.ASSOCIATED) {
			datagrams.setReadable(writable);
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
		if (Socks5CommandType.UDP_ASSOCIATE.equals(connect.command())) {
			associate();
		} else if (!Socks5CommandType.CONNECT.equals(connect.command())) {
			/* BIND is not relayed */
			refuse(Socks5CommandStatus.COMMAND_UNSUPPORTED);
		} else if (!context.settings().connectAllowed()) {
			refuse(Socks5CommandStatus.FORBIDDEN);
		} else {
			state = State.CONNECTING;
			resolve(connect.destination());
		}
	}

	/* The spork's flag is read at the Connect, as CONNECT's is. Opening a socket never waits, so it does not queue
	   behind the lookups of the resolving executor, which a stranger's slow names may fill. */
	private void associate() {
		if (context.settings().udpAllowed()) {
			state = State.OPENING;
		} else {
			refuse(Socks5CommandStatus.FORBIDDEN);
		}
	}

	/* Runs with the lock released. A connector that throws would otherwise leave the client waiting for a reply
	   that never comes; the message is left out as it may quote an address. */
	private void open() {
		try {
			context.udp().open(new DatagramEvents());
		} catch (RuntimeException ex) {
			log.warn("Opening a datagram socket failed with {}", ex.getClass().getName());
			failed(Socks5CommandStatus.FAILURE);
		}
	}

	/* An executor that is full or shut down refuses the lookup, and the client must still get its reply */
	private void resolve(SocksAddress destination) {
		try {
			context.resolving().execute(() -> admit(destination));
		} catch (RejectedExecutionException ex) {
			log.warn("Admitting a destination failed with {}", ex.getClass().getName());
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
			final List<InetAddress> admitted = context.policy().admit(destination, context.resolver());

			/* A client that left during the lookup costs no connection */
			if (isConnecting()) {
				attempting(context.connector().connect(admitted, destination.port(), new Listener()));
			}
		} catch (DestinationRefusedException ex) {
			failed(ex.status());
		} catch (RuntimeException ex) {
			/* A resolver that answers null or throws, or a connector that refuses the work, would otherwise
			   leave the relay waiting for a reply that never comes. The message is left out as it may quote
			   the name. */
			log.warn("Admitting a destination failed with {}", ex.getClass().getName());
			failed(Socks5CommandStatus.FAILURE);
		}
	}

	/* Short, so the lookup that follows it is not done under the lock; a client that has gone costs no lookup */
	private synchronized boolean isConnecting() {
		return state == State.CONNECTING;
	}

	/* A relay that ended while the connector started had nothing to cancel then */
	private synchronized void attempting(ExitConnector.Attempt started) {
		if (state == State.CONNECTING) {
			attempt = started;
		} else {
			started.cancel();
		}
	}

	private synchronized void connected(Target target) {
		if (state == State.CONNECTING) {
			this.target = target;
			state = State.RELAYING;
			send(new Connected(Socks5CommandStatus.SUCCESS, addressOf(target.localAddress())));

			/* The session may have closed under the send, which ended the relay and closed the target */
			if (state == State.RELAYING) {
				target.setReadable(chainWritable);
			}
		} else {
			target.close();
		}
	}

	private synchronized void failed(Socks5CommandStatus status) {
		if (state == State.CONNECTING || state == State.OPENING) {
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

	/* The exit's address stays with the exit: the client's own proxy answers its client with its local socket */
	private synchronized void associated(UdpConnector.Socket socket) {
		if (state == State.OPENING) {
			datagrams = new DatagramRelay(session, context, socket, log);
			state = State.ASSOCIATED;
			send(new Connected(Socks5CommandStatus.SUCCESS, SocksAddress.UNSPECIFIED));

			/* The session may have closed under the send, which ended the relay and closed the socket */
			if (state == State.ASSOCIATED) {
				datagrams.setReadable(chainWritable);
			}
		} else {
			socket.close();
		}
	}

	private synchronized void fromRemote(InetSocketAddress source, byte[] bytes) {
		if (state == State.ASSOCIATED) {
			datagrams.reply(source, bytes).ifPresent(this::send);
		}
	}

	private synchronized void socketClosed() {
		if (state == State.ASSOCIATED) {
			close(CloseReason.DONE);
		}
	}

	/* Asked on the connector's thread before it copies a datagram, which anyone can send to the socket */
	private synchronized boolean accepted(InetSocketAddress source) {
		return state == State.ASSOCIATED && datagrams.accepts(source);
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

	/* A connect still under way would otherwise hold its socket, and dial the addresses left, with no relay to own
	   them */
	private void end() {
		attempt.cancel();
		attempt = ExitConnector.Attempt.NONE;

		if (target != null) {
			target.close();
			target = null;
		}

		/* The socket goes once, and the association's count stays */
		if (datagrams != null && state != State.CLOSED) {
			datagrams.close();
		}

		state = State.CLOSED;
	}

	/* A socket address in its SOCKS5 form, as an IP literal */
	static SocksAddress addressOf(InetSocketAddress socket) {
		final Socks5AddressType type = socket.getAddress() instanceof Inet4Address ? Socks5AddressType.IPv4
			: Socks5AddressType.IPv6;

		return new SocksAddress(type, NetUtil.toAddressString(socket.getAddress()), socket.getPort());
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

	private final class DatagramEvents implements UdpConnector.Listener {
		@Override
		public void opened(UdpConnector.Socket socket) {
			associated(socket);
		}

		@Override
		public void failed(Socks5CommandStatus status) {
			ExitRelay.this.failed(status);
		}

		@Override
		public boolean accepts(InetSocketAddress source) {
			return accepted(source);
		}

		@Override
		public void received(InetSocketAddress source, byte[] bytes) {
			fromRemote(source, bytes);
		}

		@Override
		public void closed() {
			socketClosed();
		}
	}
}
