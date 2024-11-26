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

package org.unigrid.hedgehog.model.network;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.CompletableFuture;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.storage.StorageStatus;

public class PendingRequestsTest {
	@Example
	public void completesRequestsFromTheSameChannel() {
		final PendingRequests pending = new PendingRequests();
		final Channel channel = new EmbeddedChannel();
		final long id = pending.nextRequestId();
		final CompletableFuture<StorageAck> ack = pending.register(id, channel, StorageAck.class);

		pending.complete(channel, StorageAck.builder().requestId(id).status(StorageStatus.OK).build());
		assertThat(ack.join().getStatus(), equalTo(StorageStatus.OK));
	}

	@Example
	public void ignoresResponsesFromOtherChannels() {
		final PendingRequests pending = new PendingRequests();
		final long id = pending.nextRequestId();
		final CompletableFuture<StorageAck> ack = pending.register(id, new EmbeddedChannel(), StorageAck.class);

		pending.complete(new EmbeddedChannel(), StorageAck.builder().requestId(id).status(StorageStatus.OK).build());
		assertThat(ack.isDone(), is(false));
	}
}
