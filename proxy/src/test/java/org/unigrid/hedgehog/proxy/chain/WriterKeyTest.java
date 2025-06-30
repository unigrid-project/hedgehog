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

package org.unigrid.hedgehog.proxy.chain;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class WriterKeyTest {
	/* RFC 8032, section 7.1, test 1 */
	private static final byte[] SEED = HexFormat.of()
		.parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
	private static final String PUBLIC = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
	private static final String SIGNATURE = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb88215"
		+ "90a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";
	private static final byte[] IDENTITY_POINT = pointOf((byte) 0x01, (byte) 0x00, (byte) 0x00);
	private static final byte[] ORDER_TWO_POINT = pointOf((byte) 0xec, (byte) 0xff, (byte) 0x7f);
	private static final byte[] IDENTITY_SIGNATURE = signatureOfTheIdentity();

	private static byte[] pointOf(byte first, byte middle, byte last) {
		final byte[] point = new byte[WriterId.SIZE];

		Arrays.fill(point, middle);
		point[0] = first;
		point[point.length - 1] = last;
		return point;
	}

	private static byte[] signatureOfTheIdentity() {
		final byte[] signature = new byte[Entry.SIGNATURE_SIZE];

		System.arraycopy(IDENTITY_POINT, 0, signature, 0, IDENTITY_POINT.length);
		return signature;
	}

	@Example
	public void shouldMatchTheRfcVector() {
		final WriterKey key = new WriterKey(SEED);

		assertThat(key.id().toString(), is(PUBLIC));
		assertThat(HexFormat.of().formatHex(key.signature(new byte[0])), is(SIGNATURE));
	}

	@Property(tries = 100)
	public void shouldVerifyWhatItSigned(@ForAll @Size(WriterKey.SEED_SIZE) byte[] seed, @ForAll byte[] message) {
		final WriterKey key = new WriterKey(seed);

		assertThat(key.id().verify(message, key.signature(message)), is(true));
	}

	@Property(tries = 100)
	public void shouldRefuseAChangedMessage(@ForAll @Size(WriterKey.SEED_SIZE) byte[] seed,
		@ForAll @Size(min = 1, max = 64) byte[] message) {
		final WriterKey key = new WriterKey(seed);
		final byte[] signature = key.signature(message);

		message[0]++;
		assertThat(key.id().verify(message, signature), is(false));
	}

	@Property(tries = 100)
	public void shouldRefuseAnotherWriter(@ForAll @Size(WriterKey.SEED_SIZE) byte[] seed,
		@ForAll @Size(WriterKey.SEED_SIZE) byte[] other, @ForAll byte[] message) {
		Assume.that(!Arrays.equals(seed, other));
		assertThat(new WriterKey(other).id().verify(message, new WriterKey(seed).signature(message)), is(false));
	}

	@Property(tries = 100)
	public void shouldRefuseAWriterThatIsNoKey(@ForAll @Size(WriterId.SIZE) byte[] junk,
		@ForAll @Size(WriterKey.SEED_SIZE) byte[] seed, @ForAll byte[] message) {
		assertThat(new WriterId(junk).verify(message, new WriterKey(seed).signature(message)), is(false));
	}

	@Example
	public void shouldRefuseTheIdentityPointAsWriter() {
		assertThat(new WriterId(IDENTITY_POINT).verify(new byte[0], IDENTITY_SIGNATURE), is(false));
		assertThat(new WriterId(IDENTITY_POINT).verify(new byte[] { 1, 2, 3 }, IDENTITY_SIGNATURE), is(false));
	}

	@Property(tries = 100)
	public void shouldRefuseAnOrderTwoPointAsWriter(@ForAll byte[] message) {
		assertThat(new WriterId(ORDER_TWO_POINT).verify(message, IDENTITY_SIGNATURE), is(false));
	}

	@Property(tries = 50)
	public void shouldRefuseASignatureOfAnotherSize(@ForAll @Size(WriterKey.SEED_SIZE) byte[] seed,
		@ForAll @Size(max = 100) byte[] junk) {
		Assume.that(junk.length != Entry.SIGNATURE_SIZE);
		assertThat(new WriterKey(seed).id().verify(new byte[0], junk), is(false));
	}

	@Property(tries = 20)
	public void shouldRefuseASeedOfAnotherSize(@ForAll @Size(max = 64) byte[] seed) {
		Assume.that(seed.length != WriterKey.SEED_SIZE);
		Expect.assertThrows(IllegalArgumentException.class, () -> new WriterKey(seed));
	}

	@Example
	public void shouldGenerateDistinctKeys() {
		final SecureRandom random = new SecureRandom();

		assertThat(WriterKey.generate(random).id(), not(equalTo(WriterKey.generate(random).id())));
	}
}
