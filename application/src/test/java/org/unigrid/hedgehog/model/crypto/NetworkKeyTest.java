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

package org.unigrid.hedgehog.model.crypto;

import java.util.ArrayList;
import java.util.List;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.apache.commons.codec.binary.Hex;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class NetworkKeyTest extends BaseMockedWeldTest {
	private static final int NUM_SIGNATURES = 3;
	private static final List<Signature> SIGNATURES = new ArrayList<>(NUM_SIGNATURES);

	@SneakyThrows
	@BeforeProperty
	private void mockBefore() {
		for (int i = 0; i < NUM_SIGNATURES; i++) {
			SIGNATURES.add(new Signature());
		}

		new MockUp<NetworkKey>() {
			@Mock public static String[] getPublicKeys() {
				return SIGNATURES.stream().map(signature -> signature.getPublicKey()).toArray(String[]::new);
			}
		};
	}

	@Example
	@SneakyThrows
	public void storedPublicKeysShoulBeValid() {
		for (Signature signature : SIGNATURES) {
			assertThat(NetworkKey.isTrusted(signature.getPrivateKey()), is(true));
		}
	}

	@Property(tries = 10)
	public void randomPublicKeysShouldBeInvalid(@ForAll @Size(Signature.PRIVATE_KEY_HEX_SIZE) byte[] privateKey) {
		assertThat(NetworkKey.isTrusted(Hex.encodeHexString(privateKey)), is(false));
	}
}
