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
import java.util.function.Supplier;
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
import org.unigrid.hedgehog.client.RestClient;
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

	/*
	 * Sporks outlive a try, so the keys of recent tries must still name who signed them. Only recent ones,
	 * because every signature check walks this list and every verifying key generates a keypair.
	 */
	private static final int MAX_RETIRED_KEYS = 16;
	private static final List<String> RETIRED_KEYS = new ArrayList<>();

	/* The second current network key of a try, next to the one provideSignature() hands out */
	protected static Signature cosigner;

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
		return cosign(new ObjectMapper().readTree(proposal.readEntity(String.class)).get("digest").asText(), cosigner);
	}

	@BeforeContainer
	private static void beforeContainer() {
		new ApplicationDirectoryMockUp();
	}

	@Provide
	@SneakyThrows
	public Arbitrary<Signature> provideSignature() {
		return Arbitraries.create(new Supplier<Signature>() {
			@Override
			@SneakyThrows
			public Signature get() {
				final Signature signature = new Signature();

				cosigner = new Signature();
				retire(signature);
				retire(cosigner);

				new MockUp<NetworkKey>() {
					@Mock public /* static */ String[] getPublicKeys() {
						return new String[] { signature.getPublicKey(), cosigner.getPublicKey() };
					}

					@Mock public /* static */ String[] getRetiredPublicKeys() {
						return RETIRED_KEYS.toArray(String[]::new);
					}
				};

				return signature;
			}
		});
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
