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
package org.unigrid.hedgehog.model.gridnode;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.ECKey;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.jackson.internal.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import org.unigrid.hedgehog.model.Collateral;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.crypto.GridnodeKey;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.server.rest.JsonExceptionMapper;

@Slf4j
@Consumes({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
public class Heartbeat {

	private static final String HEARTBEAT_URL = "https://rest-testnet.unigrid.org/gridnode/all-delegations";

	private Client client;

	@SneakyThrows
	public void getHeartbeatData() {
		final ClientConfig clientConfig = new ClientConfig();

		clientConfig.register(JacksonJaxbJsonProvider.class);
		clientConfig.register(new JsonConfiguration());
		clientConfig.register(JsonExceptionMapper.class);

		final SSLContext context = SSLContext.getInstance("ssl");
		context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);

		client = ClientBuilder.newBuilder()
			.hostnameVerifier((hostname, session) -> true)
			.sslContext(context)
			.withConfig(clientConfig).build();

		HeartbeatData heartbeat = client.target(HEARTBEAT_URL).request().get(HeartbeatData.class);

		final Map<String, Double> map = heartbeat.getDelegations().stream()
			.collect(Collectors.toMap(Delegations::getPublicKey, Delegations::getDelegatedAmount));

		CDIUtil.resolveAndRun(Topology.class, topology -> {

			Collateral collateralCalculator = new Collateral();
			final Set<Gridnode> gridnodes = topology.cloneGridnode();
			int numNodes = gridnodes.size();
			log.atDebug().log("Heartbeat");
			log.atDebug().log("number of node on the network " + numNodes);

			if (numNodes == 0) {
				return;
			}

			map.entrySet().forEach(accountEntry -> {
				String key = accountEntry.getKey();
				Double val = accountEntry.getValue();
				double cost = collateralCalculator.get(gridnodes.size());
				log.atDebug().log("Account = " + key + " delegated amount = " + val);
				int i = (int) Math.round(val / cost);
				log.atDebug().log("Alowed to run " + i + " nodes");

				List<ECKey> keys = GridnodeKey.generateKeys(key, i);
				List<String> pubKeys = new ArrayList<>();
				keys.forEach(k -> {
					pubKeys.add(k.getPublicKeyAsHex());
				});

				gridnodes.removeIf(gridnode -> gridnode.getStatus() == Gridnode.Status.ACTIVE
					&& pubKeys.contains(gridnode.getId()));
			});

			gridnodes.forEach(gridnode -> gridnode.setStatus(Gridnode.Status.INACTIVE));
		});

	}

}
