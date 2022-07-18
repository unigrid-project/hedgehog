/*
    Unigrid Hedgehog 
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

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
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.util.Optional;
import lombok.Getter;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.unigrid.hedgehog.model.Signature;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "key-sign")
public class KeySign implements Runnable {
	@Getter @Option(names = { "-D", "--data" }, description = "Hex representation of data to sign.", required = true)
	private String data;

	@Getter @Option(names = { "-k", "--key" }, required = true, description = "Hex representation of private key to use.")
	private String key;

	@Override
	public void run() {
		try {
			final Signature signature = new Signature(Optional.of(key), Optional.empty());
			System.out.println(Hex.encodeHexString(signature.sign(Hex.decodeHex(data))));

		} catch (DecoderException | InvalidAlgorithmParameterException | InvalidKeyException | InvalidKeySpecException
			| NoSuchAlgorithmException | SignatureException ex) {

			System.err.println(String.format("Failed to sign: %s", ex));
		}
		
	}
}
