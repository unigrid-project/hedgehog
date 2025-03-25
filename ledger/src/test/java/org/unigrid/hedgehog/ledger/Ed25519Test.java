/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.ledger;

import java.util.Arrays;
import java.util.HexFormat;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class Ed25519Test {
	/* RFC 8032, section 7.1, test 1 */
	private static final byte[] SEED = HexFormat.of()
		.parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
	private static final String PUBLIC = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
	private static final String SIGNATURE = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb88215"
		+ "90a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";

	@Example
	public void shouldMatchTheRfcVector() {
		assertThat(Ed25519.publicKey(SEED).toString(), equalTo(PUBLIC));
		assertThat(HexFormat.of().formatHex(Ed25519.sign(SEED, new byte[0])), equalTo(SIGNATURE));
	}

	@Property(tries = 100)
	public void shouldVerifyWhatItSigned(@ForAll @Size(32) byte[] seed, @ForAll byte[] message) {
		final byte[] signature = Ed25519.sign(seed, message);

		assertThat(signature.length, equalTo(Ed25519.SIGNATURE_SIZE));
		assertThat(Ed25519.verify(Ed25519.publicKey(seed), message, signature), is(true));
	}

	@Property(tries = 100)
	public void shouldRefuseAChangedMessage(@ForAll @Size(32) byte[] seed,
		@ForAll @Size(min = 1, max = 64) byte[] message) {
		final byte[] signature = Ed25519.sign(seed, message);
		final byte[] changed = message.clone();

		changed[0]++;
		assertThat(Ed25519.verify(Ed25519.publicKey(seed), changed, signature), is(false));
	}

	@Property(tries = 100)
	public void shouldRefuseAnotherKey(@ForAll @Size(32) byte[] seed, @ForAll @Size(32) byte[] other,
		@ForAll byte[] message) {
		Assume.that(!Arrays.equals(seed, other));
		assertThat(Ed25519.verify(Ed25519.publicKey(other), message, Ed25519.sign(seed, message)), is(false));
	}

	@Property(tries = 50)
	public void shouldRefuseASignatureOfTheWrongLength(@ForAll @Size(32) byte[] seed, @ForAll byte[] junk) {
		Assume.that(junk.length != Ed25519.SIGNATURE_SIZE);
		assertThat(Ed25519.verify(Ed25519.publicKey(seed), new byte[0], junk), is(false));
	}
}
