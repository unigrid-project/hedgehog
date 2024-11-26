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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import java.util.Optional;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.packet.FetchFragment;
import org.unigrid.hedgehog.model.network.packet.FragmentReply;
import org.unigrid.hedgehog.model.storage.StorageStatus;
import org.unigrid.hedgehog.service.storage.FragmentKeeper;

@Sharable
public class FetchFragmentChannelHandler extends AbstractInboundHandler<FetchFragment> {
	public FetchFragmentChannelHandler() {
		super(FetchFragment.class);
	}

	@Override
	public void typedChannelRead(ChannelHandlerContext ctx, FetchFragment request) {
		CDIUtil.resolveAndRun(FragmentKeeper.class, keeper -> {
			final Optional<byte[]> fragment = keeper.fetch(request.getGroupId());

			ctx.writeAndFlush(FragmentReply.builder().requestId(request.getRequestId())
				.status(fragment.isPresent() ? StorageStatus.OK : StorageStatus.NOT_FOUND)
				.fragment(fragment.orElse(new byte[0])).build())
				.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
		});
	}
}
