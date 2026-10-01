/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.network.packet.StoreFragment;
import org.unigrid.hedgehog.service.storage.FragmentKeeper;

@Sharable
public class StoreFragmentChannelHandler extends AbstractInboundHandler<StoreFragment> {
	public StoreFragmentChannelHandler() {
		super(StoreFragment.class);
	}

	@Override
	public void typedChannelRead(final ChannelHandlerContext ctx, final StoreFragment request) {
		CDIUtil.resolveAndRun(FragmentKeeper.class, keeper -> {
			ctx.writeAndFlush(StorageAck.builder().requestId(request.getRequestId())
				.status(keeper.store(request.getFragment())).build())
				.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
		});
	}
}
