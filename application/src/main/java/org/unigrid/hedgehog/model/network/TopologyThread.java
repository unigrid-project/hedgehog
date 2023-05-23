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

package org.unigrid.hedgehog.model.network;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.client.P2PClient;

@Slf4j
public class TopologyThread extends Thread {
	public static final int SECONDS_BETWEEN_RECONNECTS = 30;
	private Object lock = new Object();
	private boolean run = true;

	@Override
	public void run() {
		final Instance<Topology> topology = CDI.current().select(Topology.class);
		final Map<InetSocketAddress, P2PClient> connections = new HashMap<>();

		while (run) {
			if (topology.isResolvable()) {
				final Set<Node> nodes = topology.get().cloneNodes();

				nodes.forEach(n -> {
					try {
						if (!connections.containsKey(n.getAddress())) {
							connections.put(n.getAddress(),
								new P2PClient(n.getAddress().getHostName(),
									n.getAddress().getPort()
								)
							);
						}
					} catch (ExecutionException | InterruptedException | CertificateException
						| NoSuchAlgorithmException ex) {
						log.atError().log("Node connection to {} failed: {}", n, ex);
					}
				});
			} else {
				log.atWarn().log("Unable to resolve Topology instance");
			}

			synchronized (lock) {
				try {
					lock.wait(SECONDS_BETWEEN_RECONNECTS * 1000);
				} catch (InterruptedException ex) {
					return; /* At this point we know we are done and can just bail out */
				}
			}
		}
	}

	public void exit() {
		synchronized (lock) {
			run = false;
			lock.notify();
		}
	}
}
