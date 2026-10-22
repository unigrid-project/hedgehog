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

import io.netty.channel.embedded.EmbeddedChannel;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.model.network.packet.Ping;

/* An answer to a ping this node never sent, or one it already took, has nothing to measure from: the handler leaves
   it, and the topology it would update is not even looked up. Outside a container a lookup would throw, which is what
   shows here that none is made. */
public class UnaskedPingAnswerTest {
	@Example
	public void shouldTakeAnAnswerToNoPingWithoutAWord() {
		final EmbeddedChannel channel = new EmbeddedChannel(new PingChannelHandler());

		channel.writeInbound(Ping.builder().response(true).build());
		channel.checkException();

		assertThat(channel.isOpen(), is(true));
		assertThat(channel.readOutbound(), nullValue());
		channel.finishAndReleaseAll();
	}
}
