/*
    Unigrid Hedgehog 
    Copyright © 2021-2022 The Unigrid Foundation

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

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.ArrayList;
import java.util.Arrays;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.VestingStorage;
import org.unigrid.hedgehog.server.p2p.P2PServer;

@Path("/gridspork")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Slf4j(topic = "org.unigrid.hedgehog.server.rest.GridSporkResource")
public class GridSporkResource {
	@Inject
	private P2PServer p2pServer;

	@Path("/get") @GET
	public Response get() {
		return Response.ok().entity(new ArrayList<>(Arrays.asList("A", "B", "C"))).build();
	}

	@Path("/list") @GET
	public Response list() {
		return Response.ok().entity(new ArrayList<>(Arrays.asList("A", "B", "C"))).build();
	}

	@Path("/set/mint-storage/{key}") @POST
	public Response set(MintStorage.SporkData data, @PathParam("key") String key) {
		System.out.println("SHIIIIIIIIIIIIIIIIIIITLER");
		return Response.ok().entity(new ArrayList<>(Arrays.asList("A", "B", "C"))).build();
	}

	@SneakyThrows
	@Path("/set/mint-supply/{key}") @POST
	public Response set(MintSupply.SporkData data, @PathParam("key") String key) {
		log.debug(data.toString());

		if (ObjectUtils.isNotEmpty(data.getMaxSupply())) {
			return Response.ok().build();
			//final byte[] maxSupply = data.getMaxSupply().toPlainString().getBytes(CharsetUtil.ISO_8859_1);
			//final Signature signature = new Signature(Optional.of(key), Optional.empty());

			//System.out.println(p2pServer);
		}

		return Response.status(Status.BAD_REQUEST.getStatusCode(), "Missing 'maxSupply'").build();
	}

	@Path("/set/vesting-storage/{key}") @POST
	public Response set(VestingStorage.SporkData data, @PathParam("key") String key) {
		System.out.println("SHIIIIIIIIIIIIIIIIIIIT");
		return Response.ok().entity(new ArrayList<>(Arrays.asList("A", "B", "C"))).build();
	}
}
