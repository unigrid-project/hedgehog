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
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import org.apache.commons.codec.digest.DigestUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

/* Each check of a signature that a thread makes on an account is charged to it once, whatever the check answers, and
   a check that the account does not pay for is not made */
public class CheckMeterTest {
	private static final byte[] DATA = "a spork".getBytes(StandardCharsets.UTF_8);

	@SneakyThrows
	private static Signature keyPair() {
		return new Signature();
	}

	@Example
	@SneakyThrows
	public void shouldChargeEachCheckOnceWhateverItAnswers() {
		final Signature signer = keyPair();
		final byte[] signature = signer.sign(DATA);
		final AtomicInteger charged = new AtomicInteger();

		final byte[] another = signer.sign(DATA);

		CheckMeter.charging(() -> charged.incrementAndGet() > 0, () -> {
			Signature.isSignedBy(signer.getPublicKey(), DATA, signature);
			Signature.isSignedBy(signer.getPublicKey(), DATA, new byte[signature.length]);
			return verifies(DigestUtils.sha512(DATA), another, signer.getPublicKey());
		});

		assertThat(charged.get(), is(3));
	}

	@Example
	@SneakyThrows
	public void shouldMakeNoCheckThatTheAccountDoesNotPayFor() {
		final Signature signer = keyPair();
		final byte[] signature = signer.sign(DATA);

		assertThrows(ChecksSpentException.class, () -> CheckMeter.charging(() -> false,
			() -> Signature.isSignedBy(signer.getPublicKey(), DATA, signature)));
		assertThat(Signature.isSignedBy(signer.getPublicKey(), DATA, signature), is(true));
	}

	@SneakyThrows
	private static boolean verifies(byte[] digest, byte[] signature, String key) {
		return Signature.verifyDigest(digest, signature, key);
	}
}
