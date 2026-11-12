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
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class FreePortTest {
	/* The servers of a node each get a port, and the ports are found before any of the servers binds one */
	@Example
	public void shouldHoldPortsThatDiffer() throws IOException {
		try (FreePort first = FreePort.hold(); FreePort second = FreePort.hold(); FreePort third = FreePort.hold()) {
			assertThat(first.getNumber(), is(not(equalTo(second.getNumber()))));
			assertThat(first.getNumber(), is(not(equalTo(third.getNumber()))));
			assertThat(second.getNumber(), is(not(equalTo(third.getNumber()))));
		}
	}

	/* The P2P server binds its port for UDP and the REST server binds its port for TCP */
	@Example
	public void shouldHoldThePortForTcpAndForUdpUntilItIsClosed() throws IOException {
		final int port;

		try (FreePort held = FreePort.hold()) {
			port = held.getNumber();

			assertThrows(BindException.class, () -> new ServerSocket(port).close());
			assertThrows(BindException.class, () -> new DatagramSocket(port).close());
		}

		try (ServerSocket tcp = new ServerSocket(port); DatagramSocket udp = new DatagramSocket(port)) {
			assertThat(tcp.getLocalPort(), equalTo(port));
			assertThat(udp.getLocalPort(), equalTo(port));
		}
	}
}
