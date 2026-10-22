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

package org.unigrid.hedgehog.model.crypto;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGeneratorSpi;
import java.util.Optional;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.ArrayUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

/* Anyone can send a spork, and checking it against the network keys must not cost a key pair each time. Every key is
   made before the generator of the JDK is faked to fail, and the fake outlives the example, so it has a class and
   with it a JVM to itself. */
public class SignatureVerifierTest {
	private static final byte[] DATA = "payload".getBytes(StandardCharsets.UTF_8);

	@SneakyThrows
	@Example
	public void shouldVerifyWithoutGeneratingAKey() {
		final Signature signer = new Signature();
		final String key = signer.getPublicKey();
		final String otherKey = new Signature().getPublicKey();
		final byte[] digest = DigestUtils.sha512(DATA);
		final byte[] signature = signer.sign(DATA);
		final byte[] forged = signer.sign(ArrayUtils.add(DATA, (byte) 0));

		new MockUp<KeyPairGeneratorSpi>(Class.forName("sun.security.ec.ECKeyPairGenerator")) {
			@Mock
			public KeyPair generateKeyPair() {
				throw new AssertionError("A key pair was generated");
			}
		};

		assertThat(Signature.verifyDigest(digest, forged, key), is(false));
		assertThat(Signature.verifyDigest(digest, signature, key), is(true));
		assertThat(Signature.verifyDigest(digest, signature, otherKey), is(false));
		assertThat(new Signature(Optional.empty(), Optional.of(key)).verify(DATA, signature), is(true));
		assertThat(new Signature(Optional.of(signer.getPrivateKey()), Optional.empty()).sign(DATA).length > 0, is(true));

		assertThrows(AssertionError.class, Signature::new);
	}
}
