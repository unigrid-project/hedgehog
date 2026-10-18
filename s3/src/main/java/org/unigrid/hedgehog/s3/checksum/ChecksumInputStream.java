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

package org.unigrid.hedgehog.s3.checksum;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/*
   Digests what is read through it: always MD5, which is the ETag of a single-part object, plus the checksums asked
   for. The check at the end runs inside the read that reaches the end, so a reader storing the stream fails before
   it keeps a body the check refuses.
*/
public class ChecksumInputStream extends FilterInputStream {
	private static final int SKIP_BUFFER = 8192;

	private final MessageDigest md5 = ChecksumAlgorithm.digest("MD5");
	private final Map<ChecksumAlgorithm, MessageDigest> digests = new EnumMap<>(ChecksumAlgorithm.class);
	private final Map<ChecksumAlgorithm, byte[]> results = new EnumMap<>(ChecksumAlgorithm.class);
	private final Consumer<? super ChecksumInputStream> check;

	private long count;
	private byte[] md5Result;
	private RuntimeException refusal;

	public ChecksumInputStream(final InputStream input, final Collection<ChecksumAlgorithm> algorithms,
		final Consumer<? super ChecksumInputStream> check) {

		super(input);
		this.check = check;
		algorithms.forEach(algorithm -> digests.put(algorithm, algorithm.digest()));
	}

	@Override
	public int read() throws IOException {
		final byte[] single = new byte[1];

		return read(single, 0, 1) < 0 ? -1 : single[0] & 0xFF;
	}

	@Override
	public int read(final byte[] buffer, final int offset, final int length) throws IOException {
		final int read = isEnded() ? -1 : super.read(buffer, offset, length);

		if (read > 0) {
			count += read;
			md5.update(buffer, offset, read);
			digests.values().forEach(digest -> digest.update(buffer, offset, read));
		} else if (read < 0) {
			end();
		}

		return read;
	}

	@Override
	public long skip(final long length) throws IOException {
		return Math.max(0, read(new byte[(int) Math.clamp(length, 0, SKIP_BUFFER)]));
	}

	@Override
	public boolean markSupported() {
		return false;
	}

	private void end() {
		if (!isEnded()) {
			md5Result = md5.digest();
			digests.forEach((algorithm, digest) -> results.put(algorithm, digest.digest()));

			try {
				check.accept(this);
			} catch (RuntimeException ex) {
				refusal = ex;
			}
		}

		if (refusal != null) {
			throw refusal;
		}
	}

	public boolean isEnded() {
		return md5Result != null;
	}

	public long count() {
		return count;
	}

	public byte[] md5() {
		ensureEnded();
		return md5Result.clone();
	}

	public byte[] digest(final ChecksumAlgorithm algorithm) {
		ensureEnded();
		return results.get(algorithm).clone();
	}

	private void ensureEnded() {
		if (!isEnded()) {
			throw new IllegalStateException("The stream has not been read to its end");
		}
	}
}
