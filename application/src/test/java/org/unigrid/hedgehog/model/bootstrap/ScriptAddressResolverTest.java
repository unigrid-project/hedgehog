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
import static org.hamcrest.Matchers.nullValue;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;

public class ScriptAddressResolverTest {
	private static final byte[] UNCOMPRESSED_KEY = HexFormat.of().parseHex(
		"04b36556d4e6822708431cce73eaf447a0ec89a8ae6eb48aa412cb5b56bb6410"
		+ "acaa7cda7000e270b9900eb77667bb421728cab77e720c7ca2118150430c4f418a");

	@Property(tries = 100)
	public void shouldResolveAPublicKeyHashScript(@ForAll @Size(Hashing.ADDRESS_HASH_SIZE) byte[] hash) {
		assertThat(ScriptAddressResolver.resolve(publicKeyHashScript(hash)), equalTo(hash));
	}

	@Example
	public void shouldResolveAPublicKeyScriptToTheHashOfItsKey() {
		assertThat(ScriptAddressResolver.resolve(publicKeyScript(UNCOMPRESSED_KEY)),
			equalTo(Hashing.addressHash(UNCOMPRESSED_KEY)));
	}

	@Example
	public void shouldCollapsePublicKeyAndPublicKeyHashOntoOneAddress() {
		final byte[] hash = Hashing.addressHash(UNCOMPRESSED_KEY);

		assertThat(ScriptAddressResolver.resolve(publicKeyScript(UNCOMPRESSED_KEY)),
			equalTo(ScriptAddressResolver.resolve(publicKeyHashScript(hash))));
	}

	@Example
	public void shouldNotResolveScriptsThatCarryNoAddress() {
		assertThat(ScriptAddressResolver.resolve(new byte[0]), nullValue());
		assertThat(ScriptAddressResolver.resolve(new byte[] { (byte) 0x6a, 0x01, 0x00 }), nullValue());
		assertThat(ScriptAddressResolver.resolve(zerocoinMintScript()), nullValue());
	}

	@Example
	public void shouldRecogniseZerocoinScripts() {
		assertThat(ScriptAddressResolver.isZerocoinMint(zerocoinMintScript()), equalTo(true));
		assertThat(ScriptAddressResolver.isZerocoinSpend(new byte[] { (byte) 0xc2, 0x11 }), equalTo(true));
		assertThat(ScriptAddressResolver.isZerocoinMint(new byte[0]), equalTo(false));
		assertThat(ScriptAddressResolver.isZerocoinSpend(new byte[0]), equalTo(false));
	}

	private static byte[] publicKeyHashScript(byte[] hash) {
		return ByteBuffer.allocate(25).put((byte) 0x76).put((byte) 0xa9)
			.put((byte) Hashing.ADDRESS_HASH_SIZE).put(hash).put((byte) 0x88).put((byte) 0xac).array();
	}

	private static byte[] publicKeyScript(byte[] key) {
		return ByteBuffer.allocate(key.length + 2).put((byte) key.length).put(key).put((byte) 0xac).array();
	}

	private static byte[] zerocoinMintScript() {
		return new byte[] { (byte) 0xc1, 0x02, 0x00 };
	}
}
