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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.Mocked;
import mockit.MockUp;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.AfterTry;
import org.bitcoinj.crypto.ECKey;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.ApplicationDirectoryMockUp;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.server.TestServer;

@WeldSetup(TestServer.class)
public class BaseRestClientTest extends BaseMockedWeldTest {
	@Mocked
	protected NetOptions netOptions;

	@Mocked
	protected RestOptions restOptions;

	@Inject
	protected TestServer server;

	protected RestClient client;

	/* A P-521 key pair takes a dozen tries to generate, so the two current network keys are made once for the run.
	   The sporks a class stores outlive its tries, and stay signed by keys that the node still trusts. */
	protected static final Signature PROPOSER = keyPair();
	protected static final Signature COSIGNER = keyPair();

	/* The keys a test retires to stand for an earlier board, which a signature log may still name. Only recent ones,
	   because every signature check walks this list. */
	private static final int MAX_RETIRED_KEYS = 16;
	private static final List<String> RETIRED_KEYS = new ArrayList<>();

	@SneakyThrows
	private static Signature keyPair() {
		return new Signature();
	}

	protected static void retire(Signature signature) {
		RETIRED_KEYS.add(signature.getPublicKey());

		if (RETIRED_KEYS.size() > MAX_RETIRED_KEYS) {
			RETIRED_KEYS.removeFirst();
		}
	}

	@SneakyThrows
	protected Response cosign(String digest, Signature signature) {
		return client.putWithHeaders("/gridspork/pending/" + digest, Entity.text(""),
			new MultivaluedHashMap(Map.of("privateKey", signature.getPrivateKey()))
		);
	}

	@SneakyThrows
	protected Response cosign(Response proposal) {
		return cosign(new ObjectMapper().readTree(proposal.readEntity(String.class)).get("digest").asText(), COSIGNER);
	}

	public List<ECKey> keys = new ArrayList<>();

	@BeforeContainer
	private static void beforeContainer() {
		new ApplicationDirectoryMockUp();
	}

	/* The proposer, with the network keys faked for the try that draws it */
	@Provide
	public Arbitrary<Signature> provideSignature() {
		return Arbitraries.create(() -> {
			mockNetworkKeys();
			return PROPOSER;
		});
	}

	protected static void mockNetworkKeys() {
		new MockUp<NetworkKey>() {
			@Mock public /* static */ String[] getPublicKeys() {
				return new String[] { PROPOSER.getPublicKey(), COSIGNER.getPublicKey() };
			}

			@Mock public /* static */ String[] getRetiredPublicKeys() {
				return RETIRED_KEYS.toArray(String[]::new);
			}
		};
	}

	@BeforeProperty
	public void before() {
		TestServer.mockProperties(server);
	}

	@BeforeTry
	public void beforeTry() {
		client = new RestClient(server.getRest().getHostName(), server.getRest().getPort(), true,
			server.getRest().getToken()
		);
	}

	@AfterTry
	public void afterTry() {
		client.close();
	}
}
