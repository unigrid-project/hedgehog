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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import static org.unigrid.hedgehog.command.option.NetOptions.DEFAULT_PORT;
import org.unigrid.hedgehog.jqwik.ArbitraryGenerator;
import org.unigrid.hedgehog.jqwik.Expect;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.TestFileOutput;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Dialed;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.service.ledger.LedgerStreams;

public class NodeResourceTest extends BaseRestClientTest {
	/* A port that is no number, and a host that does not resolve */
	private static final String UNRESOLVABLE = "no-such-host.invalid";
	private static final List<String> BAD_ADDRESSES = List.of("not:a-port", UNRESOLVABLE);

	@Mocked
	private Connection emptyConnection;

	@Inject
	private Topology topology;

	@Provide
	public Arbitrary<InetSocketAddress> provideAddress(@ForAll @IntRange(min = 1024, max = 65535) int port) {
		return Arbitraries.of(new InetSocketAddress(ArbitraryGenerator.ip4(), port));
	}

	private Response postAssert(String url, Node node) {
		return postAssert(url, node.getURI().toString().replace("/", ""));
	}

	@SneakyThrows
	private Response postAssert(String url, String host) {
		final Response response = client.post(url, Entity.text(host));

		assertThat(Status.fromStatusCode(response.getStatus()),
			equalTo(Status.CREATED)
		);

		return response;
	}

	/* A node that the operator adds is dialed as the seeds are, whatever peers tell this node */
	@Example
	@SneakyThrows
	public void shouldTakeANodeAddedOverRestForOneThatTheOperatorConfigured() {
		final Node node = Node.builder().address(new InetSocketAddress("45.9.9.9", 1234)).build();

		postAssert("/node", node);
		assertThat(topology.cloneConfigured(), hasItem(node));
	}

	/* A peer that tells of an address first, as an attacker may to keep it from the operator, leaves it to be configured
	   all the same: the node says that it knew it, and the connection that it dialed to it is one to a configured node
	   from then on */
	@Example
	@SneakyThrows
	public void shouldConfigureANodeThatAPeerToldOfFirst() {
		final EmbeddedChannel stream = LedgerStreams.stream(true);
		final Node told = Node.builder().address(new InetSocketAddress("45.9.9.10", 1234))
			.connection(Optional.of(LedgerStreams.connection(stream))).build();

		stream.parent().attr(Dialed.KEY).set(Dialed.CHOSEN);
		topology.addNode(told);

		final Response response = client.post("/node", Entity.text("45.9.9.10:1234"));

		assertThat(Status.fromStatusCode(response.getStatus()), equalTo(Status.OK));
		assertThat(response.readEntity(String.class), containsString("already"));
		assertThat(topology.cloneConfigured(), hasItem(told));
		assertThat(Dialed.of(stream), equalTo(Optional.of(Dialed.CONFIGURED)));
	}

	/* What the operator configured, the operator removes */
	@Example
	@SneakyThrows
	public void shouldConfigureNoLongerANodeThatTheOperatorRemoves() {
		final Node node = Node.builder().address(new InetSocketAddress("45.9.9.13", 1234)).build();

		postAssert("/node", node);
		assertThat(Status.fromStatusCode(client.delete("/node" + node.getURI()).getStatus()), equalTo(Status.OK));
		assertThat(topology.cloneConfigured(), not(hasItem(node)));
		assertThat(topology.cloneNodes(), not(hasItem(node)));
	}

	@Example
	@SneakyThrows
	public void shouldAnswerAConflictForANodeThatIsConfiguredAlready() {
		final Node node = Node.builder().address(new InetSocketAddress("45.9.9.11", 1234)).build();

		postAssert("/node", node);
		assertThat(Status.fromStatusCode(client.post("/node", Entity.text("45.9.9.11:1234")).getStatus()),
			equalTo(Status.CONFLICT));
	}

