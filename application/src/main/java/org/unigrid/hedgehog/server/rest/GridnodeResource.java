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

package org.unigrid.hedgehog.server.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.SignatureDecodeException;
import org.unigrid.hedgehog.model.CollateralCalculation;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.crypto.GridnodeKey;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.network.Gridnode;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.util.Utils;
import org.unigrid.hedgehog.server.p2p.P2PServer;

@Slf4j
@Path("/gridnode")
@Produces(MediaType.APPLICATION_JSON)
@Consumes({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
public class GridnodeResource extends CDIBridgeResource {

	@CDIBridgeInject
	private P2PServer p2pServer;

	@CDIBridgeInject
	private Topology topology;

	@CDIBridgeInject
	private SporkDatabase sporkDatabase;

	//@Inject
	//GridnodeHash gridnodeHash;
	@Path("/colleteral")
	@GET
	public Response get() {
		// get number of gridnodes and calculate colleteral

		final Set<Node> nodes = topology.cloneNodes();

		List<Node> nodeList = nodes.stream().filter(n -> n.getGridnode().get().getGridnodeKey() != ""
			&& n.getGridnode().get().getGridnodeStatus() == Node.GridnodeStatus.ACTIVE).toList();
		CollateralCalculation colleteral = new CollateralCalculation();

		return Response.ok(colleteral.getCollateral(nodeList.size())).build();
	}

	/**
	 * *
	 * Expects base64 encoded Base64.getEncoder().encodeToString(singature.encodeToDER()) ECKey.ECDSASignature as a
	 * header param.
	 *
	 * Gridnode object Gridnode key to activate Hex represenation of the public key
	 *
	 * @param gridnode
	 * @param sign
	 * @return status 202 if sucssesfull
	 */
	@Path("/start")
	@PUT
	public Response put(@NotNull Gridnode gridnode,
		@NotNull @HeaderParam("sign") String sign) {
		System.out.println(gridnode.getGridnodeKey() + " || " + gridnode.getMessage() + " || " + sign);
		try {
			byte[] bytes = Utils.hexStringToByteArray(gridnode.getGridnodeKey());
			ECKey pubKey = ECKey.fromPublicOnly(bytes);
			System.out.println(pubKey.toString());
			byte[] messageBytes = gridnode.getMessage().getBytes();
			byte[] signBytes = Base64.getDecoder().decode(sign);
			if (GridnodeKey.verifySignature(messageBytes, signBytes, pubKey)) {
				final Set<Node> nodes = topology.cloneNodes();
				System.out.println(nodes.size());
				nodes.stream().forEach(n -> {
					if (n.getGridnode().isPresent()
						&& n.getGridnode().get().getGridnodeKey()
							.equals(gridnode.getMessage())) {
						n.getGridnode().get()
							.setGridnodeStatus(Node.GridnodeStatus.ACTIVE);
						//TODO: add package message to the network with the new status

					}
				});
				return Response.status(Response.Status.ACCEPTED).build();
			}
		} catch (SignatureDecodeException ex) {
			log.error(ex.getMessage());
			return Response.status(Response.Status.UNAUTHORIZED).build();
		} catch (Exception e) {
			log.error(e.getMessage());
			return Response.status(Response.Status.EXPECTATION_FAILED).build();
		}

		System.out.println("was not abel to verify key!!!!");
		return Response.status(Response.Status.UNAUTHORIZED).build();
	}

	@Path("heartbeat")
	@PUT
	public Response heartbeat(@NotNull String stringMap) {
		Map<String, Double> map = new HashMap<>();

		try {
			ObjectMapper objectMapper = new ObjectMapper();
			// Parse JSON into a List of Maps
			List<Map<String, Object>> dataList = objectMapper.readValue(stringMap,
				new TypeReference<List<Map<String, Object>>>() {
				});

			// Create a map from the parsed data
			for (Map<String, Object> data : dataList) {
				String account = (String) data.get("account");
				Double delegatedAmount = (Double) data.get("delegated_amount");
				map.put(account, delegatedAmount);
			}
		} catch (Exception e) {
			System.out.println(e.getMessage());
			log.error(e.getMessage());
			return Response.status(Response.Status.EXPECTATION_FAILED).build();
		}

		CollateralCalculation collateralCalculator = new CollateralCalculation();
		final Set<Node> nodes = topology.cloneNodes();

		map.entrySet().forEach(accountEntry -> {
			String key = accountEntry.getKey();
			Double val = accountEntry.getValue();
			//System.out.println(key);
			double cost = collateralCalculator.getCollateral(nodes.size());
			//System.out.println(key);
			//System.out.println(val);
			//System.out.println(cost);
			System.out.println((int) Math.round(val / cost));
			List<ECKey> keys = GridnodeKey.generateKeys(key, (int) Math.round(val / cost));
			List<String> pubKeys = new ArrayList<>();
			keys.forEach(k -> {
				pubKeys.add(k.getPublicKeyAsHex());
			});

			System.out.println("Nodes size = " + nodes.size());

			nodes.removeIf(node -> node.getGridnode().get().getGridnodeKey().isEmpty()
				|| node.getGridnode().get().getGridnodeStatus() == Node.GridnodeStatus.ACTIVE
				&& pubKeys.contains(node.getGridnode().get().getGridnodeKey()));
		});
		System.out.println("Nodes size = " + nodes.size());

		nodes.forEach(node -> node.getGridnode().get().setGridnodeStatus(Node.GridnodeStatus.INACTIVE));

		return Response.status(Response.Status.OK).build();
	}

	/*@Path("heartbeat/hash")
	@GET
	public Response getHash() {
		return Response.ok(gridnodeHash.getHash()).build();
	}

	@Path("heartbeat/hash")
	@PUT
	public Response updateHash(@NotNull String hash) {
		gridnodeHash.setHash(hash);
		return Response.status(Response.Status.OK).build();
	}*/
}
