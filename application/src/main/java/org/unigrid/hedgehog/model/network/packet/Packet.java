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

package org.unigrid.hedgehog.model.network.packet;

import io.netty.util.AttributeKey;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;

/*
    Packet format:
    0.............................63.............................127
    [ type ][resrvd][ packet size  ][          reserved            ]
    [                   << packet specific data >>                 ]
*/
@Data
@SuppressWarnings("checkstyle:CyclomaticComplexity") // TODO: Expand more before fixing and removing this
public class Packet {
	public static final AttributeKey<Type> KEY = AttributeKey.valueOf(Packet.class.getSimpleName());
	private Type type;

	@AllArgsConstructor
	public enum Type {
		UNDEFINED((short) 0),
		HELLO((short) 250),
		PING((short) 500),
		ASK_PEERS((short) 1000), PUBLISH_PEERS((short) 1010),
		ASK_NODE_DETAILS((short) 1100), PUBLISH_NODE_DETAILS((short) 1110),
		ASK_SPORKS((short) 2000), GROW_SPORK((short) 2010), PUBLISH_SPORK((short) 2020),
		GRIDNODE((short) 2030);

		@Getter private final short value;

		public static Type get(short value) {
			switch (value) {
				case 250: return HELLO;
				case 500: return PING;
				case 1000: return ASK_PEERS;
				case 1010: return PUBLISH_PEERS;
				case 1100: return ASK_NODE_DETAILS;
				case 1110: return PUBLISH_NODE_DETAILS;
				case 2000: return ASK_SPORKS;
				case 2010: return GROW_SPORK;
				case 2020: return PUBLISH_SPORK;
				case 2030: return GRIDNODE;
				default: return UNDEFINED;
			}
		}
	}
}
