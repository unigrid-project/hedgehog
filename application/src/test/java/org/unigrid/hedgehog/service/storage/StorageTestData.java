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

package org.unigrid.hedgehog.service.storage;

import java.util.List;
import java.util.Random;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.ChunkGroups;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;

public final class StorageTestData {
	private StorageTestData() {
		/* Static helpers only */
	}

	public static StorageSpork.SporkData parameters() {
		final StorageSpork.SporkData data = new StorageSpork.SporkData();

		data.setChunkSize(1024);
		data.setFragmentSize(128);
		data.setOuterParityPercent(50);
		data.setMaxOuterDataChunks(4);
		data.setInnerParityPercent(50);
		data.setMaxParityPercent(100);
		data.setPlacementSlack(4);
		data.setManifestCopies(2);
		data.setRepairThresholdPercent(50);
		data.setExtraPoolPercent(50);
		data.setMaxBytesPerNode(1L << 24);
		data.setRepairIntervalMinutes(1);
		data.setTombstoneDays(1);
		return data;
	}

	public static GroupKey key(final Random random) {
		final byte[] seed = new byte[GroupKey.SEED_SIZE];
		random.nextBytes(seed);
		return new GroupKey(seed);
	}

	public static List<Fragment> group(final GroupKey key, final Random random) {
		final byte[] chunk = new byte[parameters().getChunkSize()];
		random.nextBytes(chunk);
		return ChunkGroups.seal(chunk, key, StorageFormat.current(), parameters().layout());
	}
}
