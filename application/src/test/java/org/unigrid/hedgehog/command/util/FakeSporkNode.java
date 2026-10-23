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

package org.unigrid.hedgehog.command.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.PendingSporkInfo;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkContent;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

/* A node as the commands that sign sporks see it: it hands out what it holds and the proposals it waits on in their
   wire form, and takes a signed spork back by the rules the daemon takes one by, which the model judges */
public final class FakeSporkNode implements RestCommandFixture.Answer {
	public static final String WIRE = "/gridspork/wire";
	public static final String PENDING = "/gridspork/pending";

	private static final ObjectMapper JSON = new JsonConfiguration().getContext(PendingSporkInfo.class);

	private final SporkDatabase database = new SporkDatabase();
	private final PendingSporks pending = new PendingSporks();
	private final List<GridSpork> submitted = new ArrayList<>();

	public FakeSporkNode holding(GridSpork... sporks) {
		Arrays.stream(sporks).forEach(database::set);
		return this;
	}

	public FakeSporkNode waitingOn(GridSpork proposal) {
		Validate.isTrue(pending.offer(proposal, database.get(proposal.getType())), "No node would hold the proposal");
		return this;
	}

	public GridSpork stored(Type type) {
		return database.get(type);
	}

	public Optional<GridSpork> proposalOf(Type type) {
		return pending.proposalOf(type);
	}

	public List<GridSpork> submitted() {
		return List.copyOf(submitted);
	}

	@Override
	public Response respond(Request request) throws ResponseOddityException {
		if (HttpMethod.POST.equals(request.method()) && WIRE.equals(request.location())) {
			return submit((byte[]) request.entity().orElseThrow().getEntity());
		}

		Validate.isTrue(HttpMethod.GET.equals(request.method()), "No command of sporks sends %s", request);
		return get(request.location());
	}

	private Response get(String location) {
		if (PENDING.equals(location)) {
			return proposals();
		}

		if (location.startsWith(PENDING + "/")) {
			return pending.find(StringUtils.substringAfterLast(location, "/")).map(FakeSporkNode::wire)
				.orElseGet(() -> Response.status(Status.NOT_FOUND).build());
		}

		Validate.isTrue(location.startsWith(WIRE + "/"), "No command of sporks asks for %s", location);
		return Optional.ofNullable(database.get(Type.valueOf(StringUtils.substringAfterLast(location, "/"))))
			.map(FakeSporkNode::wire).orElseGet(() -> Response.noContent().build());
	}

	@SneakyThrows
	private Response proposals() {
		final List<PendingSporkInfo> proposals = pending.list().stream().map(PendingSporkInfo::of).toList();

		return proposals.isEmpty() ? Response.noContent().build()
			: Response.ok(JSON.writeValueAsString(proposals)).build();
	}

	private static Response wire(GridSpork spork) {
		return Response.ok(SporkWire.encode(spork)).build();
	}

	/* What GridSporkWireResource answers, with the same model deciding */
	@SneakyThrows
	private Response submit(byte[] wire) throws ResponseOddityException {
		final GridSpork spork = SporkWire.decode(wire);
		final GridSpork stored = database.get(spork.getType());

		submitted.add(spork);

		try {
			SporkContent.validate(spork, stored);
		} catch (IllegalArgumentException ex) {
			throw new ResponseOddityException(Status.BAD_REQUEST, ex.getMessage());
		}

		if (!spork.isValidSignature() || !spork.isPending() && !spork.isDoublySigned()) {
			return Response.status(Status.UNAUTHORIZED).entity("No current board key signed the spork").build();
		}

		if (spork.isPending() ? !pending.offer(spork, stored) : !spork.canReplace(stored)) {
			return Response.status(Status.CONFLICT).entity("The spork does not follow on").build();
		}

		if (spork.isPending()) {
			return Response.accepted(JSON.writeValueAsString(PendingSporkInfo.of(spork))).build();
		}

		database.set(spork);
		pending.remove(spork.getType());
		return Response.ok().build();
	}
}
