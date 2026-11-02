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

import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.handler.SporkRecipients;
import org.unigrid.hedgehog.model.network.schedule.PublishSporkSchedule;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkDatabaseInfo;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.connection;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.sporkTypesWrittenTo;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.stream;

/* A node run without --preview whose database still holds the sporks that a run with it stored, co-signed by the
   board: it hands none of them out, shows or lists none, takes no new one and sends none to a peer. The peer here runs
   the ledger, so a node that took them would send it every one of them but the communication spork. */
public class PreviewSporkResourceTest extends BaseRestClientTest {
	private static final List<Type> OF_THE_PREVIEW = List.of(Type.STORAGE, Type.LEDGER_BLACKLIST, Type.LEDGER_FEE,
		Type.COMMUNICATION);

	@Inject private SporkDatabase sporkDatabase;
	@Inject private PendingSporks pendingSporks;
	@Inject private Topology topology;

	private final Map<Type, GridSpork> held = new EnumMap<>(Type.class);
	private EmbeddedChannel peer;

	@SneakyThrows
	private static <T extends GridSpork> T cosigned(final T spork) {
		spork.archive();
		spork.sign(PROPOSER.getPrivateKey());
		spork.cosign(COSIGNER.getPrivateKey());
		return spork;
	}

	private static MintSupply mintSupply() {
		final MintSupply spork = new MintSupply();

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		return spork;
	}

	@BeforeTry
	@SneakyThrows
	public void hold() {
		mockNetworkKeys();
		held.clear();

		for (final Type type : OF_THE_PREVIEW) {
			held.put(type, cosigned(GridSpork.create(type)));
			sporkDatabase.set(held.get(type));
			pendingSporks.remove(type);
		}

		sporkDatabase.set(cosigned(mintSupply()));
		sporkDatabase.clear(Type.MINT_STORAGE);
		peer = stream(true);

		final Node node = Node.builder().address(new InetSocketAddress("10.9.7.1", 1000)).build();

		topology.clear();
		topology.addNode(node);
		topology.modifyNode(node, member -> member.setConnection(Optional.of(connection(peer))));
	}

	@SneakyThrows
	private Status statusOf(final String location) {
		return Status.fromStatusCode(client.get(location).getStatus());
	}

	@Example
	public void shouldFindNoSporkOfThePreviewThatTheDatabaseHolds() {
		for (final Type type : OF_THE_PREVIEW) {
			assertThat(type.name(), statusOf("/gridspork/wire/" + type), equalTo(Status.NOT_FOUND));
		}

		assertThat(statusOf("/gridspork/wire/" + Type.MINT_SUPPLY), equalTo(Status.OK));
	}

	@SneakyThrows
	@Example
	public void shouldRefuseASporkOfThePreview() {
		for (final Type type : OF_THE_PREVIEW) {
			final GridSpork next = cosigned(GridSpork.nextVersion(type, held.get(type), Optional.empty()));
			final ResponseOddityException refusal = assertThrows(ResponseOddityException.class, () -> submit(next));

			assertThat(type.name(), refusal.getMessage(), startsWith("400 "));
			assertThat(type.name(), refusal.getBody(), containsString("This node takes no " + type + " spork"));
			assertThat(type.name(), sporkDatabase.get(type), sameInstance(held.get(type)));
			assertThat(type.name(), pendingSporks.proposalOf(type), equalTo(Optional.empty()));
		}

		assertThat(sporkTypesWrittenTo(peer), empty());
	}

	/* The endpoints are still there, so that a request for one of these sporks is told why there is none */
	@Example
	public void shouldShowASporkOfThePreviewAsDisabled() {
		final Map<Type, String> locations = Map.of(Type.STORAGE, "/gridspork/storage",
			Type.LEDGER_BLACKLIST, "/gridspork/ledger-blacklist", Type.LEDGER_FEE, "/gridspork/ledger-fee",
			Type.COMMUNICATION, "/gridspork/communication");

		locations.forEach((type, location) -> {
			final ResponseOddityException refusal = assertThrows(ResponseOddityException.class,
				() -> client.get(location));

			assertThat(location, refusal.getMessage(), startsWith("503 "));
			assertThat(location, refusal.getBody(), containsString("This release does not take the " + type
				+ " spork"));
		});

		assertThat(statusOf("/gridspork/mint-supply"), equalTo(Status.OK));
	}

	@SneakyThrows
	@Example
	public void shouldListNoSporkOfThePreview() {
		final List<String> names = OF_THE_PREVIEW.stream().map(Type::name).toList();
		final List<String> listed = client.getEntity("/gridspork/log", Map.class).keySet().stream()
			.map(String::valueOf).toList();

		assertThat(listed, hasItem(Type.MINT_SUPPLY.name()));
		assertThat(listed, everyItem(is(not(in(names)))));
	}

	/* Signed by no one, and so counting for nothing, as a spork of the preview that is held does not either */
	@SneakyThrows
	@Example
	public void shouldTellOfNoSporkOfThePreviewThatCountsForNothing() {
		for (final Type type : List.of(Type.MINT_STORAGE, Type.STORAGE, Type.LEDGER_BLACKLIST, Type.LEDGER_FEE,
			Type.COMMUNICATION)) {

			sporkDatabase.set(GridSpork.create(type));
		}

		final SporkDatabaseInfo info = client.getEntity("/gridspork", SporkDatabaseInfo.class);

		assertThat(info.getNotCounting(), contains(Type.MINT_STORAGE));
	}

	@Example
	public void shouldSendAPeerNoSporkOfThePreview() {
		OF_THE_PREVIEW.forEach(type -> SporkRecipients.sendAll(held.get(type), topology));
		assertThat(sporkTypesWrittenTo(peer), empty());

		PublishSporkSchedule.writeAndFlush(peer, sporkDatabase, pendingSporks);

		final List<Type> written = sporkTypesWrittenTo(peer);

		assertThat(written, hasItem(Type.MINT_SUPPLY));
		assertThat(written, everyItem(is(not(in(OF_THE_PREVIEW)))));
	}
}
