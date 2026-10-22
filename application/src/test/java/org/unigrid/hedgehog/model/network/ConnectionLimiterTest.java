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

import java.net.InetAddress;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class ConnectionLimiterTest {
	private static final InetAddress PEER = InetAddress.ofLiteral("192.0.2.7");
	private static final InetAddress OTHER = InetAddress.ofLiteral("2001:db8::7");

	private static void admitAll(ConnectionLimiter limiter, InetAddress address) {
		IntStream.range(0, ConnectionLimiter.MAX_PER_ADDRESS).forEach(n -> assertThat(limiter.admit(address), is(true)));
	}

	@Example
	public void shouldRefuseAnAddressOneConnectionAboveItsShare() {
		final ConnectionLimiter limiter = new ConnectionLimiter();

		admitAll(limiter, PEER);

		assertThat(limiter.admit(PEER), is(false));
		assertThat(limiter.held(PEER), equalTo(ConnectionLimiter.MAX_PER_ADDRESS));
		assertThat(limiter.admit(OTHER), is(true));
	}

	@Example
	public void shouldLetAnAddressInAgainOnceAConnectionHasClosed() {
		final ConnectionLimiter limiter = new ConnectionLimiter();

		admitAll(limiter, PEER);
		limiter.release(PEER);

		assertThat(limiter.admit(PEER), is(true));
	}

	@Example
	public void shouldForgetAnAddressWithNoConnectionLeft() {
		final ConnectionLimiter limiter = new ConnectionLimiter();

		limiter.admit(PEER);
		limiter.release(PEER);
		limiter.release(PEER);

		assertThat(limiter.held(PEER), equalTo(0));
	}

	/* A forwarder on this host hands every connection over from the loopback address */
	@Example
	public void shouldLeaveTheLoopbackAddressAlone() {
		final ConnectionLimiter limiter = new ConnectionLimiter();
		final InetAddress loopback = InetAddress.getLoopbackAddress();

		IntStream.rangeClosed(0, ConnectionLimiter.MAX_PER_ADDRESS * 4)
			.forEach(n -> assertThat(limiter.admit(loopback), is(true)));
		assertThat(limiter.held(loopback), equalTo(0));
	}
}
