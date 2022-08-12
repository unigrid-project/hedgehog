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
import java.security.NoSuchAlgorithmException;
import org.unigrid.hedgehog.model.Signature;
import picocli.CommandLine.Command;

@Command(name = "key-generate")
public class KeyGenerate implements Runnable {
	@Override
	public void run() {
		try {
			final Signature sig = new Signature();

			System.out.println("Private Key: " + sig.getPrivateKey());
			System.out.println("Public Key: " + sig.getPublicKey());

		} catch (InvalidAlgorithmParameterException | NoSuchAlgorithmException ex) {
			System.err.println(String.format("Failed to generate signature: %s", ex));
		}
	}
}
