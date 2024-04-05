/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

public class FingerprintKeysTest {
	@Property(tries = 20)
	public void isDeterministic() {
		final Fingerprint fingerprint = Fingerprint.generate(new SecureRandom());
		final FingerprintKeys first = new FingerprintKeys(fingerprint);
		final FingerprintKeys second = new FingerprintKeys(Fingerprint.parse(fingerprint.encode()));

		assertThat(second.chunkKey(), equalTo(first.chunkKey()));
		assertThat(second.chunkSeed(3, 7), equalTo(first.chunkSeed(3, 7)));
	}

	@Property(tries = 20)
	public void derivesUnrelatedValuesPerLabel() {
		final FingerprintKeys keys = new FingerprintKeys(Fingerprint.generate(new SecureRandom()));
		final List<byte[]> derived = List.of(keys.chunkKey(), keys.manifestKey(), keys.manifestSeed(0),
			keys.manifestSeed(1), keys.chunkSeed(0, 0), keys.chunkSeed(0, 1), keys.chunkSeed(1, 0)
		);
		final Set<String> distinct = new HashSet<>();

		derived.forEach(value -> distinct.add(HexFormat.of().formatHex(value)));
		assertThat(distinct, hasSize(derived.size()));
		derived.forEach(value -> assertThat(value.length, equalTo(FingerprintKeys.KEY_SIZE)));
	}
}
