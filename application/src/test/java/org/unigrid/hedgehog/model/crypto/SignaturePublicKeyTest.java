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
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class SignaturePublicKeyTest {
	private static final byte[] DATA = "payload".getBytes(StandardCharsets.UTF_8);

	@Example
	public void shouldAcceptASignatureOfTheKeyHolder() throws Exception {
		final Signature signer = new Signature();

		assertThat(Signature.isSignedBy(signer.getPublicKey(), DATA, signer.sign(DATA)), is(true));
	}

	@Example
	public void shouldRejectChangedData() throws Exception {
		final Signature signer = new Signature();
		final byte[] changed = "payloae".getBytes(StandardCharsets.UTF_8);

		assertThat(Signature.isSignedBy(signer.getPublicKey(), changed, signer.sign(DATA)), is(false));
	}

	@Example
	public void shouldRejectAnotherKey() throws Exception {
		final byte[] signature = new Signature().sign(DATA);

		assertThat(Signature.isSignedBy(new Signature().getPublicKey(), DATA, signature), is(false));
	}

	@Example
	public void shouldRejectKeysThatAreNotPublicKeys() throws Exception {
		final byte[] signature = new Signature().sign(DATA);

		assertThat(Signature.isSignedBy("", DATA, signature), is(false));
		assertThat(Signature.isSignedBy("zz".repeat(131), DATA, signature), is(false));
		assertThat(Signature.isSignedBy("11".repeat(131), DATA, signature), is(false));
		assertThat(Signature.isSignedBy("11".repeat(130), DATA, signature), is(false));
	}

	@Example
	public void shouldRejectGarbageSignatures() throws Exception {
		assertThat(Signature.isSignedBy(new Signature().getPublicKey(), DATA, new byte[] { 1, 2, 3 }), is(false));
	}
}
