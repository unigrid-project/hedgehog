/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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

import static com.shazam.shazamcrest.matcher.Matchers.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import io.netty.channel.ChannelHandlerContext;
import java.net.UnknownHostException;
import java.util.Optional;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import org.unigrid.hedgehog.jqwik.ArbitraryGenerator;
import org.unigrid.hedgehog.model.gridnode.Gridnode;

public class GridnodeIntegrityTest extends BaseCodecTest<PublishGridnode>{
	@Mocked
	private Connection emptyConnection;
	
	@Provide
	public Arbitrary<PublishGridnode> provideGridnodePacket(@ForAll @AlphaChars @StringLength(36) String gridnodeKey,
		@ForAll @IntRange(min = 4097, max = 65535) int port) throws UnknownHostException {

		final PublishGridnode gp = PublishGridnode.builder().build();
		final String ip = ArbitraryGenerator.ip4();
		final Gridnode gridnode = Gridnode.builder().id(gridnodeKey).hostName(ip + ":" + port).build();
		gp.setGridnode(gridnode);

		return Arbitraries.of(gp);
	}

	@Property
	@SneakyThrows
	public void shouldMatch(@ForAll("provideGridnodePacket") PublishGridnode gridnodePacket, 
		@Mocked ChannelHandlerContext context) {

		final Optional<Pair<MutableInt, MutableInt>> sizes = getSizeHolder();

		final PublishGridnode resultingGridnode = encodeDecode(gridnodePacket,
			new GridnodeEncoder(), new GridnodeDecoder(), context, sizes
		);

		assertThat(resultingGridnode, sameBeanAs(gridnodePacket));
		assertThat(resultingGridnode, equalTo(gridnodePacket));
		assertThat(sizes.get().getLeft(), equalTo(sizes.get().getRight()));
		
	}
}
