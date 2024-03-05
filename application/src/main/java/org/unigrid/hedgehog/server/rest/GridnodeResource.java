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
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.SignatureDecodeException;
import org.unigrid.hedgehog.model.Collateral;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.crypto.GridnodeKey;
import org.unigrid.hedgehog.model.network.Topology;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.gridnode.HeartbeatData;
import org.unigrid.hedgehog.model.network.ActivateGridnode;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
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

	@GET @Path("/collateral")
	public Response get() {
		// get number of gridnodes and calculate colleteral
		Set<Gridnode> gridnodes = topology.cloneGridnode();
		int count = 0;

		for (Gridnode g : gridnodes) {
			if (g.getStatus() == Gridnode.Status.ACTIVE) {
				count++;
			}
		}

		final Collateral collateral = new Collateral();
		return Response.ok(collateral.get(count)).build();
	}

	@GET
	public Response list() {
		Set<Gridnode> gridnodes = topology.cloneGridnode();

		return Response.ok(gridnodes.toString()).build();
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
				final Set<Gridnode> gridnodes = topology.cloneGridnode();
				gridnodes.forEach(g -> {
					if (g.getId().equals(gridnode.getGridnodeId())) {
						log.atTrace().log("Activating node with id {}", g.getId());
						topology.modifyGridnode(g, n -> {
							n.setStatus(Gridnode.Status.ACTIVE);
						});
						Topology.sendAll(PublishGridnode.builder().gridnode(g).build(),
							topology, Optional.empty());
					}
				});
				return Response.status(Response.Status.ACCEPTED).build();
			}
		} catch (SignatureDecodeException ex) {
			log.error(ex.getMessage());
			return Response.status(Response.Status.UNAUTHORIZED).build();
		} catch (Exception e) {
			System.out.println(e.getMessage());
			log.error(e.getMessage());
			return Response.status(Response.Status.EXPECTATION_FAILED).build();
		}

		return Response.status(Response.Status.UNAUTHORIZED).build();
	}

	@PUT @Path("heartbeat")
	public Response heartbeat(@NotNull HeartbeatData data) {
		return Response.status(Response.Status.NOT_IMPLEMENTED).build();
		/*try {
			final ValidatorSpork vs = ResourceHelper.getNewOrClonedSporkSection(
				() -> sporkDatabase.getValidatorSpork(),
				() -> new ValidatorSpork()
			);
			ValidatorSpork.SporkData sporkData = vs.getData();
			boolean verified = false;
			for (String s : sporkData.getValidatorKeys()) {
				byte[] bytes = Hex.decodeHex(s);
				ECKey pubKey = ECKey.fromPublicOnly(bytes);
				byte[] messageBytes = data.getMessage().getBytes();
				byte[] signBytes = Base64.getDecoder().decode(data.getSign());
				if (GridnodeKey.verifySignature(messageBytes, signBytes, pubKey)) {
					verified = true;
				}
			}
			if(!verified) {
				return Response.status(Response.Status.UNAUTHORIZED).build();
			}
		}  catch (SignatureDecodeException ex) {
			System.out.println("SignatureDecodeException");
			System.out.println(ex.getMessage());
			log.error(ex.getMessage());
			return Response.status(Response.Status.UNAUTHORIZED).build();
		} catch (Exception e) {
			System.out.println("Generic exception");
			System.out.println(e.getMessage());
			log.error(e.getMessage());
			return Response.status(Response.Status.EXPECTATION_FAILED).build();
		}
		final Map<String, Double> map = data.getDelagations().stream()
			.collect(Collectors.toMap(Delegation::getAccount, Delegation::getDelegatedAmount));

		Collateral collateralCalculator = new Collateral();
		final Set<Gridnode> gridnodes = topology.cloneGridnode();
		int numNodes = gridnodes.size();
		log.atDebug().log("Heartbeat");
		log.atDebug().log("number of node on the network " + numNodes);

		if (numNodes == 0) {
			return Response.status(Response.Status.NO_CONTENT).build();
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
		return Response.status(Response.Status.OK).build();*/
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
