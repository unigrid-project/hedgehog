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

import java.util.Objects;
import java.util.concurrent.Executor;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.DestinationPolicy;
import org.unigrid.hedgehog.proxy.session.Resolver;

/* What the exit relays of one node share: the spork's settings, the destination policy, the resolver and the
   executor it blocks on, and the connector. The policy is the only gate between a stranger's request and a
   connection, so production code passes DestinationPolicy.publicOnly() and nothing configures another. The executor
   runs a lookup on a thread of its own and rejects when full: one that runs it on the caller's thread would
   block the host under the relay's lock. */
public record ExitContext(ProxySettings settings, DestinationPolicy policy, Resolver resolver, Executor resolving,
	ExitConnector connector) {

	public ExitContext {
		Objects.requireNonNull(settings, "settings");
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(resolver, "resolver");
		Objects.requireNonNull(resolving, "resolving");
		Objects.requireNonNull(connector, "connector");
	}
}
