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

package org.unigrid.hedgehog.model.gridnode;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.proxy.session.HandshakeException;
import org.unigrid.hedgehog.proxy.session.NoiseNk;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class SealingKeyTest {
	private static final byte[] PRIVATE = GridnodeFixtures.sealingKey();

	@Example
	public void shouldDeriveThePublicHalfFromThePrivateKey() {
		final SealingKey key = SealingKey.of(PRIVATE);

		assertThat(key.privateKey().orElseThrow(), is(PRIVATE));
		assertThat(key.publicKey().orElseThrow(), is(SealingKeyFile.publicKey(PRIVATE)));
	}

	@Example
	public void shouldHoldNeitherHalfWhenThereIsNone() {
		assertThat(SealingKey.none().privateKey(), is(Optional.empty()));
		assertThat(SealingKey.none().publicKey(), is(Optional.empty()));
		assertThat(SealingKey.none().staticKey(), is(Optional.empty()));
	}

	/* The exit tries every entry it cannot place as an opening, so the key it opens them under is made ready once
	   and handed out as it is, with no copy of the bytes */
	@Example
	public void shouldMakeItsStaticKeyReadyOnceForTheOpeningsSealedToIt() throws HandshakeException {
		final SealingKey key = SealingKey.of(PRIVATE);
		final byte[] opening = NoiseNk.initiate(key.publicKey().orElseThrow(), new byte[0], new SecureRandom())
			.message();

		assertThat(key.staticKey().orElseThrow(), is(sameInstance(key.staticKey().orElseThrow())));
		assertThat(NoiseNk.respond(key.staticKey().orElseThrow(), new byte[0], opening).isPresent(), is(true));
	}

	@Example
	public void shouldKeepItsOwnCopyOfTheKey() {
		final byte[] given = PRIVATE.clone();
		final SealingKey key = SealingKey.of(given);

		given[0] ^= 1;
		key.privateKey().orElseThrow()[1] ^= 1;
		key.publicKey().orElseThrow()[1] ^= 1;

		assertThat(key.privateKey().orElseThrow(), is(PRIVATE));
		assertThat(key.publicKey().orElseThrow(), is(SealingKeyFile.publicKey(PRIVATE)));
	}

	@Example
	public void shouldRefuseAKeyOfAnyOtherSize() {
		for (int size : new int[] { 0, 31, 33 }) {
			assertThrows(IllegalArgumentException.class, () -> SealingKey.of(new byte[size]));
		}
	}

	@Example
	public void shouldNeverShowEitherHalf() {
		final SealingKey key = SealingKey.of(PRIVATE);

		assertThat(key.toString(), not(containsString(HexFormat.of().formatHex(PRIVATE))));
		assertThat(key.toString(), not(containsString(HexFormat.of().formatHex(key.publicKey().orElseThrow()))));
	}
}
