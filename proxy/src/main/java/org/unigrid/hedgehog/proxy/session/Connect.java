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

package org.unigrid.hedgehog.proxy.session;

import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.util.Objects;

/* The client asks the exit to reach a destination: CONNECT for TCP, UDP_ASSOCIATE for datagrams */
public record Connect(Socks5CommandType command, SocksAddress destination) implements SealedMessage {
	public Connect {
		Objects.requireNonNull(destination, "destination");

		if (!Socks5CommandType.CONNECT.equals(command) && !Socks5CommandType.UDP_ASSOCIATE.equals(command)) {
			throw new IllegalArgumentException("A session carries CONNECT or UDP_ASSOCIATE, found " + command);
		}
	}
}
