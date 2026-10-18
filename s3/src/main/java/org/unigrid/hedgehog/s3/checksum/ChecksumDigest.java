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

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.Checksum;

/* Lets a CRC stand in for a digest, written big-endian in as many bytes as it has, which is how S3 encodes it */
final class ChecksumDigest extends MessageDigest {
	private final Checksum checksum;
	private final int length;

	ChecksumDigest(final String algorithm, final Checksum checksum, final int length) {
		super(algorithm);
		this.checksum = checksum;
		this.length = length;
	}

	@Override
	protected void engineUpdate(final byte input) {
		checksum.update(input);
	}

	@Override
	protected void engineUpdate(final byte[] input, final int offset, final int length) {
		checksum.update(input, offset, length);
	}

	@Override
	protected byte[] engineDigest() {
		final byte[] value = ByteBuffer.allocate(Long.BYTES).putLong(checksum.getValue()).array();

		checksum.reset();
		return Arrays.copyOfRange(value, Long.BYTES - length, Long.BYTES);
	}

	@Override
	protected int engineGetDigestLength() {
		return length;
	}

	@Override
	protected void engineReset() {
		checksum.reset();
	}
}
