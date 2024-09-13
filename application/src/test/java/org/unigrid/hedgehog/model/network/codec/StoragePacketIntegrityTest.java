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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Negative;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.codec.api.PacketEncoder;
import org.unigrid.hedgehog.model.network.packet.DeleteGroup;
import org.unigrid.hedgehog.model.network.packet.FetchFragment;
import org.unigrid.hedgehog.model.network.packet.FragmentReply;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.network.packet.HasFragment;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.network.packet.StoreFragment;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageStatus;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class StoragePacketIntegrityTest extends BaseCodecTest<Packet> {
	@SneakyThrows
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Packet roundTrip(Packet packet, PacketEncoder encoder, PacketDecoder decoder, ChannelHandlerContext context) {
		return encodeDecode(packet, encoder, decoder, context);
	}

	private static List<GroupId> groups(List<byte[]> ids) {
		return ids.stream().map(GroupId::of).collect(Collectors.toList());
	}

	@Property(tries = 30)
	public void storeFragment(@ForAll long requestId, @ForAll @Size(max = 512) byte[] fragment,
		@Mocked ChannelHandlerContext context) {

		final StoreFragment packet = StoreFragment.builder().requestId(requestId).fragment(fragment).build();
		assertThat(roundTrip(packet, new StoreFragmentEncoder(), new StoreFragmentDecoder(), context), equalTo(packet));
	}

	@Property(tries = 30)
	public void fetchFragment(@ForAll long requestId, @ForAll @Size(32) byte[] group,
		@Mocked ChannelHandlerContext context) {

		final FetchFragment packet = FetchFragment.builder().requestId(requestId).groupId(GroupId.of(group)).build();
		assertThat(roundTrip(packet, new FetchFragmentEncoder(), new FetchFragmentDecoder(), context), equalTo(packet));
	}

	@Property(tries = 30)
	public void fragmentReply(@ForAll long requestId, @ForAll StorageStatus status,
		@ForAll @Size(max = 512) byte[] fragment, @Mocked ChannelHandlerContext context) {

		final FragmentReply packet = FragmentReply.builder().requestId(requestId).status(status).fragment(fragment)
			.build();
		assertThat(roundTrip(packet, new FragmentReplyEncoder(), new FragmentReplyDecoder(), context), equalTo(packet));
	}

	@Property(tries = 30)
	public void hasFragment(@ForAll long requestId, @ForAll @Size(max = 40) List<@Size(32) byte[]> ids,
		@Mocked ChannelHandlerContext context) {

		final HasFragment packet = HasFragment.builder().requestId(requestId).groupIds(groups(ids)).build();
		assertThat(roundTrip(packet, new HasFragmentEncoder(), new HasFragmentDecoder(), context), equalTo(packet));
	}

	@Property(tries = 30)
	public void fragmentStatus(@ForAll long requestId, @ForAll @Size(max = 40) List<@Size(32) byte[]> ids,
		@ForAll FragmentStatus.State state, @ForAll @IntRange(min = 0, max = 254) int index,
		@Mocked ChannelHandlerContext context) {

		final List<FragmentStatus.Entry> entries = groups(ids).stream()
			.map(id -> new FragmentStatus.Entry(id, state, index)).collect(Collectors.toList());
		final FragmentStatus packet = FragmentStatus.builder().requestId(requestId).entries(entries).build();

		assertThat(roundTrip(packet, new FragmentStatusEncoder(), new FragmentStatusDecoder(), context),
			equalTo(packet));
	}

	@Property(tries = 30)
	public void deleteGroup(@ForAll long requestId, @ForAll @Size(32) byte[] group, @ForAll long timestamp,
		@ForAll @Size(64) byte[] signature, @Mocked ChannelHandlerContext context) {

		final DeleteGroup packet = DeleteGroup.builder().requestId(requestId).groupId(GroupId.of(group))
			.timestamp(timestamp).signature(signature).build();
		assertThat(roundTrip(packet, new DeleteGroupEncoder(), new DeleteGroupDecoder(), context), equalTo(packet));
	}

	@Property(tries = 30)
	public void storageAck(@ForAll long requestId, @ForAll StorageStatus status,
		@Mocked ChannelHandlerContext context) {

		final StorageAck packet = StorageAck.builder().requestId(requestId).status(status).build();
		assertThat(roundTrip(packet, new StorageAckEncoder(), new StorageAckDecoder(), context), equalTo(packet));
	}

	@Property(tries = 10)
	public void encodersRefuseTooManyGroups(@ForAll long requestId, @ForAll @Size(32) byte[] group,
		@ForAll @IntRange(min = StorageCodecs.MAX_GROUPS_PER_PACKET + 1, max = 3 * StorageCodecs.MAX_GROUPS_PER_PACKET)
		int count, @Mocked ChannelHandlerContext context) {

		final GroupId id = GroupId.of(group);
		final HasFragment has = HasFragment.builder().requestId(requestId).groupIds(Collections.nCopies(count, id))
			.build();
		final FragmentStatus status = FragmentStatus.builder().requestId(requestId)
			.entries(Collections.nCopies(count, new FragmentStatus.Entry(id, FragmentStatus.State.HELD, 0))).build();

		assertThrows(IllegalArgumentException.class, () -> new HasFragmentEncoder().encode(context, has));
		assertThrows(IllegalArgumentException.class, () -> new FragmentStatusEncoder().encode(context, status));
	}

	@Property(tries = 30)
	public void decodersRefuseTooManyGroups(@ForAll long requestId,
		@ForAll @IntRange(min = StorageCodecs.MAX_GROUPS_PER_PACKET + 1, max = 0xFFFF) int count,
		@Mocked ChannelHandlerContext context) {

		final ByteBuf in = Unpooled.buffer().writeLong(requestId).writeShort(count);

		assertThrows(IllegalArgumentException.class, () -> new HasFragmentDecoder().typedDecode(context, in.copy()));
		assertThrows(IllegalArgumentException.class, () -> new FragmentStatusDecoder().typedDecode(context, in.copy()));
	}

	@Property(tries = 30)
	public void readBytesRefusesNegativeLengths(@ForAll @Negative int length) {
		assertThrows(IllegalArgumentException.class, () -> StorageCodecs.readBytes(Unpooled.buffer().writeInt(length)));
	}

	@Property(tries = 30)
	public void readBytesRefusesOversizedLengths(@ForAll @IntRange(min = Network.MAX_DATA_SIZE + 1) int length) {
		assertThrows(IllegalArgumentException.class, () -> StorageCodecs.readBytes(Unpooled.buffer().writeInt(length)));
	}

	@Property(tries = 30)
	public void readBytesRefusesLengthsBeyondTheBuffer(@ForAll @Size(max = 64) byte[] data,
		@ForAll @IntRange(min = 1, max = Network.MAX_DATA_SIZE) int excess) {

		final int length = (int) Math.min((long) data.length + excess, Network.MAX_DATA_SIZE);
		final ByteBuf in = Unpooled.buffer().writeInt(length).writeBytes(data);

		assertThrows(IndexOutOfBoundsException.class, () -> StorageCodecs.readBytes(in));
	}

	@Property
	public void storageStatusMapsAnyIntToAStatus(@ForAll int ordinal) {
		final StorageStatus[] statuses = StorageStatus.values();
		final boolean known = ordinal >= 0 && ordinal < statuses.length;

		assertThat(StorageStatus.of(ordinal), equalTo(known ? statuses[ordinal] : StorageStatus.ERROR));
	}

	@Property
	public void fragmentStateMapsAnyIntToAState(@ForAll int ordinal) {
		final FragmentStatus.State[] states = FragmentStatus.State.values();
		final boolean known = ordinal >= 0 && ordinal < states.length;

		assertThat(FragmentStatus.State.of(ordinal), equalTo(known ? states[ordinal] : FragmentStatus.State.NONE));
	}

	@Property(tries = 30)
	public void decodersMapUnknownStatusBytes(@ForAll long requestId,
		@ForAll @IntRange(min = 8, max = 0xFF) int status, @ForAll @Size(32) byte[] group,
		@Mocked ChannelHandlerContext context) throws Exception {

		final ByteBuf ack = Unpooled.buffer().writeLong(requestId).writeByte(status);
		final ByteBuf census = Unpooled.buffer().writeLong(requestId).writeShort(1).writeBytes(group)
			.writeByte(status).writeByte(0);

		assertThat(new StorageAckDecoder().typedDecode(context, ack).get().getStatus(), equalTo(StorageStatus.ERROR));
		assertThat(new FragmentStatusDecoder().typedDecode(context, census).get().getEntries().get(0).getState(),
			equalTo(FragmentStatus.State.NONE));
	}
}
