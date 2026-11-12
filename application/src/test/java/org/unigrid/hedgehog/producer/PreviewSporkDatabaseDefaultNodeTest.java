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

package org.unigrid.hedgehog.producer;

import jakarta.ws.rs.core.Response.Status;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeContainer;
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
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.sporkTypesWrittenTo;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.QuicPeer;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.server.BaseDefaultNodeSporkTest;
import org.unigrid.hedgehog.transport.handler.SporkRecipients;
import org.unigrid.hedgehog.transport.schedule.PublishSporkSchedule;

/* A node run without --preview starts on a spork database that a run with it left behind: it holds the sporks of the
   parts that this release leaves off, co-signed by the board, next to one of this release. It sends a peer none of
   them, whether the peer opens a stream to it, the schedule runs or a relay sends, and shows none of them over REST.
   The peer here reads every type of spork, so the only reason it is sent none is that this release does not take
   them. The file is on a file system in memory, from where the producer of the database reads it. */
public class PreviewSporkDatabaseDefaultNodeTest extends BaseDefaultNodeSporkTest {
	@BeforeContainer
	@SneakyThrows
	private static void leaveTheSporksOfARunWithThePreview() {
		final SporkDatabase left = SporkDatabase.builder().build();

		OF_THE_PREVIEW.forEach(type -> left.set(cosigned(type)));
		left.set(mintSupply());

		SporkDatabase.persist(Files.createDirectories(dataDirectory.getUserDataDir())
			.resolve(SporkDatabase.SPORK_DB_FILE), left);
	}

	/* That the node holds them, and counts them, is what makes the rest of these examples a test of the release */
	private void assertTheNodeHoldsThem() {
		for (Type type : OF_THE_PREVIEW) {
			assertThat(type + " loaded", database.get(type), is(notNullValue()));
			assertThat(type + " counting", database.counting(type), is(notNullValue()));
		}

		assertThat(Type.MINT_SUPPLY + " loaded", database.counting(Type.MINT_SUPPLY), is(notNullValue()));
		assertThat("parts of the preview created", createdOfThePreview(), is(empty()));
	}

	private static List<Type> sporkTypesIn(final QuicPeer.Stream stream) {
		return stream.frames().stream().filter(frame -> frame.type() == Packet.Type.PUBLISH_SPORK)
			.map(frame -> SporkWire.decode(frame.body()).getType()).toList();
	}

	@Example
	public void shouldLoadTheSporksOfThePreviewFromTheFile() {
		assertTheNodeHoldsThem();
	}

	/* The node writes the sporks it holds to a stream that a peer opens as the stream is set up. This peer settles on the
	   protocol of the release, which reads the storage spork too, so a node that took it would send it. */
	@Example
	public void shouldSendAPeerThatOpensAStreamNoSporkOfThePreview() throws InterruptedException {
		assertTheNodeHoldsThem();

		try (QuicPeer peer = QuicPeer.dial(NetOptions.getHost(), NetOptions.getPort(), Network.getProtocols())) {
			final QuicPeer.Stream stream = peer.open();

			stream.awaitAnswer(stream.ping());
			assertThat(sporkTypesIn(stream), contains(Type.MINT_SUPPLY));
		}
	}

	@Example
	public void shouldSendNoSporkOfThePreviewWhenTheScheduleRuns() {
		assertTheNodeHoldsThem();

		new PublishSporkSchedule().getConsumer().accept(recipient);
		assertThat(sporkTypesWrittenTo(recipient), contains(Type.MINT_SUPPLY));
	}

	@Example
	public void shouldRelayNoSporkOfThePreviewThatItHolds() {
		assertTheNodeHoldsThem();

		for (Type type : OF_THE_PREVIEW) {
			SporkRecipients.sendAll(database.get(type), topology);
			SporkRecipients.write(recipient, database.get(type));
		}

		assertThat(sporkTypesWrittenTo(recipient), is(empty()));

		SporkRecipients.sendAll(database.get(Type.MINT_SUPPLY), topology);
		assertThat(sporkTypesWrittenTo(recipient), contains(Type.MINT_SUPPLY));
	}

	@SneakyThrows
	private Status statusOf(final String location) {
		return Status.fromStatusCode(client.get(location).getStatus());
	}

	@Example
	public void shouldHandOutNoSporkOfThePreviewOverRest() {
		assertTheNodeHoldsThem();

		for (Type type : OF_THE_PREVIEW) {
			assertThat(type.name(), statusOf("/gridspork/wire/" + type), equalTo(Status.NOT_FOUND));
		}

		assertThat(statusOf("/gridspork/wire/" + Type.MINT_SUPPLY), equalTo(Status.OK));
	}

	/* The endpoints are still there, so that a request for one of these sporks is told why there is none */
	@Example
	public void shouldShowASporkOfThePreviewAsDisabledOverRest() {
		assertTheNodeHoldsThem();

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
	public void shouldListNoSporkOfThePreviewOverRest() {
		assertTheNodeHoldsThem();

		final List<String> names = OF_THE_PREVIEW.stream().map(Type::name).toList();
		final List<String> listed = client.getEntity("/gridspork/log", Map.class).keySet().stream()
			.map(String::valueOf).toList();

		assertThat(listed, hasItem(Type.MINT_SUPPLY.name()));
		assertThat(listed, everyItem(is(not(in(names)))));
		assertThat(statusOf("/gridspork/pending"), equalTo(Status.NO_CONTENT));
	}
}
