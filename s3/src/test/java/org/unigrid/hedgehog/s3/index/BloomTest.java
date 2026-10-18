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

package org.unigrid.hedgehog.s3.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

public class BloomTest {
	private static final int BITS_PER_KEY = 10;
	private static final int KEYS = 10_000;

	@Provide
	Arbitrary<Set<byte[]>> keySets() {
		return Arbitraries.bytes().array(byte[].class).ofMaxSize(16).set().ofMaxSize(200);
	}

	private static Bloom bloom(final Set<byte[]> keys, final int bitsPerKey) {
		final Bloom bloom = Bloom.forKeys(keys.size(), bitsPerKey);

		keys.forEach(bloom::add);
		return bloom;
	}

	@Property
	public void neverMissesAKeyItWasGiven(@ForAll("keySets") final Set<byte[]> keys,
		@ForAll @IntRange(min = 1, max = 20) final int bitsPerKey) {

		final Bloom bloom = bloom(keys, bitsPerKey);

		keys.forEach(key -> assertThat(bloom.mightContain(key), is(true)));
	}

	@Property
	public void answersTheSameOnceReadBack(@ForAll("keySets") final Set<byte[]> keys,
		@ForAll("keySets") final Set<byte[]> probes) throws IOException {

		final Bloom bloom = bloom(keys, BITS_PER_KEY);
		final Bloom read = Codec.decode(Codec.encode(bloom::write), Bloom::read);

		probes.forEach(probe -> assertThat(read.mightContain(probe), equalTo(bloom.mightContain(probe))));
		keys.forEach(key -> assertThat(read.mightContain(key), is(true)));
	}

	@Example
	public void letsFewStrangersThrough() {
		final Bloom bloom = Bloom.forKeys(KEYS, BITS_PER_KEY);

		for (int i = 0; i < KEYS; i++) {
			bloom.add(("member-" + i).getBytes(StandardCharsets.UTF_8));
		}

		final long strangers = IntStream.range(0, KEYS)
			.filter(i -> bloom.mightContain(("stranger-" + i).getBytes(StandardCharsets.UTF_8))).count();

		assertThat(strangers, lessThan(KEYS / 50L));
	}
}
