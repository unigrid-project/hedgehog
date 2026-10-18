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
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.s3.spi.BlobId;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;

public class ManifestTest {
	@Provide
	Arbitrary<Manifest> manifests() {
		final Arbitrary<BlobId> ids = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8).map(BlobId::new);
		final Arbitrary<Long> numbers = Arbitraries.longs().greaterOrEqual(0);
		final Arbitrary<RunInfo> runs = Combinators.combine(ids, numbers, numbers, numbers,
			IndexArbitraries.rawKeys(), IndexArbitraries.rawKeys()).as(RunInfo::new);
		final Arbitrary<Manifest.Pending> pending = Combinators.combine(ids, IndexArbitraries.instants())
			.as(Manifest.Pending::new);

		return Combinators.combine(Arbitraries.longs().greaterOrEqual(0), runs.list().ofMaxSize(4),
			pending.list().ofMaxSize(4)).as(Manifest::new);
	}

	@Property
	public void readsBackWhatItWrites(@ForAll("manifests") final Manifest manifest) throws IOException {
		final Manifest read = Manifest.decode(manifest.encode());

		assertThat(read.sequence(), equalTo(manifest.sequence()));
		assertThat(read.pending(), equalTo(manifest.pending()));
		assertThat(read.runs().stream().map(RunInfo::toString).toList(),
			equalTo(manifest.runs().stream().map(RunInfo::toString).toList()));
	}

	@Property
	public void refusesADamagedManifest(@ForAll("manifests") final Manifest manifest, @ForAll final int seed) {
		final byte[] encoding = manifest.encode();
		final int position = Math.floorMod(seed, encoding.length);

		encoding[position] ^= 1 << Math.floorMod(seed >> 8, 8);
		assertThrows(IOException.class, () -> Manifest.decode(encoding));
	}
}
