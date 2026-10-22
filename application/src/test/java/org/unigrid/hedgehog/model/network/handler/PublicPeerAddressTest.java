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

package org.unigrid.hedgehog.model.network.handler;

import java.net.InetAddress;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/* Which addresses a peer on the internet is heard on when it tells of other peers */
public class PublicPeerAddressTest {
	@Example
	public void shouldTakeAddressesOnTheInternet() {
		for (final String address : new String[] { "8.8.8.8", "203.0.113.9", "2001:4860:4860::8888" }) {
			assertThat(address, PublishPeersChannelHandler.isPublic(InetAddress.ofLiteral(address)), is(true));
		}
	}

	@Example
	public void shouldNotTakeAddressesOfThisHostOrOfAPrivateNetwork() {
		for (final String address : new String[] { "127.0.0.1", "10.0.0.1", "172.16.4.2", "192.168.1.1",
			"169.254.1.1", "0.0.0.0", "224.0.0.1", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "::", "ff02::1" }) {

			assertThat(address, PublishPeersChannelHandler.isPublic(InetAddress.ofLiteral(address)), is(false));
		}
	}
}
