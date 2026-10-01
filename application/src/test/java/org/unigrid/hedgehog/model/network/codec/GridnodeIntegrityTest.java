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

import static com.shazam.shazamcrest.matcher.Matchers.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.Optional;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import org.unigrid.hedgehog.jqwik.ArbitraryGenerator;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.gridnode.GridnodeFixtures;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;

public class GridnodeIntegrityTest extends BaseCodecTest<PublishGridnode> {
	@Mocked
	private Connection emptyConnection;

	/* Generating a key pair takes many tries, so one is shared; the codec does not care who signed */
	private static final Signature KEY = key();

	@SneakyThrows
	private static Signature key() {
		return new Signature();
	}

	@SneakyThrows
	private static PublishGridnode packetOf(Gridnode.Status status, long timestamp, int port) {
		return PublishGridnode.builder().gridnode(GridnodeFixtures.signed(KEY, status,
			ArbitraryGenerator.ip4() + ":" + port, timestamp
		)).build();
	}

	@Provide
	public Arbitrary<PublishGridnode> provideGridnodePacket() {
		return Combinators.combine(Arbitraries.of(Gridnode.Status.values()), Arbitraries.longs(),
			Arbitraries.integers().between(4097, 65535)
		).as(GridnodeIntegrityTest::packetOf);
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
		assertThat(sizes.get().getLeft(), equalTo(sizes.get().getRight()));
	}

	private static ByteBuf frame(int idLength, int hostLength, int signatureLength) {
		final ByteBuf out = Unpooled.buffer();

		out.writeByte(1).writeLong(0).writeShort(idLength).writeZero(idLength);
		out.writeShort(hostLength);

		if (hostLength <= GridnodeDecoder.MAX_HOST_LENGTH) {
			out.writeZero(hostLength).writeShort(signatureLength);
		}

		return out;
	}

	private static Throwable decodeFailure(ByteBuf in) {
		try {
			new GridnodeDecoder().typedDecode(null, in);
			return null;
		} catch (Exception ex) {
			return ex;
		}
	}

	@Example
	public void shouldRefuseAHostAboveTheLimitWithoutReadingIt() {
		assertThat(decodeFailure(frame(GridnodeDecoder.ID_LENGTH, 65535, 0)),
			instanceOf(CorruptedFrameException.class));
	}

	@Example
	public void shouldRefuseAnIdOfTheWrongLength() {
		assertThat(decodeFailure(frame(10, 0, 0)), instanceOf(CorruptedFrameException.class));
	}

	@Example
	public void shouldRefuseASignatureAboveTheLimit() {
		assertThat(decodeFailure(frame(GridnodeDecoder.ID_LENGTH, 0, GridnodeDecoder.MAX_SIGNATURE_LENGTH + 1)),
			instanceOf(CorruptedFrameException.class));
	}
}
