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

package org.unigrid.hedgehog.model.network;

import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.model.crypto.NetworkIdentifier;

public class NetworkIdentifierTest extends BaseMockedWeldTest {
	private static final byte[] MESSAGE = "publishGridnode".getBytes(StandardCharsets.UTF_8);

	@Inject
	private NetworkIdentifier id;

	private static NetworkIdentifier anotherIdentifier() {
		final NetworkIdentifier identifier = new NetworkIdentifier();

		identifier.init();
		return identifier;
	}

	@Example
	@SneakyThrows
	public void shouldVerifyWithOwnKey() {
		assertThat(id.verify(MESSAGE, id.sign(MESSAGE)), is(true));
	}

	@Example
	@SneakyThrows
	public void shouldVerifyATrustedNetworkKey() {
		final NetworkIdentifier other = anotherIdentifier();

		id.getNetworkKeys().add(other.getPublicKey());
		assertThat(id.verifyOther(MESSAGE, other.sign(MESSAGE)), is(true));
	}

	@Example
	@SneakyThrows
	public void shouldRejectAKeyItDoesNotTrust() {
		final NetworkIdentifier other = anotherIdentifier();

		id.getNetworkKeys().add(anotherIdentifier().getPublicKey());
		assertThat(id.verifyOther(MESSAGE, other.sign(MESSAGE)), is(false));
	}
}
