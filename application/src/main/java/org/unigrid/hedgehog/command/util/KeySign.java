/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

	package org.unigrid.hedgehog.command.util;

	import java.security.InvalidAlgorithmParameterException;
	import java.security.NoSuchAlgorithmException;
	import java.security.spec.InvalidKeySpecException;
	import java.util.Optional;
	
	import lombok.Getter;
	import org.apache.commons.codec.DecoderException;
	import org.apache.commons.codec.binary.Hex;
	import org.unigrid.hedgehog.model.crypto.Signature;
	import org.unigrid.hedgehog.model.crypto.SigningException;
	import picocli.CommandLine.Command;
	import picocli.CommandLine.Option;
	
	@Command(name = "key-sign", description = "Signs hex-encoded data with a private key")
	public class KeySign implements Runnable {
	
		@Getter
		@Option(
			names = { "-D", "--data" },
			description = "Hex representation of data to sign",
			required = true
		)
		private String data;
	
		@Getter
		@Option(
			names = { "-k", "--key" },
			description = "Hex representation of private key to use",
			required = true
		)
		private String key;
	
		@Override
		public void run() {
			try {
				Signature signature = new Signature(
					Optional.of(key),
					Optional.empty()
				);
	
				byte[] signed = signature.sign(Hex.decodeHex(data));
				System.out.println(Hex.encodeHexString(signed));
	
			} catch (DecoderException |
					 InvalidAlgorithmParameterException |
					 InvalidKeySpecException |
					 NoSuchAlgorithmException |
					 SigningException ex) {  // <-- Lägg till SigningException här
				System.err.printf("Failed to sign: %s%n", ex.getMessage());
			}
		}
	}
	