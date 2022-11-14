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

package org.unigrid.hedgehog.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.BiConsumer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.unigrid.hedgehog.model.network.handler.AbstractInboundHandler;
import org.unigrid.hedgehog.model.network.packet.Packet;

@RequiredArgsConstructor
public class BaseServerChannelTest<T extends Packet, H> extends BaseServerTest {
	private final Class<H> channelType;
	@Getter @Setter private Optional<BiConsumer<ChannelHandlerContext, T>> channelCallback = Optional.empty();

	@BeforeProperty
	private void mockBefore() {
		new MockUp<AbstractInboundHandler>() {
			@Mock public void channelRead(Invocation invocation, ChannelHandlerContext ctx, Object obj)
				throws Exception {

				/* First, we call the real handler and take care of the packet ... */
				invocation.proceed(ctx, obj);

				/* ... then we execute the callback */
				for (Entry<String, ChannelHandler> entry : ctx.pipeline()) {
					if (entry.getValue().getClass().equals(channelType)) {
						if (channelCallback.isPresent()) {
							channelCallback.get().accept(ctx, (T) obj);
						}
					}
				}
			}
		};
	}

	@AfterProperty
	private void clearCallback() {
		channelCallback = Optional.empty();
	}
}
