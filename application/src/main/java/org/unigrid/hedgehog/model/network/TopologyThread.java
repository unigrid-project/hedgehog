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

import io.netty.channel.ConnectTimeoutException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.cdi.CDIUtil;

@Slf4j
public class TopologyThread extends Thread {
	public static final int SECONDS_BETWEEN_RECONNECTS = 30;

	private Object lock = new Object();
	private boolean run = true;

	@RequiredArgsConstructor
	public class NodeConnectionHandler implements Consumer<Node> {
		private final Topology topology;

		@Override
		public void accept(Node node) {
			log.atTrace().log("Handling connection {}", node);

			try {
				if (node.getConnection().isPresent()) {
					final P2PClient client = new P2PClient(node.getAddress().getHostName(),
						node.getAddress().getPort()
					);

					topology.modifyNode(node, n -> {
						n.setConnection(Optional.of(client));
					});
				}
			} catch (ExecutionException | InterruptedException | CertificateException
				| NoSuchAlgorithmException ex) {
				log.atWarn().log("Node connection to {} failed", node, ex);

				if (ex.getCause().getClass().isAssignableFrom(ConnectTimeoutException.class)) {
					topology.removeNode(node);
					log.atTrace().log("Removed node {} from topology", node);
				}
			}
		}
	}

	@Override
	public void run() {
		CDIUtil.resolveAndRun(Topology.class, topology -> {
			while (run) {
				/* If we have no connections, we try the seed nodes again! */
				if (topology.isEmpty()) {
					topology.repopulate();
				}

				final Set<Node> nodes = topology.cloneNodes();
				nodes.forEach(new NodeConnectionHandler(topology));

				synchronized (lock) {
					try {
						lock.wait(SECONDS_BETWEEN_RECONNECTS * 1000);
					} catch (InterruptedException ex) {
						return; /* At this point we know we are done and can just bail out */
					}
				}
			}
		});
	}

	public void exit() {
		synchronized (lock) {
			run = false;
			lock.notify();
		}
	}
}
