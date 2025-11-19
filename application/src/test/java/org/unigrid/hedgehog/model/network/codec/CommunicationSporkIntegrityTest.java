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

package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Builders;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.model.network.codec.chunk.CommunicationSporkEncoder;
import org.unigrid.hedgehog.model.spork.CommunicationSpork.SporkData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.model.spork.CommunicationSporkTest.MAX_UNSIGNED_INT;
import static org.unigrid.hedgehog.model.spork.CommunicationSporkTest.MAX_UNSIGNED_SHORT;
import static org.unigrid.hedgehog.model.spork.CommunicationSporkTest.with;

public class CommunicationSporkIntegrityTest {
	private static final int ENCODED_SIZE = 32;

	@Provide
	public Arbitrary<SporkData> wireWideSporkData() {
		final Arbitrary<Integer> unsignedShorts = Arbitraries.integers().between(0, MAX_UNSIGNED_SHORT);
		final Arbitrary<Long> unsignedInts = Arbitraries.longs().between(0, MAX_UNSIGNED_INT);

		return Builders.withBuilder(SporkData::new)
			.use(unsignedShorts).in(with(SporkData::setGroupSize))
			.use(unsignedInts).in(with(SporkData::setReorganizePeriodSeconds))
			.use(unsignedShorts).in(with(SporkData::setChainsPerGroup))
			.use(unsignedShorts).in(with(SporkData::setClientsPerChain))
			.use(unsignedInts).in(with(SporkData::setChainCapacityBytes))
			.use(unsignedShorts).in(with(SporkData::setRetentionSeconds))
			.use(unsignedInts).in(with(SporkData::setMaxEntryBytes))
			.use(unsignedShorts).in(with(SporkData::setSessionIdleSeconds))
			.use(unsignedShorts).in(with(SporkData::setCommunicationFlags))
			.build();
	}

	@SneakyThrows
	private static ByteBuf encode(SporkData data) {
		final ByteBuf buffer = Unpooled.buffer();

		new CommunicationSporkEncoder().encodeChunk(null, data, buffer);
		return buffer;
	}

	private static SporkData withEveryFieldAt(int unsignedShort, long unsignedInt) {
		final SporkData data = new SporkData();

		data.setGroupSize(unsignedShort);
		data.setReorganizePeriodSeconds(unsignedInt);
		data.setChainsPerGroup(unsignedShort);
		data.setClientsPerChain(unsignedShort);
		data.setChainCapacityBytes(unsignedInt);
		data.setRetentionSeconds(unsignedShort);
		data.setMaxEntryBytes(unsignedInt);
		data.setSessionIdleSeconds(unsignedShort);
		data.setCommunicationFlags(unsignedShort);
		return data;
	}

	@Example
	public void laysOutTheFieldsInTheSpecifiedOrder() {
		assertThat(ByteBufUtil.hexDump(encode(new SporkData())),
			equalTo("000800000e100004001002000000001e00004000012c00030000000000000000"));
	}

	@Example
	public void encodesEveryFieldAtItsWireMaximum() {
		assertThat(ByteBufUtil.hexDump(encode(withEveryFieldAt(MAX_UNSIGNED_SHORT, MAX_UNSIGNED_INT))),
			equalTo("ffffffffffffffffffffffffffffffffffffffffffffffff0000000000000000"));
	}

	@Example
	public void encodesEveryFieldAtZeroAsZeroBytes() {
		assertThat(ByteBufUtil.hexDump(encode(withEveryFieldAt(0, 0))),
			equalTo("0000000000000000000000000000000000000000000000000000000000000000"));
	}

	/* validate() refuses these values, so the encoder only has to keep its output at the fixed width */
	@Example
	public void truncatesValuesBeyondTheirWireWidth() {
		final SporkData data = new SporkData();

		data.setGroupSize(MAX_UNSIGNED_SHORT + 1);
		data.setReorganizePeriodSeconds(MAX_UNSIGNED_INT + 1);

		assertThat(ByteBufUtil.hexDump(encode(data)),
			equalTo("0000000000000004001002000000001e00004000012c00030000000000000000"));
	}

	@Property
	public void encodesEverySporkInAFixedSize(@ForAll("wireWideSporkData") SporkData data) {
		assertThat(encode(data).readableBytes(), equalTo(ENCODED_SIZE));
	}
}
