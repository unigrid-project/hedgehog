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

package org.unigrid.hedgehog.model.producer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.security.SecureRandom;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.proxy.socks.Tunnel;
import org.unigrid.hedgehog.service.chain.ChainClient;
import org.unigrid.hedgehog.service.chain.ChainHost;
import org.unigrid.hedgehog.service.chain.ChainTransport;
import org.unigrid.hedgehog.service.chain.ChainTunnel;
import org.unigrid.hedgehog.service.chain.ClientSessions;
import org.unigrid.hedgehog.service.chain.ProxySettingsSource;

@ApplicationScoped
public class TunnelProducer {
	/* Streams run over client sessions on the shard group chains. The tunnel reads the communication spork for every
	   stream, so it needs no restart when the spork arrives, and refuses every stream as not allowed by ruleset until
	   then. */
	@Produces @Singleton
	public Tunnel tunnel(final ChainHost host, final ChainTransport transport, final GridnodeDirectory directory,
		final ProxySettingsSource settings, final PendingRequests pending) {

		final SecureRandom random = new SecureRandom();
		final ScheduledThreadPoolExecutor scheduler = scheduler();
		final ChainClient client = new ChainClient(settings, directory, transport, host, pending,
			System::currentTimeMillis, random);

		return new ChainTunnel(settings, new ClientSessions(client, host, settings, System::currentTimeMillis,
			random, scheduler), scheduler);
	}

	/* One thread for the tick of the sessions and the deadlines of the streams and associations. It is a daemon
	   that starts with the first session, so a node needs no disposer to end, and a session that ends takes its
	   deadline out of the queue rather than staying reachable until the deadline would have run. */
	static ScheduledThreadPoolExecutor scheduler() {
		final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, body -> {
			final Thread thread = new Thread(body, "chain-client");

			thread.setDaemon(true);
			return thread;
		});

		scheduler.setRemoveOnCancelPolicy(true);
		return scheduler;
	}
}
