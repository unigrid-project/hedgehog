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

import io.netty.channel.ChannelHandlerContext;
import java.time.Instant;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.ShortRange;
import net.jqwik.api.domains.Domain;
import static com.shazam.shazamcrest.matcher.Matchers.*;
import java.util.Optional;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.jqwik.SuiteDomain;
import org.unigrid.hedgehog.jqwik.NotNull;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSporkProvider;

public class PublishSporkIntegrityTest extends BaseCodecTest<PublishSpork> {
	private final GridSporkProvider gridSporkProvider = new GridSporkProvider();

	@Provide(ignoreExceptions = IllegalArgumentException.class)
	public Arbitrary<GridSpork> provideGridSpork(@ForAll GridSpork.Type gridSporkType,
		@ForAll @ShortRange(min = 0, max = 3) short flags, @ForAll @Size(min = 50, max = 60) byte[] signature,
		@ForAll Instant time, @ForAll Instant previousTime) {

		return gridSporkProvider.provide(gridSporkType, flags, signature, time, previousTime);
	}

	@SneakyThrows
	@Property(tries = 200)
	@Domain(SuiteDomain.class)
	public void shouldMatch(@ForAll("provideGridSpork") @NotNull GridSpork gridSpork,
		@Mocked ChannelHandlerContext context) {

		final PublishSpork publishSpork = PublishSpork.builder().gridSpork(gridSpork).build();
		final Optional<Pair<MutableInt, MutableInt>> sizes = getSizeHolder();

		final PublishSpork resultingPublishSpork = encodeDecode(publishSpork,
			new PublishSporkEncoder(), new PublishSporkDecoder(), context, sizes
		);

		assertThat(resultingPublishSpork, sameBeanAs(publishSpork));
		assertThat(resultingPublishSpork, equalTo(publishSpork));
		assertThat(sizes.get().getLeft(), equalTo(sizes.get().getRight()));
	}
}
