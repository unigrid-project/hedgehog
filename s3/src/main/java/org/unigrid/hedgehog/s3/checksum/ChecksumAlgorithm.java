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

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import lombok.SneakyThrows;

/* The additional checksums a client may ask S3 to verify and keep, each sent in its own x-amz-checksum header */
public enum ChecksumAlgorithm {
	CRC32(() -> new ChecksumDigest("CRC32", new CRC32(), Integer.BYTES)),
	CRC32C(() -> new ChecksumDigest("CRC32C", new CRC32C(), Integer.BYTES)),
	CRC64NVME(() -> new ChecksumDigest("CRC64NVME", new Crc64Nvme(), Long.BYTES)),
	SHA1(() -> digest("SHA-1")),
	SHA256(() -> digest("SHA-256"));

	private static final String HEADER_PREFIX = "x-amz-checksum-";

	private final Supplier<MessageDigest> digests;

	ChecksumAlgorithm(final Supplier<MessageDigest> digests) {
		this.digests = digests;
	}

	@SneakyThrows
	static MessageDigest digest(final String algorithm) {
		return MessageDigest.getInstance(algorithm);
	}

	public static Optional<ChecksumAlgorithm> of(final String name) {
		return Arrays.stream(values()).filter(algorithm -> algorithm.name().equalsIgnoreCase(name)).findFirst();
	}

	public static Optional<ChecksumAlgorithm> ofHeader(final String header) {
		return Arrays.stream(values()).filter(algorithm -> algorithm.getHeader().equalsIgnoreCase(header))
			.findFirst();
	}

	public String getHeader() {
		return HEADER_PREFIX + name().toLowerCase(Locale.ROOT);
	}

	public MessageDigest digest() {
		return digests.get();
	}
}
