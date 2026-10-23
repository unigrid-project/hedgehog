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

package org.unigrid.hedgehog.model.spork;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/* The board keys that sign sporks, as a chain of changes from the keys built into the release (BoardKeyChain). An
   empty chain leaves the built-in keys active. */
@Data @ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class BoardKeys extends GridSpork implements Serializable {
	private static final long serialVersionUID = -2858013573021957746L;

	public BoardKeys() {
		setType(Type.BOARD_KEYS);
		setFlags((short) (getFlags() | Flag.GOVERNED.getValue()));
		setData(new SporkData());
	}

	@Data
	public static class SporkData implements ChunkData {
		private static final long serialVersionUID = 6815529384750267513L;

		private List<KeyChange> changes = new ArrayList<>();

		@Override
		public SporkData empty() {
			return new SporkData();
		}
	}
}
