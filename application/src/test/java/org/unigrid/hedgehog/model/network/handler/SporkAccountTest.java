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

import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.util.Optional;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.model.network.handler.RemoteAddress.Dialed;
import org.unigrid.hedgehog.model.spork.SporkLag.Pool;

/* What a stream pays the checks of the sporks it sends from. A peer that this node dialed pays from the pool of those,
   one that an operator configured from a share of its own and any other from the share of its network group, however
   many of its addresses the node dialed; every other peer pays from the pool of the rest, by its network group too. */
public class SporkAccountTest {
	private static Channel streamTo(String address, Optional<Dialed> dialed) {
		final EmbeddedChannel connection = new EmbeddedChannel();

		connection.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).set(new InetSocketAddress(address, 1000));
		dialed.ifPresent(why -> connection.attr(RemoteAddress.DIALED_KEY).set(why));
		return new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false);
	}

	private static PublishSporkChannelHandler.Account accountOf(String address, Optional<Dialed> dialed) {
		return PublishSporkChannelHandler.accountOf(streamTo(address, dialed));
	}

	@Example
	public void shouldShareThePoolOfTheDialedPeersByTheNetworkGroupOfTheChosenOnes() {
		final PublishSporkChannelHandler.Account first = accountOf("45.0.1.1", Optional.of(Dialed.CHOSEN));

		assertThat(first.pool(), equalTo(Pool.TRUSTED));
		assertThat(accountOf("45.0.2.2", Optional.of(Dialed.CHOSEN)), equalTo(first));
		assertThat(accountOf("80.1.1.1", Optional.of(Dialed.CHOSEN)), not(equalTo(first)));
	}

	@Example
	public void shouldGiveAConfiguredPeerAShareOfItsOwnInThePoolOfTheDialedPeers() {
		final PublishSporkChannelHandler.Account seed = accountOf("45.0.250.1", Optional.of(Dialed.CONFIGURED));

		assertThat(seed.pool(), equalTo(Pool.TRUSTED));
		assertThat(seed, not(equalTo(accountOf("45.0.1.1", Optional.of(Dialed.CHOSEN)))));
		assertThat(seed, not(equalTo(accountOf("45.0.250.2", Optional.of(Dialed.CONFIGURED)))));
	}

	@Example
	public void shouldShareThePoolOfEveryOtherPeerByNetworkGroup() {
		final PublishSporkChannelHandler.Account inbound = accountOf("45.0.1.1", Optional.empty());

		assertThat(inbound.pool(), equalTo(Pool.OTHER));
		assertThat(accountOf("45.0.9.9", Optional.empty()), equalTo(inbound));
		assertThat(accountOf("2001:db8:1::1", Optional.empty()), equalTo(accountOf("2001:db8:2::1",
			Optional.empty())));
	}
}
