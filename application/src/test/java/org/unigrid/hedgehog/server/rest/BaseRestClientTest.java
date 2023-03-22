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

import jakarta.inject.Inject;
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

				new MockUp<NetworkKey>() {
					@Mock public /* static */ String[] getPublicKeys() {
						return new String[] { signature.getPublicKey() };
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
		client = new RestClient(server.getRest().getHostName(), server.getRest().getPort(), true);
	}

	@AfterTry
	public void afterTry() {
		client.close();
	}
}
