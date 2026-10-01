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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
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
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.network.packet.StoreFragment;
import org.unigrid.hedgehog.model.storage.DeleteProof;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageStatus;
import org.unigrid.hedgehog.model.storage.GroupKey;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class StoragePacketIntegrityTest extends BaseCodecTest<Packet> {
	@SneakyThrows
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Packet roundTrip(Packet packet, PacketEncoder encoder, PacketDecoder decoder, ChannelHandlerContext context) {
		return encodeDecode(packet, encoder, decoder, context);
	}

	private static final Map<Class<?>, Packet.Type> STORAGE_TYPES = Map.of(
		StoreFragment.class, Packet.Type.STORE_FRAGMENT, FetchFragment.class, Packet.Type.FETCH_FRAGMENT,
		FragmentReply.class, Packet.Type.FRAGMENT_REPLY, HasFragment.class, Packet.Type.HAS_FRAGMENT,
		FragmentStatus.class, Packet.Type.FRAGMENT_STATUS, DeleteGroup.class, Packet.Type.DELETE_GROUP,
		StorageAck.class, Packet.Type.STORAGE_ACK);

	private static List<GroupId> groups(List<byte[]> ids) {
		return ids.stream().map(GroupId::of).collect(Collectors.toList());
	}

	@Provide
	public Arbitrary<Packet> storagePackets() {
		final Arbitrary<Long> ids = Arbitraries.longs();

		return Arbitraries.oneOf(ids.map(id -> StoreFragment.builder().requestId(id).build()),
			ids.map(id -> FetchFragment.builder().requestId(id).build()),
			ids.map(id -> FragmentReply.builder().requestId(id).build()),
			ids.map(id -> HasFragment.builder().requestId(id).build()),
			ids.map(id -> FragmentStatus.builder().requestId(id).build()),
			ids.map(id -> DeleteGroup.builder().requestId(id).build()),
			ids.map(id -> StorageAck.builder().requestId(id).build()));
	}

	private static Arbitrary<byte[]> bytes(int size) {
		return Arbitraries.bytes().array(byte[].class).ofSize(size);
	}

	private static Arbitrary<DeleteProof> proofs() {
		return Combinators.combine(bytes(GroupKey.PUBLIC_KEY_SIZE), Arbitraries.longs(), bytes(GroupKey.SIGNATURE_SIZE))
			.as(DeleteProof::new);
	}

	@Provide
	public Arbitrary<List<FragmentStatus.Entry>> entries() {
		return Combinators.combine(bytes(GroupId.SIZE).map(GroupId::of), Arbitraries.of(FragmentStatus.State.class),
			Arbitraries.integers().between(0, StorageCodecs.MAX_INDEX), proofs())
			.as((id, state, index, proof) -> new FragmentStatus.Entry(id, state, index,
				state == FragmentStatus.State.TOMBSTONE ? proof : null))
			.list().ofMaxSize(40);
	}

	@Provide
	public Arbitrary<DeleteProof> malformedProofs() {
		final Arbitrary<byte[]> anyBytes = Arbitraries.bytes().array(byte[].class).ofMaxSize(2 * GroupKey.SIGNATURE_SIZE);

		return Combinators.combine(anyBytes, Arbitraries.longs(), anyBytes).as(DeleteProof::new)
			.filter(proof -> !proof.isWellFormed());
	}

	@Provide
	public Arbitrary<Integer> indicesOutsideAByte() {
		return Arbitraries.oneOf(Arbitraries.integers().lessOrEqual(-1),
			Arbitraries.integers().greaterOrEqual(StorageCodecs.MAX_INDEX + 1));
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
	public void fragmentStatus(@ForAll long requestId, @ForAll("entries") List<FragmentStatus.Entry> entries,
		@Mocked ChannelHandlerContext context) {

		final FragmentStatus packet = FragmentStatus.builder().requestId(requestId).entries(entries).build();

		assertThat(roundTrip(packet, new FragmentStatusEncoder(), new FragmentStatusDecoder(), context),
			equalTo(packet));
	}

	@Property(tries = 30)
	public void encoderRefusesTombstonesWithoutAWellFormedProof(@ForAll long requestId, @ForAll @Size(32) byte[] group,
		@ForAll("malformedProofs") DeleteProof malformed, @ForAll boolean missing, @Mocked ChannelHandlerContext context) {

		final FragmentStatus.Entry entry = new FragmentStatus.Entry(GroupId.of(group), FragmentStatus.State.TOMBSTONE, 0,
			missing ? null : malformed);
		final FragmentStatus packet = FragmentStatus.builder().requestId(requestId).entries(List.of(entry)).build();

		assertThrows(IllegalArgumentException.class, () -> new FragmentStatusEncoder().encode(context, packet));
	}

	@Property(tries = 30)
	public void deleteGroup(@ForAll long requestId, @ForAll @Size(32) byte[] group, @ForAll @Size(32) byte[] publicKey,
		@ForAll long timestamp, @ForAll @Size(64) byte[] signature, @Mocked ChannelHandlerContext context) {

		final DeleteGroup packet = DeleteGroup.builder().requestId(requestId).groupId(GroupId.of(group))
			.publicKey(publicKey).timestamp(timestamp).signature(signature).build();
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
	public void encoderRefusesIndicesOutsideAByte(@ForAll long requestId, @ForAll @Size(32) byte[] group,
		@ForAll("indicesOutsideAByte") int index, @Mocked ChannelHandlerContext context) {

		final FragmentStatus packet = FragmentStatus.builder().requestId(requestId)
			.entries(List.of(new FragmentStatus.Entry(GroupId.of(group), FragmentStatus.State.HELD, index))).build();

		assertThrows(IllegalArgumentException.class, () -> new FragmentStatusEncoder().encode(context, packet));
	}

	@Property(tries = 30)
	public void encoderRefusesSignaturesOfAnyOtherSize(@ForAll long requestId, @ForAll @Size(32) byte[] group,
		@ForAll @Size(max = 2 * GroupKey.SIGNATURE_SIZE) byte[] signature, @Mocked ChannelHandlerContext context) {

		Assume.that(signature.length != GroupKey.SIGNATURE_SIZE);

		final DeleteGroup packet = DeleteGroup.builder().requestId(requestId).groupId(GroupId.of(group))
			.publicKey(new byte[GroupKey.PUBLIC_KEY_SIZE]).signature(signature).build();

		assertThrows(IllegalArgumentException.class, () -> new DeleteGroupEncoder().encode(context, packet));
	}

	@Property(tries = 30)
	public void encoderRefusesPublicKeysOfAnyOtherSize(@ForAll long requestId, @ForAll @Size(32) byte[] group,
		@ForAll @Size(max = 2 * GroupKey.PUBLIC_KEY_SIZE) byte[] publicKey, @Mocked ChannelHandlerContext context) {

		Assume.that(publicKey.length != GroupKey.PUBLIC_KEY_SIZE);

		final DeleteGroup packet = DeleteGroup.builder().requestId(requestId).groupId(GroupId.of(group))
			.publicKey(publicKey).signature(new byte[GroupKey.SIGNATURE_SIZE]).build();

		assertThrows(IllegalArgumentException.class, () -> new DeleteGroupEncoder().encode(context, packet));
	}

	@Property(tries = 30)
	public void decoderRefusesSignaturesOfAnyOtherSize(@ForAll long requestId, @ForAll @Size(32) byte[] group,
		@ForAll long timestamp, @ForAll int length, @Mocked ChannelHandlerContext context) {

		Assume.that(length != GroupKey.SIGNATURE_SIZE);

		final ByteBuf in = Unpooled.buffer().writeLong(requestId).writeBytes(group)
			.writeInt(GroupKey.PUBLIC_KEY_SIZE).writeZero(GroupKey.PUBLIC_KEY_SIZE).writeLong(timestamp)
			.writeInt(length).writeZero(GroupKey.SIGNATURE_SIZE);

		assertThrows(IllegalArgumentException.class, () -> new DeleteGroupDecoder().typedDecode(context, in));
	}

	@Property(tries = 30)
	public void decoderRefusesPublicKeysOfAnyOtherSize(@ForAll long requestId, @ForAll @Size(32) byte[] group,
		@ForAll int length, @Mocked ChannelHandlerContext context) {

		Assume.that(length != GroupKey.PUBLIC_KEY_SIZE);

		final ByteBuf in = Unpooled.buffer().writeLong(requestId).writeBytes(group).writeInt(length)
			.writeZero(GroupKey.PUBLIC_KEY_SIZE + Long.BYTES + Integer.BYTES + GroupKey.SIGNATURE_SIZE);

		assertThrows(IllegalArgumentException.class, () -> new DeleteGroupDecoder().typedDecode(context, in));
	}

	@Property
	public void everyTypeIsFoundByItsValue(@ForAll Packet.Type type) {
		assertThat(Packet.Type.get(type.getValue()), equalTo(type));
	}

	@Property
	public void unassignedValuesAreUndefined(@ForAll short value) {
		Assume.that(Arrays.stream(Packet.Type.values()).noneMatch(type -> type.getValue() == value));
		assertThat(Packet.Type.get(value), equalTo(Packet.Type.UNDEFINED));
	}

	@Property(tries = 50)
	public void buildersSetThePacketType(@ForAll("storagePackets") Packet packet) {
		assertThat(packet.getType(), equalTo(STORAGE_TYPES.get(packet.getClass())));
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
