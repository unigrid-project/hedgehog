/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.storage.crypto;

import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import org.unigrid.hedgehog.model.storage.crypto.Fingerprints.Secrets;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Negative;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class FingerprintKeysTest {
	@Property(tries = 50)
	public void isDeterministic(@ForAll(supplier = Secrets.class) byte[] secret,
		@ForAll @IntRange(min = 0) int stripe, @ForAll @IntRange(max = FingerprintKeys.MAX_POSITION) int index,
		@ForAll @IntRange(max = FingerprintKeys.MAX_POSITION) int copy) {

		final FingerprintKeys first = new FingerprintKeys(Fingerprints.of(secret));
		final FingerprintKeys second = new FingerprintKeys(Fingerprints.of(secret.clone()));

		assertThat(second.chunkKey(), equalTo(first.chunkKey()));
		assertThat(second.manifestKey(), equalTo(first.manifestKey()));
		assertThat(second.chunkSeed(stripe, index), equalTo(first.chunkSeed(stripe, index)));
		assertThat(second.manifestSeed(copy), equalTo(first.manifestSeed(copy)));
	}

	@Property(tries = 50)
	public void derivesUnrelatedValuesPerLabel(@ForAll(supplier = Secrets.class) byte[] secret,
		@ForAll @IntRange(min = 0, max = Integer.MAX_VALUE - 1) int stripe,
		@ForAll @IntRange(max = FingerprintKeys.MAX_POSITION - 1) int index,
		@ForAll @IntRange(max = FingerprintKeys.MAX_POSITION - 1) int copy) {

		final FingerprintKeys keys = new FingerprintKeys(Fingerprints.of(secret));
		final List<byte[]> derived = List.of(keys.chunkKey(), keys.manifestKey(), keys.manifestSeed(copy),
			keys.manifestSeed(copy + 1), keys.chunkSeed(stripe, index), keys.chunkSeed(stripe, index + 1),
			keys.chunkSeed(stripe + 1, index)
		);
		final Set<String> distinct = new HashSet<>();

		derived.forEach(value -> distinct.add(HexFormat.of().formatHex(value)));
		assertThat(distinct, hasSize(derived.size()));
		derived.forEach(value -> assertThat(value.length, equalTo(FingerprintKeys.KEY_SIZE)));
	}

	@Property(tries = 50)
	public void derivesUnrelatedValuesPerFingerprint(@ForAll(supplier = Secrets.class) byte[] secret,
		@ForAll(supplier = Secrets.class) byte[] otherSecret) {

		Assume.that(!Arrays.equals(secret, otherSecret));

		final FingerprintKeys keys = new FingerprintKeys(Fingerprints.of(secret));
		final FingerprintKeys otherKeys = new FingerprintKeys(Fingerprints.of(otherSecret));

		assertThat(otherKeys.chunkKey(), not(equalTo(keys.chunkKey())));
		assertThat(otherKeys.chunkSeed(0, 0), not(equalTo(keys.chunkSeed(0, 0))));
	}

	@Property(tries = 100)
	public void rejectsPositionsOutsideAByte(@ForAll int position) {
		Assume.that(position < 0 || position > FingerprintKeys.MAX_POSITION);

		final FingerprintKeys keys = new FingerprintKeys(Fingerprints.of(Fingerprints.knownSecret()));

		assertThrows(IllegalArgumentException.class, () -> keys.chunkSeed(0, position));
		assertThrows(IllegalArgumentException.class, () -> keys.manifestSeed(position));
	}

	@Property(tries = 50)
	public void rejectsNegativeStripes(@ForAll @Negative int stripe,
		@ForAll @IntRange(max = FingerprintKeys.MAX_POSITION) int index) {

		final FingerprintKeys keys = new FingerprintKeys(Fingerprints.of(Fingerprints.knownSecret()));

		assertThrows(IllegalArgumentException.class, () -> keys.chunkSeed(stripe, index));
	}

	@Example
	public void derivesTheKnownAnswers() {
		final HexFormat hex = HexFormat.of();
		final FingerprintKeys keys = new FingerprintKeys(Fingerprints.of(Fingerprints.knownSecret()));

		assertThat(hex.formatHex(keys.chunkKey()),
			equalTo("185ab30d177b039c080517a81596f20c606d695b27c8e8318f16cda6a0c49cd6")
		);
		assertThat(hex.formatHex(keys.manifestKey()),
			equalTo("7f847735047723b9f2ec6706c5ec1a6ab64400930a6f9e1e36ce03cc5dcb0c47")
		);
		assertThat(hex.formatHex(keys.chunkSeed(1, 2)),
			equalTo("a7ee1eccd458d5cc3e6d13ba0d818e2a0deb9dfd62dce5fc866b84fdcd650095")
		);
		assertThat(hex.formatHex(keys.manifestSeed(0)),
			equalTo("01ae94458d1c3d14f07b56393452f31b4033f5593f4edb92d05747983bf2c023")
		);
	}
}
