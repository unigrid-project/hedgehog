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

package org.unigrid.hedgehog.model.network;

import io.netty.util.AttributeKey;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
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
		GRIDNODE((short) 2030),
		STORE_FRAGMENT((short) 3000), FETCH_FRAGMENT((short) 3010), FRAGMENT_REPLY((short) 3020),
		HAS_FRAGMENT((short) 3030), FRAGMENT_STATUS((short) 3040), DELETE_GROUP((short) 3050),
		STORAGE_ACK((short) 3060);

		private static final Map<Short, Type> BY_VALUE = Arrays.stream(values())
			.collect(Collectors.toMap(Type::getValue, type -> type));

		@Getter private final short value;

		public static Type get(final short value) {
			return BY_VALUE.getOrDefault(value, UNDEFINED);
		}
	}
}
