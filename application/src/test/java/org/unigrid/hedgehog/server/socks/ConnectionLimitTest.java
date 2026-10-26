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

package org.unigrid.hedgehog.server.socks;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ConnectionLimitTest {
	@ChannelHandler.Sharable
	private static final class Admitted extends ChannelInboundHandlerAdapter {
	}

	private static boolean wasAdmitted(EmbeddedChannel client) {
		return client.pipeline().get(Admitted.class) != null;
	}

	private static List<EmbeddedChannel> connect(ConnectionLimit limit, int clients) {
		final List<EmbeddedChannel> connected = new ArrayList<>();

		for (int i = 0; i < clients; i++) {
			connected.add(new EmbeddedChannel(limit));
		}

		return connected;
	}

	@Property(tries = 30)
	public void shouldAdmitUpToTheLimitAndTurnTheRestAway(@ForAll @IntRange(min = 1, max = 20) int max,
		@ForAll @IntRange(min = 0, max = 20) int beyond) {
		final List<EmbeddedChannel> clients = connect(new ConnectionLimit(max, new Admitted()), max + beyond);

		for (int i = 0; i < clients.size(); i++) {
			assertThat(wasAdmitted(clients.get(i)), is(i < max));
			assertThat(clients.get(i).isOpen(), is(i < max));
		}
	}

	@Property(tries = 30)
	public void shouldFreeExactlyTheSlotOfTheConnectionThatCloses(@ForAll @IntRange(min = 1, max = 20) int max,
		@ForAll @IntRange(min = 1, max = 20) int turnedAway) {
		final ConnectionLimit shared = new ConnectionLimit(max, new Admitted());
		final List<EmbeddedChannel> admitted = connect(shared, max);

		connect(shared, turnedAway);
		admitted.get(0).close();

		final List<EmbeddedChannel> next = connect(shared, 2);

		assertThat(wasAdmitted(next.get(0)), is(true));
		assertThat(wasAdmitted(next.get(1)), is(false));
	}

	@Example
	public void shouldCountEveryListenerOnItsOwn() {
		final ConnectionLimit first = new ConnectionLimit(1, new Admitted());
		final ConnectionLimit second = new ConnectionLimit(1, new Admitted());

		assertThat(wasAdmitted(connect(first, 1).get(0)), is(true));
		assertThat(wasAdmitted(connect(second, 1).get(0)), is(true));
		assertThat(wasAdmitted(connect(first, 1).get(0)), is(false));
	}
}
