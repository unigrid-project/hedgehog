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

package org.unigrid.hedgehog.proxy.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.WriterKey;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

final class SessionFixtures {
	static final long NOW = 1_750_000_000_000L;
	static final ChainId CHAIN = new ChainId(filled(ChainId.SIZE, 7));

	private SessionFixtures() {
		/* Static helpers only */
	}

	static byte[] filled(int size, int value) {
		final byte[] bytes = new byte[size];

		Arrays.fill(bytes, (byte) value);
		return bytes;
	}

	static Fingerprint fingerprint(int value) {
		return new Fingerprint(filled(Fingerprint.SIZE, value));
	}

	static WriterKey key(int number) {
		return new WriterKey(filled(WriterKey.SEED_SIZE, number));
	}

	/* The private sealing key of a gridnode, drawn from its id so that any test can rebuild it */
	static byte[] sealingPrivate(String gridnodeId) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(("sealing " + gridnodeId)
				.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is mandatory on every Java platform", ex);
		}
	}

	static byte[] sealingPublic(String gridnodeId) {
		return new X25519PrivateKeyParameters(sealingPrivate(gridnodeId)).generatePublicKey().getEncoded();
	}

	static ExitCandidate exit(String gridnodeId) {
		return new ExitCandidate(gridnodeId, sealingPublic(gridnodeId));
	}

	/* The context of writer number on CHAIN, posting every entry to the given list */
	static SessionContext context(int number, List<Entry> posted, LongSupplier clock) {
		return new SessionContext(new FeedWriter(key(number), CHAIN), posted::add, clock, new SecureRandom(),
			ProxySettings.DEFAULTS);
	}
}
