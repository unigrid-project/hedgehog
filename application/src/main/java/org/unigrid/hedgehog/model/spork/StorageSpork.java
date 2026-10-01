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

package org.unigrid.hedgehog.model.spork;

import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;
import org.unigrid.hedgehog.model.storage.LayoutParameters;

@Data @ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class StorageSpork extends GridSpork implements Serializable {
	public StorageSpork() {
		setType(Type.STORAGE);
		setData(new StorageSpork.SporkData());
	}

	@Data
	public static class SporkData implements ChunkData {
		public static final int MAX_MANIFEST_COPIES = 16;
		private static final int MAX_EXTRA_POOL_PERCENT = 90;
		private static final int PERCENT = 100;

		/* Bounded by their wire fields, so the spork a signer validated is the one its peers decode */
		private static final int MAX_PLACEMENT_SLACK = 0xFF;
		private static final int MAX_TOMBSTONE_DAYS = 0xFFFF;

		private long maxBytesPerNode = 10_737_418_240L;
		private int chunkSize = 1_048_576;
		private int fragmentSize = 65_536;
		private int outerParityPercent = 50;
		private int maxOuterDataChunks = 32;
		private int innerParityPercent = 50;
		private int maxParityPercent = 100;
		private int repairIntervalMinutes = 60;
		private int tombstoneDays = 30;
		private int manifestCopies = 3;
		private int placementSlack = 8;
		private int repairThresholdPercent = 50;
		private int extraPoolPercent = 20;

		@Override
		public SporkData empty() {
			return new SporkData();
		}

		public LayoutParameters layout() {
			return LayoutParameters.builder().chunkSize(chunkSize).fragmentSize(fragmentSize)
				.outerParityPercent(outerParityPercent).maxOuterDataChunks(maxOuterDataChunks)
				.innerParityPercent(innerParityPercent).maxParityPercent(maxParityPercent).build();
		}

		public int window() {
			return layout().maxFragments() + placementSlack;
		}

		public void validate() {
			layout().validate();
			LayoutParameters.require(LayoutParameters.inRange(manifestCopies, 1, MAX_MANIFEST_COPIES),
				"manifestCopies must be 1-16");
			LayoutParameters.require(LayoutParameters.inRange(repairThresholdPercent, 1, PERCENT),
				"repairThresholdPercent must be 1-100");
			LayoutParameters.require(LayoutParameters.inRange(extraPoolPercent, 0, MAX_EXTRA_POOL_PERCENT),
				"extraPoolPercent must be 0-90");
			LayoutParameters.require(repairIntervalMinutes >= 1, "repairIntervalMinutes must be positive");
			LayoutParameters.require(LayoutParameters.inRange(placementSlack, 0, MAX_PLACEMENT_SLACK),
				"placementSlack must be 0-255");
			LayoutParameters.require(LayoutParameters.inRange(tombstoneDays, 1, MAX_TOMBSTONE_DAYS),
				"tombstoneDays must be 1-65535");
			LayoutParameters.require(maxBytesPerNode >= 0, "maxBytesPerNode cannot be negative");
		}
	}
}
