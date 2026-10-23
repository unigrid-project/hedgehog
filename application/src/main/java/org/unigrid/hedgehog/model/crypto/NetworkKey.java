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
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.unigrid.hedgehog.command.option.NetOptions;

@Slf4j
public class NetworkKey {
	/* What the chain of the board keys spork that this node holds makes of the board keys. The options are read for as
	   long as nothing is installed, so a network started with keys of its own goes by them until its board changes
	   them. */
	private static final AtomicReference<ActiveKeys> INSTALLED = new AtomicReference<>();

	/* The board keys of the release, or of a private network that gives its own, which the chain of changes to the
	   board keys is rooted in */
	public static String[] getBuiltInKeys() {
		return NetOptions.getNetworkKeys();
	}

	/* One read, so that a decision over two signatures is made against one set of keys */
	public static ActiveKeys getActiveKeys() {
		return Objects.requireNonNullElseGet(INSTALLED.get(), () -> ActiveKeys.of(Arrays.asList(getBuiltInKeys())));
	}

	public static String[] getPublicKeys() {
		return getActiveKeys().active().toArray(String[]::new);
	}

	/* The keys a signature log may name: every key that was ever active, as a version that a key signed before it was
	   switched off is history that stays readable. A log only grows under a head that two active keys signed, so a key
	   that was switched off adds nothing to one. */
	public static Set<String> getLogKeys() {
		final ActiveKeys installed = INSTALLED.get();

		return Objects.isNull(installed) ? new LinkedHashSet<>(Arrays.asList(getPublicKeys())) : installed.known();
	}

	/**
	* Goes by the keys of a chain from now on when it is longer than the one installed, and says whether it is. A chain
	* that a node holds only grows, so a chain no longer than the installed one is one the node has moved past, or a
	* fork of it that came later.
	*/
	public static boolean install(ActiveKeys keys) {
		return INSTALLED.updateAndGet(installed -> keys.length() > lengthOf(installed) ? keys : installed) == keys;
	}

	private static int lengthOf(ActiveKeys installed) {
		return Objects.isNull(installed) ? 0 : installed.length();
	}

	/* A process that starts over goes by the built-in keys again, as each test of a process does */
	public static void reset() {
		INSTALLED.set(null);
	}

	public static Optional<String> signerOf(Signable signable) {
		return signerOf(DigestUtils.sha512(signable.getSignable()), signable.getSignature());
	}

	public static Optional<String> signerOf(byte[] digest, byte[] signature) {
		return signerOf(digest, signature, Arrays.asList(getPublicKeys()));
	}

	public static Optional<String> signerOf(byte[] digest, byte[] signature, Collection<String> keys) {
		return keys.stream().filter(key -> verifies(digest, signature, key)).findFirst();
	}

	private static boolean verifies(byte[] digest, byte[] signature, String key) {
		try {
			return Signature.verifyDigest(digest, signature, key);
		} catch (VerifySignatureException ex) {
			log.atTrace().log("{}:{}", ex.getMessage(), ExceptionUtils.getStackTrace(ex));
			return false;
		}
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

	/* Whether the private key is that of one of the public keys given */
	public static boolean isOneOf(String privateKey, Collection<String> publicKeys) {
		try {
			final RandomSignableData signed = RandomSignableData.create(privateKey);
			final byte[] digest = DigestUtils.sha512(signed.getSignable());

			return signerOf(digest, signed.getSignature(), publicKeys).isPresent();
		} catch (SigningException ex) {
			log.atTrace().log(ex.getMessage());
			return false;
		}
	}

	@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
	private static final class RandomSignableData implements Signable {
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
