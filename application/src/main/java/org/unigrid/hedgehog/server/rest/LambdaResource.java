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

package org.unigrid.hedgehog.server.rest;

import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.lambda.LambdaDeploy;
import org.unigrid.hedgehog.model.lambda.entity.AddLayerVersionPermisson;
import org.unigrid.hedgehog.model.lambda.entity.AddPermission;
import org.unigrid.hedgehog.model.lambda.entity.CreateAlias;
import org.unigrid.hedgehog.model.lambda.entity.CreateCodeSigningConfig;
import org.unigrid.hedgehog.model.lambda.entity.CreateFunction;
import org.unigrid.hedgehog.model.lambda.entity.CreateFunctionUrlConfig;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.server.p2p.P2PServer;

@Slf4j
@Path("/gridspork")
@Produces(MediaType.APPLICATION_JSON)
@Consumes({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
public class LambdaResource extends CDIBridgeResource {

	@CDIBridgeInject
	private P2PServer p2pServer;

	@CDIBridgeInject
	private Topology topology;

	@PUT
	@Path("lambda/deploy")
	public Response deploy(@NotNull LambdaDeploy lambdaDeploy) {
		return Response.status(Response.Status.NOT_IMPLEMENTED).build();
	}

	@POST
	@Path("lambda/addLayerVersionPermission")
	public Response addLayerVersionPermission(@NotNull AddLayerVersionPermisson addLayerVersionPermisson) {
		return Response.status(Response.Status.NOT_IMPLEMENTED).build();
	}

	@POST
	@Path("lambda/addPermisson")
	public Response addPermission(@NotNull AddPermission addPermission) {
		return Response.status(Response.Status.NOT_IMPLEMENTED).build();
	}
	
	@POST
	@Path("lambda/createAlias")
	public Response createAlias(@NotNull CreateAlias createAlias) {
		return Response.status(Response.Status.NOT_IMPLEMENTED).build();
	}

	@POST
	@Path("lambda/createCodeSigningConfig")
	public Response createCodeSigningConfig(@NotNull CreateCodeSigningConfig createCodeSignConfig) {
		return Response.status(Response.Status.NOT_IMPLEMENTED).build();
	}

	@POST
	@Path("lambda/createFunction") 
	public Response createFunction(@NotNull CreateFunction createFunction) {
		return Response.status(Response.Status.NOT_IMPLEMENTED).build();
	}

	@POST
	@Path("lambda/createFuntionUrlConfig")
	public Response createFuntionUrlConfig(@NotNull CreateFunctionUrlConfig createFuntionUrlConfig) {
		return Response.status(Response.Status.NOT_IMPLEMENTED).build();
	}
}
