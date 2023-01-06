/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.unigrid.hedgehog.model.network.packet.Packet;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractInboundHandler<T extends Packet> extends ChannelInboundHandlerAdapter {
	private final Class<T> clazz;

	/* Special abstraction to clean up the default inbound handler and introduce generic types. Can and
	   should be expanded to support more of the overridable methods in ChannelInboundHandlerAdapter as the
	   code base needs it. */

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object obj) throws Exception {
		boolean release = true;

		try {
			if (clazz.isInstance(obj)) {
				typedChannelRead(ctx, (T) obj);
			} else {
				release = false;
				ctx.fireChannelRead(obj);
			}
		} finally {
			if (release) {
				//TODO: Do we actually need to do this?
				ReferenceCountUtil.release(obj);
			}
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		log.atWarn().log("{}:{}", cause.getMessage(), ExceptionUtils.getStackTrace(cause));
		ctx.close();
	}

	public abstract void typedChannelRead(ChannelHandlerContext ctx, T obj) throws Exception;
}
