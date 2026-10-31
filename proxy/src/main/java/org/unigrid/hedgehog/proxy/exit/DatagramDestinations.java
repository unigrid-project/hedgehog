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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.unigrid.hedgehog.proxy.session.DestinationPolicy;
import org.unigrid.hedgehog.proxy.session.DestinationRefusedException;
import org.unigrid.hedgehog.proxy.session.Resolver;
import org.unigrid.hedgehog.proxy.session.SocksAddress;

/* Where the datagrams of one association may go. Every destination is admitted by the destination policy before
   anything is sent to it, a name is looked up once and the datagrams go to the address that was checked, never to
   the name, and replies are taken only from where the association sent. What cannot be sent is dropped and
   counted, and so is what the socket cannot reach. */
final class DatagramDestinations {
	/* Destinations one association keeps at a time */
	static final int MAX_DESTINATIONS = 64;

	/* Datagrams held for a name while it is looked up */
	static final int MAX_WAITING = 8;

	/* Names one association has in lookup at once. Each lookup keeps a thread of the exit busy while it blocks,
	   and a stranger chooses the names and how slowly they are answered. */
	static final int MAX_LOOKUPS = 4;

	/* A destination nothing was sent to for this long is forgotten, which is also when its replies stop being
	   taken and when a name is looked up and checked afresh */
	static final long IDLE_MILLIS = 60_000;

	@FunctionalInterface
	interface Sink {
		void send(InetAddress address, int port, byte[] bytes);
	}

	private static final class Destination {
		/* The checked address; null while the name is looked up, and for a destination that was refused */
		private InetAddress target;
		/* Held until the lookup is over; null afterwards */
		private List<byte[]> waiting = new ArrayList<>();
		private long lastSent;
	}

	private final DestinationPolicy policy;
	private final Resolver resolver;
	private final Executor resolving;
	private final LongSupplier clock;
	private final Sink sink;
	private final Predicate<InetAddress> reachable;
	private final Logger log;
	private final Map<SocksAddress, Destination> destinations = new HashMap<>();
	private int lookups;
	private long dropped;

	DatagramDestinations(DestinationPolicy policy, Resolver resolver, Executor resolving, LongSupplier clock,
		Sink sink, Predicate<InetAddress> reachable, Logger log) {

		this.policy = policy;
		this.resolver = resolver;
		this.resolving = resolving;
		this.clock = clock;
		this.sink = sink;
		this.reachable = reachable;
		this.log = log;
	}

	synchronized long dropped() {
		return dropped;
	}

	/* Sends the bytes to the checked address of the destination, holds them while its name is looked up, or
	   drops them */
	synchronized void send(SocksAddress destination, byte[] bytes) {
		final long now = clock.getAsLong();

		forgetIdle(now);

		Destination entry = destinations.get(destination);

		if (entry == null) {
			entry = introduce(destination);
		}

		if (entry == null || !route(entry, destination.port(), bytes, now)) {
			dropped++;
		}
	}

	/* A reply counts when it comes from an address and port the association sent to, and from the address that
	   was chosen when a name has several */
	synchronized boolean allowsReplyFrom(InetSocketAddress source) {
		forgetIdle(clock.getAsLong());

		return destinations.entrySet().stream().anyMatch(entry -> entry.getKey().port() == source.getPort()
			&& source.getAddress().equals(entry.getValue().target));
	}

	private void forgetIdle(long now) {
		destinations.values().removeIf(entry -> now - entry.lastSent >= IDLE_MILLIS);
	}

	/* An IP literal is checked on the spot, a name is looked up on the executor, away from the lock */
	private Destination introduce(SocksAddress destination) {
		final boolean name = Socks5AddressType.DOMAIN.equals(destination.type());

		if (destination.port() == 0 || isFull(name)) {
			return null;
		}

		final Destination entry = new Destination();

		destinations.put(destination, entry);

		if (!name) {
			settle(destination, entry, check(destination));
		} else if (!lookUp(destination, entry)) {
			destinations.remove(destination);
			return null;
		}

		return entry;
	}

	/* An executor that turns the lookup away must not leave a slot taken or a destination that never settles */
	private boolean lookUp(SocksAddress destination, Destination entry) {
		lookups++;

		try {
			resolving.execute(() -> settle(destination, entry, check(destination)));
			return true;
		} catch (RejectedExecutionException busy) {
			lookups--;
			return false;
		}
	}

	private boolean isFull(boolean name) {
		return destinations.size() >= MAX_DESTINATIONS || name && lookups >= MAX_LOOKUPS;
	}

	private Optional<InetAddress> check(SocksAddress destination) {
		try {
			return policy.admit(destination, resolver).stream().filter(reachable).findFirst();
		} catch (DestinationRefusedException ex) {
			return Optional.empty();
		} catch (RuntimeException ex) {
			log.warn("Checking a datagram destination failed with {}", ex.getClass().getName());
			return Optional.empty();
		}
	}

	private synchronized void settle(SocksAddress destination, Destination entry, Optional<InetAddress> target) {
		final List<byte[]> held = entry.waiting;

		if (Socks5AddressType.DOMAIN.equals(destination.type())) {
			lookups--;
		}

		entry.target = target.orElse(null);
		entry.waiting = null;

		for (byte[] bytes : held) {
			if (destinations.get(destination) != entry || !deliver(entry, destination.port(), bytes)) {
				dropped++;
			}
		}
	}

	private boolean route(Destination entry, int port, byte[] bytes, long now) {
		entry.lastSent = now;

		if (entry.waiting != null) {
			return entry.waiting.size() < MAX_WAITING && entry.waiting.add(bytes);
		}

		return deliver(entry, port, bytes);
	}

	private boolean deliver(Destination entry, int port, byte[] bytes) {
		if (entry.target == null) {
			return false;
		}

		sink.send(entry.target, port, bytes);
		return true;
	}
}
