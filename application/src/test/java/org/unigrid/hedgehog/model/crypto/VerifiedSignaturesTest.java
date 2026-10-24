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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import org.apache.commons.codec.digest.DigestUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/* A signature that verified is never checked again, whichever way it is checked, over what it signs or over the
   digest of that, while one that did not verify is checked each time, as anyone can make another */
public class VerifiedSignaturesTest {
	private static final byte[] DATA = "a spork".getBytes(StandardCharsets.UTF_8);
	private static final Signature SIGNER = keyPair();

	@SneakyThrows
	private static Signature keyPair() {
		return new Signature();
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private static int checksOf(BooleanSupplier work) {
		final AtomicInteger checks = new AtomicInteger();

		CheckMeter.charging(() -> checks.incrementAndGet() > 0, work::getAsBoolean);
		return checks.get();
	}

	@SneakyThrows
	private static boolean verifiesDigest(byte[] signature) {
		return Signature.verifyDigest(DigestUtils.sha512(DATA), signature, SIGNER.getPublicKey());
	}

	@Example
	@SneakyThrows
	public void shouldCheckASignatureThatVerifiedOnceWhicheverWayItIsChecked() {
		final byte[] signature = SIGNER.sign(DATA);

		assertThat(checksOf(() -> Signature.isSignedBy(SIGNER.getPublicKey(), DATA, signature)), is(1));
		assertThat(checksOf(() -> Signature.isSignedBy(SIGNER.getPublicKey(), DATA, signature)
			&& verifiesDigest(signature)), is(0));
	}

	@Example
	public void shouldCheckASignatureThatDidNotVerifyEachTime() {
		final byte[] forged = new byte[DATA.length];

		assertThat(checksOf(() -> Signature.isSignedBy(SIGNER.getPublicKey(), DATA, forged)
			|| Signature.isSignedBy(SIGNER.getPublicKey(), DATA, forged)), is(2));
	}

	/* A signature is known by its key and what it signs as well as by its bytes */
	@Example
	@SneakyThrows
	public void shouldCheckTheSameBytesAgainForAnotherKeyOrOtherData() {
		final byte[] signature = SIGNER.sign(DATA);
		final Signature other = keyPair();

		Signature.isSignedBy(SIGNER.getPublicKey(), DATA, signature);

		assertThat(checksOf(() -> Signature.isSignedBy(other.getPublicKey(), DATA, signature)), is(1));
		assertThat(checksOf(() -> Signature.isSignedBy(SIGNER.getPublicKey(), "another".getBytes(
			StandardCharsets.UTF_8), signature)), is(1));
	}

	@Example
	@SneakyThrows
	public void shouldRememberSoManyAndNoMore() {
		final byte[] first = SIGNER.sign(DATA);

		Signature.isSignedBy(SIGNER.getPublicKey(), DATA, first);

		for (int i = 0; i < VerifiedSignatures.CAPACITY; i++) {
			VerifiedSignatures.remember(SIGNER.getPublicKey(), DigestUtils.sha512(DATA), new byte[] {
				(byte) i, (byte) (i >> Byte.SIZE)
			});
		}

		assertThat(checksOf(() -> Signature.isSignedBy(SIGNER.getPublicKey(), DATA, first)), is(1));
	}
}
