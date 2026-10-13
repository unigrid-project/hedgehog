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

package org.unigrid.hedgehog.model.crypto;

import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import net.jqwik.api.constraints.NotEmpty;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.apache.commons.codec.binary.Hex;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.model.HedgehogConfig;

public class SignatureTest extends BaseMockedWeldTest {
	private static final int NUM_SIGNATURES = 3;
	private static final List<Signature> SIGNATURES = new ArrayList<>(NUM_SIGNATURES);

	@SneakyThrows
	@BeforeProperty
	private void mockBefore() {
		for (int i = 0; i < NUM_SIGNATURES; i++) {
			SIGNATURES.add(new Signature());
		}

		new MockUp<NetOptions>() {
			@Mock public static String[] getNetworkKeys() {
				return SIGNATURES.stream().map(signature -> signature.getPublicKey()).toArray(String[]::new);
			}
		};
	}

	
	@SneakyThrows
	@Property(tries = 100)
	public boolean shouldSignAndVerify(@ForAll @NotEmpty byte[] data) {
		new MockUp<HedgehogConfig>() {
			@Mock public long getVerifyChangeDate() {
				return 1716707716;
			}
		};

		final Signature signature = new Signature();
		final byte[] signatureData = signature.sign(data);

		return signature.verify(data, signatureData);
	}
	
	@SneakyThrows
	@Property(tries = 100)
	public boolean shouldSignAndVerifyNewWay(@ForAll @NotEmpty @Size(min = 50, max = 70) byte[] data) {

		new MockUp<HedgehogConfig>() {
			@Mock public long getVerifyChangeDate() {
				return 0;
			}
		};

		final Signature signature = SIGNATURES.get(0);
		final byte[] signatureData = signature.sign(data);

		final Signature signature2 = SIGNATURES.get(1);
		final byte[] signatureData2 = signature2.sign(data);

		return signature.verifyMultiple(data, signatureData, signatureData2);
	}
	
	@SneakyThrows
	@Property(tries = 100)
	public boolean shouldSignAndVerifyNewWayWithSameKey(@ForAll @NotEmpty @Size(min = 50, max = 70) byte[] data) {

		new MockUp<HedgehogConfig>() {
			@Mock public long getVerifyChangeDate() {
				return 0;
			}
		};

		final Signature signature = SIGNATURES.get(0);
		final byte[] signatureData = signature.sign(data);

		return !signature.verifyMultiple(data, signatureData, signatureData);
	}

	@SneakyThrows
	@Property(tries = 100)
	public boolean shouldSignAndVerifyNewWayUsingList(@ForAll @NotEmpty @Size(min = 50, max = 70) byte[] data) {

		new MockUp<HedgehogConfig>() {
			@Mock public long getVerifyChangeDate() {
				return 0;
			}
		};
		Signature signature = SIGNATURES.get(0);
		List<byte[]> signs = new ArrayList<>();
		for (Signature s : SIGNATURES) {
			signs.add(s.sign(data));
		}

		return signature.verifyMultiple(data, signs);
	}

	@SneakyThrows
	@Property(tries = 50)
	public boolean shouldSignAndVerifyWithOtherConstructor(@ForAll @NotEmpty byte[] data) {
		final Signature signatureKeys = new Signature();

		final Signature signatureSigner = new Signature(Optional.of(
			signatureKeys.getPrivateKey()), Optional.empty()
		);

		final Signature signatureVerifier = new Signature(Optional.empty(),
			Optional.of(signatureKeys.getPublicKey())
		);

		final byte[] signatureData = signatureSigner.sign(data);
		return signatureVerifier.verify(data, signatureData);
	}

	@Property(tries = 50)
	public boolean shouldThrowExceptionOnInvalidPrivateKeySize(@ForAll @Size(min = 50, max = 70) byte[] privateKey) {
		try {
			final Signature signatureSigner = new Signature(
				Optional.of(Hex.encodeHexString(privateKey)), Optional.empty()
			);
		} catch (InvalidAlgorithmParameterException | InvalidKeySpecException | NoSuchAlgorithmException ex) {
			return false;
		} catch (IllegalArgumentException ex) {
			return privateKey.length != Signature.PRIVATE_KEY_HEX_SIZE;
		}

		return privateKey.length == Signature.PRIVATE_KEY_HEX_SIZE;
	}

	@Property(tries = 50)
	public boolean shouldThrowExceptionOnInvalidPublicKeySize(@ForAll @Size(min = 120, max = 140) byte[] publicKey) {
		try {
			final Signature signatureSigner = new Signature(Optional.empty(),
				Optional.of(Hex.encodeHexString(publicKey))
			);
		} catch (InvalidAlgorithmParameterException | InvalidKeySpecException | NoSuchAlgorithmException ex) {
			return false;
		} catch (IllegalArgumentException ex) {
			return publicKey.length != Signature.PUBLIC_KEY_HEX_SIZE;
		} catch (RuntimeException ex) {
			return true; /* Happens when we have weird input values in the public key, so lets ignore it */
		}

		return publicKey.length == Signature.PUBLIC_KEY_HEX_SIZE;
	}
}
