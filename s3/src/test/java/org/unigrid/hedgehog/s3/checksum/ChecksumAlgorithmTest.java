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

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import software.amazon.awssdk.checksums.DefaultChecksumAlgorithm;
import software.amazon.awssdk.checksums.SdkChecksum;

public class ChecksumAlgorithmTest {
	private static final byte[] CHECK = "123456789".getBytes(StandardCharsets.US_ASCII);

	/* CRC64NVME is left out because the SDK only computes it through its native runtime */
	private static final Map<ChecksumAlgorithm, software.amazon.awssdk.checksums.spi.ChecksumAlgorithm> SDK = Map.of(
		ChecksumAlgorithm.CRC32, DefaultChecksumAlgorithm.CRC32,
		ChecksumAlgorithm.CRC32C, DefaultChecksumAlgorithm.CRC32C,
		ChecksumAlgorithm.SHA1, DefaultChecksumAlgorithm.SHA1,
		ChecksumAlgorithm.SHA256, DefaultChecksumAlgorithm.SHA256
	);

	private static String hex(final ChecksumAlgorithm algorithm, final byte[] data) {
		return HexFormat.of().formatHex(algorithm.digest().digest(data));
	}

	@Provide
	public Arbitrary<ChecksumAlgorithm> provideSdkAlgorithm() {
		return Arbitraries.of(SDK.keySet());
	}

	@Example
	public void matchesTheCheckValuesOfEachAlgorithm() {
		assertThat(hex(ChecksumAlgorithm.CRC32, CHECK), equalTo("cbf43926"));
		assertThat(hex(ChecksumAlgorithm.CRC32C, CHECK), equalTo("e3069283"));
		assertThat(hex(ChecksumAlgorithm.CRC64NVME, CHECK), equalTo("ae8b14860a799888"));
		assertThat(hex(ChecksumAlgorithm.SHA1, CHECK), equalTo("f7c3bc1d808e04732adf679965ccc34ca7ae3441"));
		assertThat(hex(ChecksumAlgorithm.SHA256, CHECK),
			equalTo("15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225")
		);
	}

	@Property
	public void agreesWithTheSdk(@ForAll("provideSdkAlgorithm") ChecksumAlgorithm algorithm, @ForAll byte[] data) {
		final SdkChecksum expected = SdkChecksum.forAlgorithm(SDK.get(algorithm));

		expected.update(data, 0, data.length);
		assertThat(algorithm.digest().digest(data), equalTo(expected.getChecksumBytes()));
	}

	@Property
	public void isFoundByItsNameAndItsHeader(@ForAll ChecksumAlgorithm algorithm, @ForAll boolean upperCase) {
		final String name = upperCase ? algorithm.name() : algorithm.name().toLowerCase(Locale.ROOT);

		assertThat(ChecksumAlgorithm.of(name), equalTo(Optional.of(algorithm)));
		assertThat(ChecksumAlgorithm.ofHeader("x-amz-checksum-" + name), equalTo(Optional.of(algorithm)));
		assertThat(algorithm.getHeader(), equalTo("x-amz-checksum-" + algorithm.name().toLowerCase(Locale.ROOT)));
	}

	@Example
	public void knowsNoOtherAlgorithm() {
		assertThat(ChecksumAlgorithm.of("MD5"), equalTo(Optional.empty()));
		assertThat(ChecksumAlgorithm.ofHeader("x-amz-checksum-type"), equalTo(Optional.empty()));
		assertThat(ChecksumAlgorithm.ofHeader("content-md5"), equalTo(Optional.empty()));
	}
}
