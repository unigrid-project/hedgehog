/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.network.packet;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class AskNodeDetails extends Packet implements Serializable {
	@Builder.Default private boolean protocol = true;
	@Builder.Default private boolean version = true;

	public AskNodeDetails() {
		setType(Type.ASK_NODE_DETAILS);
	}

	@RequiredArgsConstructor
	public enum Flags {
		PROTOCOL(0x01), VERSION(0x02);

		@Getter private final int mask;

		public boolean isSet(int flags) {
			return (flags & mask) == mask;
		}
	}
}
