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

import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.ResponseOddityException;
import static org.unigrid.hedgehog.command.option.NetOptions.DEFAULT_PORT;
import org.unigrid.hedgehog.jqwik.ArbitraryGenerator;
import org.unigrid.hedgehog.jqwik.TestFileOutput;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Node;

public class NodeResourceTest extends BaseRestClientTest {
	@Mocked
	private Connection emptyConnection;

	@Provide
	public Arbitrary<InetSocketAddress> provideAddress(@ForAll @IntRange(min = 1024, max = 65535) int port) {
		return Arbitraries.of(InetSocketAddress.createUnresolved(ArbitraryGenerator.ip4(), port));
	}

	/*@Provide
	public Arbitrary<String> provideProtocol(@ForAll @AlphaChars @StringLength(min = 4, max = 16) String feature) {
		return Arbitraries.of(feature.concat("/" + ArbitraryGenerator.version()));
	}*/

	/*@Provide
	public Arbitrary<Node> provideNode(@ForAll short version, @ForAll Instant lastPingTime,
		@ForAll @UniqueElements @Size(max = 5) List<@From("provideProtocol") String> protocols,
		@ForAll("provideAddress") InetSocketAddress address) {

		final Details details = Details.builder()
			.protocols(protocols.toArray(new String[0]))
			.version(version)
			.build();

		final Node node = Node.builder().
			address(address).connection(Optional.of(emptyConnection)).
			details(details).lastPingTime(lastPingTime).ping(Optional.empty()).
			build();

		return Arbitraries.of(node);
	}*/

	private Response postAssert(String url, Node node) {
		return postAssert(url, node.getURI().toString().replace("/", ""));
	}

	@SneakyThrows
	private Response postAssert(String url, String host) {
		final Response response = client.post(url, host);

		assertThat(Status.fromStatusCode(response.getStatus()),
			equalTo(Status.CREATED)
		);

		return response;
	}

	@SneakyThrows
	@Property(tries = 200)
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
		try {
			final String url = "/node";
			final Response postResponse = postAssert(url, address.getHostName());

			if (Status.fromStatusCode(postResponse.getStatus()) == Status.CREATED) {
				final InetSocketAddress addressWithDefaultPort = InetSocketAddress.createUnresolved(
					address.getHostName(), DEFAULT_PORT
				);

				final Node node = Node.builder().address(addressWithDefaultPort).build();
				final Response response = client.get(url + node.getURI());

				assertThat(Status.fromStatusCode(response.getStatus()),
					equalTo(Status.OK)
				);
			} else {
				assertThat("Unexpected response", false);
			}
		} catch(ResponseOddityException ex) {
			assertThat(ex.getMessage(), containsString("Conflict"));
		}
	}

	@SneakyThrows
	@Property(tries = 500)
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
