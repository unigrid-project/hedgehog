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
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SerializationUtils;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.VestingStorage;
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData.Vesting;
import org.unigrid.hedgehog.server.p2p.P2PServer;

@Slf4j
@Path("/gridspork")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VestingStorageResource extends CDIBridgeResource {
	@CDIBridgeInject
	private P2PServer p2pServer;

	@CDIBridgeInject
	private SporkDatabase sporkDatabase;

	@Path("/vesting-storage") @GET
	public Response list() {
		final VestingStorage vs = sporkDatabase.getVestingStorage();

		if (Objects.isNull(vs)) {
			return Response.noContent().build();
		}

		return Response.ok().entity(sporkDatabase.getVestingStorage()).build();
	}

	@Path("/vesting-storage/{address}") @GET
	public Response get(@PathParam("address") String address) {
		if (Objects.isNull(sporkDatabase.getVestingStorage())) {
			return Response.noContent().build();
		}

		final VestingStorage.SporkData data = sporkDatabase.getVestingStorage().getData();
		final Vesting vesting = data.getVestingAddresses().get(Address.builder().wif(address).build());

		if (Objects.isNull(vesting)) {
			return Response.status(Response.Status.NOT_FOUND).build();
		}

		return Response.ok().entity(vesting).build();
	}

	@Path("/vesting-storage/{address}") @PUT
	public Response grow(@PathParam("address") String address, Vesting vesting,
		@HeaderParam("privateKey") String privateKey) {

		if (Objects.nonNull(privateKey) && NetworkKey.isTrusted(privateKey)) {
			VestingStorage vs = sporkDatabase.getVestingStorage();

			if (Objects.isNull(vs)) {
				vs = new VestingStorage();
			} else {
				vs = SerializationUtils.clone(vs);
			}

			final VestingStorage.SporkData data = vs.getData();
			boolean isUpdate = false;
			final Vesting oldVesting = data.getVestingAddresses().get(Address.builder().wif(address).build());

			vs.archive();
			isUpdate = Objects.nonNull(oldVesting);
			data.getVestingAddresses().put(Address.builder().wif(address).build(), vesting);

			try {
				vs.sign(privateKey);
			} catch (SigningException ex) {
				/* As we clone() the vesting storage, returning here results in a database NOP */
				return Response.status(Response.Status.UNAUTHORIZED).entity(ex).build();
			}

			sporkDatabase.setVestingStorage(vs);

			if (isUpdate) {
				return Response.noContent().build();
			}

			return Response.ok().build();
		}

		return Response.status(Response.Status.UNAUTHORIZED).build();
	}
}
