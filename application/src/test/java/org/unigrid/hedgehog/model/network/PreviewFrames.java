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

package org.unigrid.hedgehog.model.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import lombok.SneakyThrows;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.gridnode.GridnodeFixtures;
import org.unigrid.hedgehog.model.network.codec.DeleteGroupEncoder;
import org.unigrid.hedgehog.model.network.codec.FetchFragmentEncoder;
import org.unigrid.hedgehog.model.network.codec.FragmentReplyEncoder;
import org.unigrid.hedgehog.model.network.codec.FragmentStatusEncoder;
import org.unigrid.hedgehog.model.network.codec.GridnodeEncoder;
import org.unigrid.hedgehog.model.network.codec.HasFragmentEncoder;
import org.unigrid.hedgehog.model.network.codec.PingEncoder;
import org.unigrid.hedgehog.model.network.codec.StorageAckEncoder;
import org.unigrid.hedgehog.model.network.codec.StoreFragmentEncoder;
import org.unigrid.hedgehog.model.network.packet.DeleteGroup;
import org.unigrid.hedgehog.model.network.packet.FetchFragment;
import org.unigrid.hedgehog.model.network.packet.FragmentReply;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.network.packet.HasFragment;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.network.packet.StoreFragment;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.GroupKey;
import org.unigrid.hedgehog.model.storage.StorageStatus;

/* The frames that a node run with --preview sends its peers and that a node without it has no stage for: the
   announcement of a gridnode and the packets of the storage */
public final class PreviewFrames {
	private static final long SIGNED_AT = 1_700_000_000_000L;
	private static final long REQUEST_ID = 42;
	private static final long NANO_TIME = 4242;
	private static final int HEADER = 8;
	private static final GroupId GROUP = GroupId.of(new byte[GroupId.SIZE]);

	private PreviewFrames() {
		/* Static helpers only */
	}

	public static ByteBuf frameOf(final ChannelHandler encoder, final Object packet) {
		final EmbeddedChannel encoding = new EmbeddedChannel(encoder);

		encoding.writeOutbound(packet);
		return encoding.readOutbound();
	}

	@SneakyThrows
	public static ByteBuf gridnode() {
		final Gridnode gridnode = GridnodeFixtures.signed(new Signature(), Gridnode.Status.ACTIVE, "localhost:52884",
			SIGNED_AT);

		return frameOf(new GridnodeEncoder(), PublishGridnode.builder().gridnode(gridnode).build());
	}

	public static List<ByteBuf> ofTheParts() {
		return List.of(gridnode(),
			frameOf(new StoreFragmentEncoder(), StoreFragment.builder().requestId(REQUEST_ID)
				.fragment(new byte[Long.BYTES]).build()),
			frameOf(new FetchFragmentEncoder(), FetchFragment.builder().requestId(REQUEST_ID).groupId(GROUP).build()),
			frameOf(new FragmentReplyEncoder(), FragmentReply.builder().requestId(REQUEST_ID).status(StorageStatus.OK)
				.fragment(new byte[Long.BYTES]).build()),
			frameOf(new HasFragmentEncoder(), HasFragment.builder().requestId(REQUEST_ID).groupIds(List.of(GROUP))
				.build()),
			frameOf(new FragmentStatusEncoder(), FragmentStatus.builder().requestId(REQUEST_ID)
				.entries(List.of(new FragmentStatus.Entry(GROUP, FragmentStatus.State.HELD, 0, null))).build()),
			frameOf(new DeleteGroupEncoder(), DeleteGroup.builder().requestId(REQUEST_ID).groupId(GROUP)
				.publicKey(new byte[GroupKey.PUBLIC_KEY_SIZE]).timestamp(SIGNED_AT)
				.signature(new byte[GroupKey.SIGNATURE_SIZE]).build()),
			frameOf(new StorageAckEncoder(), StorageAck.builder().requestId(REQUEST_ID).status(StorageStatus.OK)
				.build()));
	}

	public static ByteBuf ping() {
		return frameOf(new PingEncoder(), Ping.builder().nanoTime(NANO_TIME).build());
	}

	/* The answer to the ping, and nothing else, went out on the stream */
	public static void assertThePingIsAnsweredAlone(final EmbeddedChannel stream) {
		final ByteBuf answer = stream.readOutbound();

		assertThat(answer.skipBytes(HEADER).readLong(), is(NANO_TIME));
		assertThat(answer.readByte(), is((byte) 1));
		assertThat(stream.readOutbound(), is(nullValue()));
		answer.release();
	}
}