	@SneakyThrows
	@Property(tries = 150)
	public void shoulBeAbleToAddNodes(@ForAll("provideAddress") InetSocketAddress address) {
		final String url = "/node";
		final AtomicBoolean containsNode = new AtomicBoolean();
		Optional<Set<Node>> nodesOnServer = Optional.empty();

		final Response response = client.get(url);
		final Node node = Node.builder().address(address).build();

		containsNode.set(false);

		if (Status.fromStatusCode(response.getStatus()) == Status.OK) {
			nodesOnServer = Optional.of(response.readEntity(new GenericType<Set<Node>>(){}));
		}

		nodesOnServer.ifPresent(n -> {
			try {
				if (n.contains(node)) {
					final Response repsonse = client.get(url + node.getURI());
					containsNode.set(true);

					assertThat(Status.fromStatusCode(response.getStatus()),
						equalTo(Status.OK)
					);
				}
			} catch (ResponseOddityException ex) {
				assertThat("Rest client exception", false);
			}
		});

		if (!containsNode.get()) {
			final Response postResponse = postAssert(url, node);
			TestFileOutput.output(postResponse.getLocation().toString());
		}
	}

	@SneakyThrows
	@Property(tries = 50)
	public void shouldAddNodeWithMissingPort(@ForAll("provideAddress") InetSocketAddress address) {
		final String url = "/node";
		final String host = address.getHostString();
		final Status status = Status.fromStatusCode(client.post(url, Entity.text(host)).getStatus());

		/* Generated hosts repeat, and a host added before answers with a conflict */
		assertThat(status, is(oneOf(Status.CREATED, Status.CONFLICT)));

		if (status == Status.CREATED) {
			/* The address that was posted, not the name that a reverse lookup of it gives, which may be another one */
			final Node node = Node.builder().address(new InetSocketAddress(host, DEFAULT_PORT)).build();

			assertThat(Status.fromStatusCode(client.get(url + node.getURI()).getStatus()), equalTo(Status.OK));
		}
	}

	@Example
	public void shouldRefuseAHostThatDoesNotResolve() {
		try {
			client.post("/node", Entity.text("no-such-host.invalid"));
			assertThat("Unexpected response", false);
		} catch (ResponseOddityException ex) {
			assertThat(ex.getMessage(), containsString("Bad Request"));
		}
	}

	private void assertBadRequest(Expect.Action request) {
		final ResponseOddityException refusal = assertThrows(ResponseOddityException.class, request);

		assertThat(refusal.getStatusCode(), equalTo(Status.BAD_REQUEST.getStatusCode()));
		assertThat(refusal.getBody(), is(emptyString()));
	}

	/* The host is refused without a lookup, which is slow or never answers when the network is down, and any other
	   address is parsed and resolved as the node does it */
	private void assertBadRequests(Function<String, Expect.Action> request) {
		new MockUp<Node>() {
			@Mock public /* static */ Node fromAddress(String address) throws URISyntaxException, UnknownHostException {
				if (UNRESOLVABLE.equals(address)) {
					throw new UnknownHostException(address);
				}

				return Node.fromURI(new URI(null, address, null, null, null).parseServerAuthority());
			}
		};

		BAD_ADDRESSES.forEach(address -> assertBadRequest(request.apply(address)));
	}

	@Example
	public void shouldAnswerABadRequestWhenLookingForAnAddressThatIsNoNode() {
		assertBadRequests(address -> () -> client.get("/node/" + address));
	}

	@Example
	public void shouldAnswerABadRequestWhenAddingAnAddressThatIsNoNode() {
		assertBadRequests(address -> () -> client.post("/node", Entity.text(address)));
	}

	@Example
	public void shouldAnswerABadRequestWhenRemovingAnAddressThatIsNoNode() {
		assertBadRequests(address -> () -> client.delete("/node/" + address));
	}

	@SneakyThrows
	@Property(tries = 150)
	public void shoulBeAbleToRemoveNodes(@ForAll("provideAddress") InetSocketAddress address) {
		final String url = "/node";
		Optional<Set<Node>> nodesOnServer = Optional.empty();

		final Node node = Node.builder().address(address).build();
		final Response response = client.get(url + node.getURI());

		if (Status.fromStatusCode(response.getStatus()) == Status.NOT_FOUND) {
			postAssert(url, node);
		} else {
			final Response deleteResponse = client.delete(url + node.getURI());

			assertThat(Status.fromStatusCode(deleteResponse.getStatus()),
				equalTo(Status.OK)
			);
		}
	}
}
