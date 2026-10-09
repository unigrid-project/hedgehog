/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.network.initializer;

import java.util.List;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.model.Network;

/* QUIC numbers the streams of a connection by who opened them and how: the two lowest bits are 0 for a
   bidirectional stream of the client, 1 for one of the server, 2 and 3 for unidirectional ones, so the client's
   bidirectional streams are 0, 4, 8, ... and stream 4 can only be the second one the client opened */
public class StreamRouterTest {
	private static final long LARGEST_STREAM_ID = (1L << 62) - 1;
	private final RegisterQuicChannelInitializer main = new RegisterQuicChannelInitializer(List::of,
		RegisterQuicChannelInitializer.Type.SERVER);
	private final ChainStreamInitializer chain = new ChainStreamInitializer(RegisterQuicChannelInitializer.Type.SERVER);
	private final StreamRouter router = new StreamRouter(main, chain);

	@Example
	public void givesTheSecondBidirectionalStreamOfTheClientTheChainPipeline() {
		assertThat(Network.CHAIN_STREAM_ID, is(4L));
		assertThat(router.initializerOf(Network.CHAIN_STREAM_ID, true), is(sameInstance(chain)));
	}

	@Example
	public void givesTheStreamsAroundItTheMainPipeline() {
		for (long streamId : new long[] {0, 1, 2, 3, 5, 6, 7, 8, 12}) {
			assertThat(router.initializerOf(streamId, true), is(sameInstance(main)));
		}
	}

	@Property
	public void givesEveryOtherStreamTheMainPipeline(@ForAll @LongRange(max = LARGEST_STREAM_ID) long streamId) {
		Assume.that(streamId != Network.CHAIN_STREAM_ID);
		assertThat(router.initializerOf(streamId, true), is(sameInstance(main)));
	}

	@Example
	public void givesTheSecondStreamOfAPeerOfThePreviousReleaseTheMainPipeline() {
		assertThat(router.initializerOf(Network.CHAIN_STREAM_ID, false), is(sameInstance(main)));
	}

	@Property
	public void givesEveryStreamOfAPeerOfThePreviousReleaseTheMainPipeline(
		@ForAll @LongRange(max = LARGEST_STREAM_ID) long streamId) {

		assertThat(router.initializerOf(streamId, false), is(sameInstance(main)));
	}
}
