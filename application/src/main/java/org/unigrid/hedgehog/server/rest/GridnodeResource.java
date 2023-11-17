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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.SignatureDecodeException;
import org.unigrid.hedgehog.model.Collateral;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.crypto.GridnodeKey;
import org.unigrid.hedgehog.model.network.Topology;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.unigrid.hedgehog.model.gridnode.Delegation;
import org.unigrid.hedgehog.model.network.ActivateGridnode;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
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

	@GET @Path("/collateral")
	public Response get() {
		// get number of gridnodes and calculate colleteral
		Set<Node> nodes = topology.cloneNodes();

		nodes = nodes.stream().filter(n -> n.getGridnode().isPresent()
			&& n.getGridnode().get().getStatus() == Node.Gridnode.Status.ACTIVE)
			.collect(Collectors.toSet());

		final Collateral collateral = new Collateral();
		return Response.ok(collateral.get(nodes.size())).build();
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
	public Response put(@NotNull ActivateGridnode gridnode,
		@NotNull @HeaderParam("sign") String sign) {
		try {
			byte[] bytes = Hex.decodeHex(gridnode.getPublicKey());
			ECKey pubKey = ECKey.fromPublicOnly(bytes);
			byte[] messageBytes = gridnode.getGridnodeId().getBytes();
			byte[] signBytes = Base64.getDecoder().decode(sign);
			if (GridnodeKey.verifySignature(messageBytes, signBytes, pubKey)) {
				final Set<Node> nodes = topology.cloneNodes();
				nodes.stream().forEach(n -> {
					if (n.getGridnode().isPresent() && n.getGridnode().get().getId()
						.equals(gridnode.getGridnodeId())) {
							n.getGridnode().get().setStatus(Node.Gridnode.Status.ACTIVE);
							//TODO: Adam review
							Topology.sendAll(PublishGridnode.builder()
								.node(n).build(), topology, Optional.empty());
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

		return Response.status(Response.Status.UNAUTHORIZED).build();
	}

	@PUT @Path("heartbeat")
	public Response heartbeat(@NotNull List<Delegation> accounts) {
		final Map<String, Double> map = accounts.stream()
			.collect(Collectors.toMap(Delegation::getAccount, Delegation::getDelegatedAmount));

		Collateral collateralCalculator = new Collateral();
		final Set<Node> nodes = topology.cloneNodes();
		int numNodes = nodes.size();
		log.atDebug().log("Heartbeat");
		log.atDebug().log("number of node on the network " + numNodes);

		if (numNodes == 0) {
			return Response.status(Response.Status.NO_CONTENT).build();
		}

		map.entrySet().forEach(accountEntry -> {
			String key = accountEntry.getKey();
			Double val = accountEntry.getValue();
			double cost = collateralCalculator.get(nodes.size());
			log.atDebug().log("Account = " + key + " delegated amount = " + val);
			int i = (int) Math.round(val / cost);
			log.atDebug().log("Alowed to run " + i + " nodes");


			List<ECKey> keys = GridnodeKey.generateKeys(key, i);
			List<String> pubKeys = new ArrayList<>();
			keys.forEach(k -> {
				pubKeys.add(k.getPublicKeyAsHex());
			});


			nodes.removeIf(node -> node.getGridnode().isEmpty()
				|| node.getGridnode().get().getStatus() == Node.Gridnode.Status.ACTIVE
				&& pubKeys.contains(node.getGridnode().get().getId()));
		});

		nodes.forEach(node -> node.getGridnode().get().setStatus(Node.Gridnode.Status.INACTIVE));
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
