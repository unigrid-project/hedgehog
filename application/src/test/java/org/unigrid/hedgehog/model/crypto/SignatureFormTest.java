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
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.Property;
import org.apache.commons.lang3.ArrayUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/* Of the two values of s that verify, a signature in its one form holds the lower, in DER of the shortest length, so
   that whoever holds it cannot make another signature of other bytes from it */
public class SignatureFormTest {
	private static final byte[] DATA = "a change of the board keys".getBytes(StandardCharsets.UTF_8);
	private static final Signature SIGNER = keyPair();

	@SneakyThrows
	private static Signature keyPair() {
		return new Signature();
	}

	@Property(tries = 20)
	@SneakyThrows
	public void shouldSignInTheOneFormThatVerifies() {
		final byte[] signature = SIGNER.signInOneForm(DATA);

		assertThat(Signature.isInOneForm(signature), is(true));
		assertThat(Signature.isSignedBy(SIGNER.getPublicKey(), DATA, signature), is(true));
	}

	@Example
	@SneakyThrows
	public void shouldTakeTheOtherValueOfSForNoSignatureInItsOneForm() {
		final byte[] signature = SIGNER.signInOneForm(DATA);
		final byte[] other = SignatureForms.withTheOtherS(signature);

		assertThat(Signature.isSignedBy(SIGNER.getPublicKey(), DATA, other), is(true));
		assertThat(Signature.isInOneForm(other), is(false));
	}

	@Example
	@SneakyThrows
	public void shouldTakeNoLongerEncodingForASignatureInItsOneForm() {
		final byte[] signature = SIGNER.signInOneForm(DATA);

		assertThat(Signature.isInOneForm(SignatureForms.withAPaddedR(signature)), is(false));
		assertThat(Signature.isInOneForm(ArrayUtils.add(signature, (byte) 0)), is(false));
		assertThat(Signature.isInOneForm(new byte[0]), is(false));
		assertThat(Signature.isInOneForm(null), is(false));
	}
}
