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

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.DestinationPolicy;
import org.unigrid.hedgehog.proxy.session.Resolver;

/* What the exit relays of one node share: the spork's settings, the destination policy, the resolver and the
   executor it blocks on, the connectors for streams and for datagrams, and a monotonic clock in milliseconds that
   ages an association's destinations, as a wall clock that steps back would stop forgetting them. The policy is the
   only gate between a stranger's request and a connection, so production code passes DestinationPolicy.publicOnly()
   and nothing configures another. The executor runs a lookup on a thread of its own and rejects when full: one that
   runs it on the caller's thread would block the host under the relay's lock. */
public record ExitContext(ProxySettings settings, DestinationPolicy policy, Resolver resolver, Executor resolving,
	ExitConnector connector, UdpConnector udp, LongSupplier clock) {

	public static final LongSupplier MONOTONIC_MILLIS = () -> System.nanoTime() / 1_000_000;

	public ExitContext {
		Objects.requireNonNull(settings, "settings");
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(resolver, "resolver");
		Objects.requireNonNull(resolving, "resolving");
		Objects.requireNonNull(connector, "connector");
		Objects.requireNonNull(udp, "udp");
		Objects.requireNonNull(clock, "clock");
	}

	/* A context of a node that relays streams and no datagrams, on the system's monotonic clock */
	public ExitContext(ProxySettings settings, DestinationPolicy policy, Resolver resolver, Executor resolving,
		ExitConnector connector) {

		this(settings, policy, resolver, resolving, connector, UdpConnector.unsupported(), MONOTONIC_MILLIS);
	}
}
