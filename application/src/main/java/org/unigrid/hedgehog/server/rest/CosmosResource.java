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

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SerializationUtils;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.spork.Cosmos;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.VestingStorage;
import org.unigrid.hedgehog.server.p2p.P2PServer;

@Slf4j
@Path("/gridspork")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CosmosResource extends CDIBridgeResource {
	@CDIBridgeInject
	private P2PServer p2pServer;

	@CDIBridgeInject
	private SporkDatabase sporkDatabase;

	@Path("/cosmos") @GET
	public Response list() {
		System.out.println("Cosmos get 1");
		final Cosmos c = sporkDatabase.getCosmos();

		if (Objects.isNull(c)) {
			return Response.noContent().build();
		}

		return Response.ok().entity(c).build();
	}

	@Path("/cosmos/{parameter}") @GET
	public Response get(@PathParam("parameter") String parameter) {
		final Cosmos c = sporkDatabase.getCosmos();

		if (Objects.isNull(c)) {
			return Response.noContent().build();
		}

		System.out.println("Cosmos get params");

		final Cosmos.SporkData data = c.getData();
		final Object cosmosParams = data.getParameters().get(parameter);

		if (Objects.isNull(cosmosParams)) {
			return Response.status(Response.Status.NOT_FOUND).build();
		}

		return Response.ok().entity(cosmosParams).build();
	}

	@Path("/cosmos/{parameter}") @PUT
	public Response grow(@PathParam("parameter") String parameter, Object obj, @HeaderParam("privateKey") String privateKey) {

		if (Objects.nonNull(privateKey) && NetworkKey.isTrusted(privateKey)) {
			Cosmos c = sporkDatabase.getCosmos();
			System.out.println("Cosmos");
			System.out.println(c);
			if (Objects.isNull(c)) {
				c = new Cosmos();
			} else {
				c = SerializationUtils.clone(c);
			}

			final Cosmos.SporkData data = c.getData();
			System.out.println("Cosmos data");
			System.out.println(data);
			boolean isUpdate = false;
			System.out.println("param " + parameter);
			final Object oldCosmos = data.getParameters().get(parameter);
			
			c.archive();
			isUpdate = Objects.nonNull(oldCosmos);
			data.getParameters().put(parameter, obj);			
			
			try {
				c.sign(privateKey);
			} catch (SigningException ex) {
				// As we clone() the cosmos, returning here results in a database NOP
				System.out.println("EXCCCC");
				return Response.status(Response.Status.UNAUTHORIZED).entity(ex).build();
			}
			System.out.println("Cosmos");
			System.out.println(c);
			System.out.println("isUpdate");
			System.out.println(isUpdate);

			sporkDatabase.setCosmos(c);

			System.out.println("1");
			if (isUpdate) {
				return Response.noContent().build();
			}
			System.out.println("2");
			return Response.ok().build();
		}

		return Response.status(Response.Status.UNAUTHORIZED).build();
	}
}
