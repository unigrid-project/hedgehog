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

import java.util.Arrays;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ScriptAddressResolver {
	public static final byte OP_ZEROCOIN_MINT = (byte) 0xc1;
	public static final byte OP_ZEROCOIN_SPEND = (byte) 0xc2;

	private static final byte OP_DUP = (byte) 0x76;
	private static final byte OP_HASH160 = (byte) 0xa9;
	private static final byte OP_EQUAL = (byte) 0x87;
	private static final byte OP_EQUALVERIFY = (byte) 0x88;
	private static final byte OP_CHECKSIG = (byte) 0xac;

	private static final int PUBLIC_KEY_HASH_SCRIPT_SIZE = 25;
	private static final int SCRIPT_HASH_SCRIPT_SIZE = 23;
	private static final int COMPRESSED_PUBLIC_KEY_SCRIPT_SIZE = 35;
	private static final int UNCOMPRESSED_PUBLIC_KEY_SCRIPT_SIZE = 67;

	public static byte[] resolve(byte[] script) {
		if (isPublicKeyHash(script)) {
			return Arrays.copyOfRange(script, 3, 3 + Hashing.ADDRESS_HASH_SIZE);
		}

		if (isPublicKey(script)) {
			return Hashing.addressHash(Arrays.copyOfRange(script, 1, script.length - 1));
		}

		if (isScriptHash(script)) {
			return Arrays.copyOfRange(script, 2, 2 + Hashing.ADDRESS_HASH_SIZE);
		}

		return null;
	}

	public static boolean isZerocoinMint(byte[] script) {
		return script.length > 0 && script[0] == OP_ZEROCOIN_MINT;
	}

	public static boolean isZerocoinSpend(byte[] script) {
		return script.length > 0 && script[0] == OP_ZEROCOIN_SPEND;
	}

	private static boolean isPublicKeyHash(byte[] script) {
		return script.length == PUBLIC_KEY_HASH_SCRIPT_SIZE && script[0] == OP_DUP
			&& script[1] == OP_HASH160 && script[2] == Hashing.ADDRESS_HASH_SIZE
			&& script[23] == OP_EQUALVERIFY && script[24] == OP_CHECKSIG;
	}

	private static boolean isPublicKey(byte[] script) {
		return (script.length == COMPRESSED_PUBLIC_KEY_SCRIPT_SIZE
			|| script.length == UNCOMPRESSED_PUBLIC_KEY_SCRIPT_SIZE)
			&& script[0] == script.length - 2 && script[script.length - 1] == OP_CHECKSIG;
	}

	private static boolean isScriptHash(byte[] script) {
		return script.length == SCRIPT_HASH_SCRIPT_SIZE && script[0] == OP_HASH160
			&& script[1] == Hashing.ADDRESS_HASH_SIZE && script[22] == OP_EQUAL;
	}
}
