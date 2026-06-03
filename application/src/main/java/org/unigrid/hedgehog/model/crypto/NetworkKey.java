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

package org.unigrid.hedgehog.model.crypto;

import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.unigrid.hedgehog.command.option.NetOptions;

@Slf4j
public class NetworkKey {
	public static String[] getPublicKeys() {
		return NetOptions.getNetworkKeys();
	}

	public static boolean isTrusted(String privateKey) {
		try {
			if (RandomSignableData.create(privateKey).isValidSignature()) {
				return true;
			}
		} catch (SigningException  ex) {
			log.atTrace().log(ex.getMessage());
		}

		return false;
	}

	@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
	private static class RandomSignableData implements Signable {
		private static final int SIZE = 32;
		@Getter private byte[] signable;
		@Getter private byte[] signature;

		public static RandomSignableData create(String privateKeyHex) throws SigningException {
			final RandomSignableData randomSignableData = new RandomSignableData();

			randomSignableData.sign(privateKeyHex);
			return randomSignableData;
		}

		@Override
		public boolean isValidSignature() {
			try {
				for (String key : getPublicKeys()) {
					if (Signature.verify(this, key)) {
						return true;
					}
				}
			} catch (VerifySignatureException ex) {
				log.atTrace().log("{}:{}", ex.getMessage(), ExceptionUtils.getStackTrace(ex));
			}

			return false;
		}

		@Override
		public void sign(String privateKeyHex) throws SigningException {
			signable = RandomUtils.nextBytes(SIZE);

			try {
				final Signature signature = new Signature(Optional.of(privateKeyHex),
					Optional.empty()
				);

				this.signature = signature.sign(signable);

			} catch (InvalidAlgorithmParameterException | IllegalArgumentException | InvalidKeySpecException
				| NoSuchAlgorithmException  ex) {

				throw new SigningException("Failed to prepare random signable data", ex);
			}
		}
	}
}
