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

package org.unigrid.hedgehog.model.network.codec;

import io.netty.channel.ChannelHandlerContext;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.constraints.ShortRange;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static com.shazam.shazamcrest.matcher.Matchers.*;
import java.util.Optional;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.packet.AskPeers;

public class AskPeersIntegrityTest extends BaseCodecTest<AskPeers> {
	@Provide
	public Arbitrary<AskPeers> provideAskPeers(@ForAll @ShortRange(min = Short.MIN_VALUE) short amount) {
		final AskPeers askPeers = AskPeers.builder().amount(amount).build();
		askPeers.setType(Packet.Type.ASK_PEERS);
		return Arbitraries.of(askPeers);
	}

	@Property
	@SneakyThrows
	public void shouldMatch(@ForAll("provideAskPeers") AskPeers askPeers, @Mocked ChannelHandlerContext context) {
		final Optional<Pair<MutableInt, MutableInt>> sizes = getSizeHolder();
		final AskPeers resultingAskPeers = encodeDecode(askPeers, new AskPeersEncoder(), new AskPeersDecoder(),
			context, sizes);

		assertThat(resultingAskPeers, sameBeanAs(askPeers));
		assertThat(resultingAskPeers, equalTo(askPeers));
		assertThat(sizes.get().getLeft(), equalTo(sizes.get().getRight()));
	}
}
