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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.bootstrap.AddressBalance;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.BoardKeysAuthority;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.PendingSporkInfo;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.content.SporkContent;

/* A node as the commands that sign sporks see it: it hands out what it holds and the proposals it waits on in their
   wire form, and takes a signed spork back by the rules the daemon takes one by, which the model judges */
public final class FakeSporkNode implements RestCommandFixture.Answer {
	public static final String WIRE = "/gridspork/wire";
	public static final String PENDING = "/gridspork/pending";

	private static final String SHOWN = "/gridspork/";
	private static final String BALANCE = "/bootstrap/address/";
	private static final String BALANCES = "/bootstrap/addresses";

	private static final ObjectMapper JSON = new JsonConfiguration().getContext(PendingSporkInfo.class);

	private final SporkDatabase database = new SporkDatabase();
	private final PendingSporks pending = new PendingSporks();
	private final List<GridSpork> submitted = new ArrayList<>();
	private final List<GridSpork> forged = new ArrayList<>();
	private final Map<String, AddressBalance> snapshot = new HashMap<>();
	private final Map<String, BigDecimal> mints = new HashMap<>();
	private boolean holdsASnapshot = true;
	private boolean looksUpAddresses = true;

	public FakeSporkNode holding(GridSpork... sporks) {
		Arrays.stream(sporks).forEach(database::set);
		return this;
	}

	public FakeSporkNode waitingOn(GridSpork proposal) {
		Validate.isTrue(pending.offer(proposal, database.get(proposal.getType())), "No node would hold the proposal");
		return this;
	}

	/* A proposal that no honest node would hold, handed out by one that is not honest or by whatever stands between
	   it and the command line */
	public FakeSporkNode handingOut(GridSpork forged) {
		this.forged.add(forged);
		return this;
	}

	/**
	 * The addresses its legacy snapshot knows, which it answers for as a node does at /bootstrap/address.
	 *
	 * @param balances what the snapshot holds for each address
	 * @return this node
	 */
	public FakeSporkNode knowing(AddressBalance... balances) {
		Arrays.stream(balances).forEach(balance -> snapshot.put(balance.getAddress(), balance));
		return this;
	}

	/**
	 * A mint the node still owes an address, which it adds to the balance it gives for that address, as a node does.
	 *
	 * @param address the legacy address
	 * @param amount the pending mint
	 * @return this node
	 */
	public FakeSporkNode owing(String address, BigDecimal amount) {
		mints.merge(address, amount, BigDecimal::add);
		return this;
	}

	/**
	 * A node that holds no legacy snapshot, which answers every question about one with 503.
	 *
	 * @return this node
	 */
	public FakeSporkNode withoutASnapshot() {
		holdsASnapshot = false;
		return this;
	}

	/**
	 * A node of a release without /bootstrap/addresses, which answers it with 404 as it does any path it does not
	 * serve.
	 *
	 * @return this node
	 */
	public FakeSporkNode withoutTheAddressLookup() {
		looksUpAddresses = false;
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

		if (HttpMethod.POST.equals(request.method()) && BALANCES.equals(request.location())) {
			if (!looksUpAddresses) {
				return Response.status(Status.NOT_FOUND).build();
			}

			return balancesOf((List<?>) request.entity().orElseThrow().getEntity());
		}

		Validate.isTrue(HttpMethod.GET.equals(request.method()), "No command of sporks sends %s", request);

		if (request.location().startsWith(BALANCE)) {
			return balanceOf(StringUtils.removeStart(request.location(), BALANCE));
		}

		return get(request.location());
	}

	/**
	 * What the snapshot holds for an address, as a node answers it.
	 *
	 * @param address the legacy address
	 * @return the balance, or 404 when the snapshot does not know it
	 * @throws ResponseOddityException with 503 when the node holds no snapshot
	 */
	private Response balanceOf(String address) throws ResponseOddityException {
		requireASnapshot();
		return held(address).map(balance -> Response.ok(balance).build())
			.orElseGet(() -> Response.status(Status.NOT_FOUND).build());
	}

	/**
	 * What the node holds for many addresses at once, as /bootstrap/addresses answers: each as it answers for one,
	 * leaving out those it knows nothing of.
	 *
	 * @param addresses the legacy addresses
	 * @return their balances
	 * @throws ResponseOddityException with 503 when the node holds no snapshot
	 */
	private Response balancesOf(List<?> addresses) throws ResponseOddityException {
		requireASnapshot();
		return Response.ok(addresses.stream().map(String::valueOf).map(this::held).flatMap(Optional::stream)
			.toList()).build();
	}

	/** The snapshot balance of an address with the mints still owed to it, or nothing when it has neither. */
	private Optional<AddressBalance> held(String address) {
		final BigDecimal owed = mints.getOrDefault(address, BigDecimal.ZERO);

		return Optional.ofNullable(snapshot.get(address))
			.map(balance -> new AddressBalance(address, balance.getBalance().add(owed),
				balance.getTransactionCount()))
			.or(() -> owed.signum() > 0 ? Optional.of(new AddressBalance(address, owed, 0)) : Optional.empty());
	}

	/** A node without a snapshot answers every question about one with 503. */
	private void requireASnapshot() throws ResponseOddityException {
		if (!holdsASnapshot) {
			throw new ResponseOddityException(Status.SERVICE_UNAVAILABLE);
		}
	}

	private Response get(String location) {
		if (PENDING.equals(location)) {
			return proposals();
		}

		if (location.startsWith(PENDING + "/")) {
			final String digest = StringUtils.substringAfterLast(location, "/");

			return heldProposals().filter(spork -> PendingSporks.digestOf(spork).equals(digest)).findFirst()
				.map(FakeSporkNode::wire).orElseGet(() -> Response.status(Status.NOT_FOUND).build());
		}

		if (!location.startsWith(WIRE + "/")) {
			return shown(location);
		}

		return Optional.ofNullable(database.get(Type.valueOf(StringUtils.substringAfterLast(location, "/"))))
			.map(FakeSporkNode::wire).orElseGet(() -> Response.noContent().build());
	}

	/* Where the daemon shows the spork of a type whole, under the name of the type: it answers nothing where it holds
	   none, and a command that signs reads no more of the answer than that there is one */
	private Response shown(String location) {
		Validate.isTrue(location.startsWith(SHOWN), "No command of sporks asks for %s", location);

		final String name = StringUtils.removeStart(location, SHOWN).toUpperCase(Locale.ROOT).replace('-', '_');

		return database.get(Type.valueOf(name)) == null ? Response.noContent().build() : Response.ok().build();
	}

	private Stream<GridSpork> heldProposals() {
		return Stream.concat(forged.stream(), pending.list().stream());
	}

	@SneakyThrows
	private Response proposals() {
		final List<PendingSporkInfo> proposals = heldProposals().map(PendingSporkInfo::of).toList();

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

		if (spork.isPending() ? !pending.offer(spork, () -> database.get(spork.getType()), () -> true)
			: pending.isAheadOfTime(spork)
			|| !spork.canReplace(stored)) {
			return Response.status(Status.CONFLICT).entity("The spork does not follow on").build();
		}

		if (spork.isPending()) {
			return Response.accepted(JSON.writeValueAsString(PendingSporkInfo.of(spork))).build();
		}

		if (spork instanceof BoardKeys boardKeys) {
			BoardKeysAuthority.accept(boardKeys, database, pending, () -> true);
			return Response.ok().build();
		}

		database.set(spork);
		pending.retainProposalsOver(spork);
		return Response.ok().build();
	}
}
