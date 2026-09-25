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
import java.time.Instant;
import java.math.BigDecimal;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.ShortRange;
import net.jqwik.api.domains.Domain;
import static com.shazam.shazamcrest.matcher.Matchers.*;
import java.util.Optional;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.jqwik.SuiteDomain;
import org.unigrid.hedgehog.jqwik.NotNull;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSporkProvider;
import org.unigrid.hedgehog.model.spork.MintSupply;

public class PublishSporkIntegrityTest extends BaseCodecTest<PublishSpork> {
	private static String[] networkKeys;

	private final GridSporkProvider gridSporkProvider = new GridSporkProvider();

	@Provide
	public Arbitrary<GridSpork> provideGridSpork(@ForAll GridSpork.Type gridSporkType,
		@ForAll @ShortRange(min = 0, max = 3) short flags, @ForAll @Size(min = 50, max = 60) byte[] signature,
		@ForAll Instant time, @ForAll Instant previousTime) {

		try {
			return gridSporkProvider.provide(gridSporkType, flags, signature, time, previousTime);
		} catch(IllegalArgumentException ex) {
			return Arbitraries.just(null);
		}
	}

	@SneakyThrows
	@Property(tries = 100)
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

	@SneakyThrows
	private GridSpork roundTrip(GridSpork gridSpork, ChannelHandlerContext context) {
		return encodeDecode(PublishSpork.builder().gridSpork(gridSpork).build(), new PublishSporkEncoder(),
			new PublishSporkDecoder(), context
		).getGridSpork();
	}

	@SneakyThrows
	@Example
	public void shouldAcceptRenewalsSentOverTheNetwork(@Mocked ChannelHandlerContext context) {
		final Signature proposer = new Signature();
		final Signature cosigner = new Signature();
		final MintSupply singleSigned = new MintSupply();
		final MintSupply.SporkData data = new MintSupply.SporkData();

		networkKeys = new String[] { proposer.getPublicKey(), cosigner.getPublicKey() };

		new MockUp<NetworkKey>() {
			@Mock public static String[] getPublicKeys() {
				return networkKeys;
			}

			@Mock public static String[] getRetiredPublicKeys() {
				return new String[0];
			}
		};

		data.setMaxSupply(BigDecimal.TEN);
		singleSigned.setData(data);
		singleSigned.archive();
		singleSigned.sign(proposer.getPrivateKey());

		final GridSpork stored = roundTrip(singleSigned, context);
		final GridSpork renewed = SerializationUtils.clone(stored);

		renewed.renew();
		renewed.sign(proposer.getPrivateKey());
		renewed.cosign(cosigner.getPrivateKey());

		final GridSpork received = roundTrip(renewed, context);
		final GridSpork renewedAgain = SerializationUtils.clone(received);

		renewedAgain.renew();
		renewedAgain.sign(cosigner.getPrivateKey());
		renewedAgain.cosign(proposer.getPrivateKey());

		assertThat(received.canReplace(stored), is(true));
		assertThat(roundTrip(renewedAgain, context).canReplace(received), is(true));
	}
}
