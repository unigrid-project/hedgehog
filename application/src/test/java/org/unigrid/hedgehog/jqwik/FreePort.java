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

package org.unigrid.hedgehog.jqwik;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/* A port that the system picked and that nothing is bound to, for TCP or for UDP, on any address. It is held for both
   until it is closed, so ports that are held at once differ. A server binds it after that, and nothing keeps another
   process from being given it in between: the system picks among thousands of ports, so that is rare. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class FreePort implements AutoCloseable {
	private final ServerSocket tcp;
	private final DatagramSocket udp;

	/* The system picks a port that is free for TCP, and is asked for another when something holds that one for UDP */
	public static FreePort hold() throws IOException {
		final ServerSocket tcp = new ServerSocket(0);

		try {
			return new FreePort(tcp, new DatagramSocket(tcp.getLocalPort()));
		} catch (BindException ex) {
			tcp.close();
			return hold();
		}
	}

	public int getNumber() {
		return tcp.getLocalPort();
	}

	@Override
	public void close() throws IOException {
		udp.close();
		tcp.close();
	}
}
