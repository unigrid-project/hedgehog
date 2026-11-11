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

import java.lang.reflect.Field;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.commons.lang3.mutable.MutableInt;

/* The keys given are checked against through stand-ins that count how often they are written out as text, which is a
   dump of the point over several lines, and which a check that fails builds into the message of its exception. The
   keys that Signature keeps for the checks are put back when this is closed. */
public final class CountedKeys implements AutoCloseable {
	private final Map<String, ECPublicKey> verified;
	private final Map<String, ECPublicKey> before;
	@Getter private final MutableInt written = new MutableInt();
	@Getter private final Map<String, ECPublicKey> real = new HashMap<>();

	@SneakyThrows
	@SuppressWarnings("unchecked")
	public CountedKeys(Collection<Signature> signers) {
		verified = (Map<String, ECPublicKey>) valueOf(null, "VERIFIED_KEYS");
		before = new HashMap<>(verified);

		for (Signature signer : signers) {
			real.put(signer.getPublicKey(), (ECPublicKey) valueOf(signer, "publicKey"));
			verified.put(signer.getPublicKey(), new Counted(real.get(signer.getPublicKey())));
		}
	}

	/* A field of the signature class, which the package does not open to the libraries that read fields */
	private static Object valueOf(Signature instance, String name) throws ReflectiveOperationException {
		final Field field = Signature.class.getDeclaredField(name);

		field.setAccessible(true);
		return field.get(instance);
	}

	@Override
	public void close() {
		verified.clear();
		verified.putAll(before);
	}

	private final class Counted implements ECPublicKey {
		private final ECPublicKey key;

		private Counted(ECPublicKey key) {
			this.key = key;
		}

		@Override public ECPoint getW() {
			return key.getW();
		}

		@Override public ECParameterSpec getParams() {
			return key.getParams();
		}

		@Override public String getAlgorithm() {
			return key.getAlgorithm();
		}

		@Override public String getFormat() {
			return key.getFormat();
		}

		@Override public byte[] getEncoded() {
			return key.getEncoded();
		}

		@Override public String toString() {
			written.increment();
			return key.toString();
		}
	}
}
