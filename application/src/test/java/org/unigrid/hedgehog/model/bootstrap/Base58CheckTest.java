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

package org.unigrid.hedgehog.model.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import java.util.Arrays;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;

public class Base58CheckTest {
	@Example
	public void shouldEncodeTheZeroHashAsTheKnownAddress() {
		assertThat(LegacyAddress.encode(new byte[Hashing.ADDRESS_HASH_SIZE]),
			equalTo("H6X8PLvXQDY3iLaTynKkQ1tUBBJjSZSf23"));
	}

	@Example
	public void shouldEncodeTheAllOnesHashAsTheKnownAddress() {
		final byte[] hash = new byte[Hashing.ADDRESS_HASH_SIZE];

		Arrays.fill(hash, (byte) 0xff);
		assertThat(LegacyAddress.encode(hash), equalTo("HVrjNTDp7PzvXmiZ1Cf4t9AFogZg5BbcAE"));
	}

	@Property(tries = 200)
	public void shouldRoundTripEveryAddressHash(@ForAll @Size(Hashing.ADDRESS_HASH_SIZE) byte[] hash) {
		final String address = LegacyAddress.encode(hash);

		assertThat(address, startsWith("H"));
		assertThat(LegacyAddress.decode(address), equalTo(hash));
	}

	@Example
	public void shouldRejectATamperedAddress() {
		final String address = LegacyAddress.encode(new byte[Hashing.ADDRESS_HASH_SIZE]);
		final String tampered = address.substring(0, address.length() - 1) + "Z";

		try {
			LegacyAddress.decode(tampered);
			throw new AssertionError("A tampered address was accepted: " + tampered);

		} catch (IllegalArgumentException expected) {
			assertThat(expected.getMessage(), startsWith("Address checksum does not match"));
		}
	}
}
