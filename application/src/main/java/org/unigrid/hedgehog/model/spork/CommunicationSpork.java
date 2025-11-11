/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

@Data @ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class CommunicationSpork extends GridSpork implements Serializable {
	public CommunicationSpork() {
		setType(Type.COMMUNICATION);
		setData(new CommunicationSpork.SporkData());
	}

	@Data
	public static class SporkData implements ChunkData {
		/* The signature covers the serialised class, so its version must not change when members are added */
		private static final long serialVersionUID = 1L;

		private int groupSize = 8;
		private long reorganizePeriodSeconds = 3600;
		private int chainsPerGroup = 4;
		private int clientsPerChain = 16;
		private long chainCapacityBytes = 32L * 1024 * 1024;
		private int retentionSeconds = 30;
		private long maxEntryBytes = 16L * 1024;
		private int sessionIdleSeconds = 300;

		@Override
		public SporkData empty() {
			return new SporkData();
		}
	}
}
