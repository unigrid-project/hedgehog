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
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractInboundHandler<T> extends ChannelInboundHandlerAdapter {
	private final Optional<AttributeKey<Boolean>> filteringAttribute;
	private final Class<T> clazz;
	
	public AbstractInboundHandler(Class<T> clazz){
		this(Optional.empty(), clazz);
	}

	/* Special abstraction to clean up the default inbound handler and introduce generic types. Can and
	   should be expanded to support more of the overridable methods in ChannelInboundHandlerAdapter as the
	   code base needs it. */

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object obj) throws Exception {
		//boolean release = true;

		final Supplier<Boolean> isAttributeEmptyAndOurClass = () -> filteringAttribute.isEmpty() && clazz.isInstance(obj);

		//System.out.println("ctx: " + ctx.channel().attr(filteringAttribute.get()));
		//System.out.println("ctx: " + ctx.channel().attr(filteringAttribute.get()).get());
		System.out.println("Channel " + ctx.channel());
	
//		final Supplier<Boolean> isAttributeFalseOrNullAndOurClass = () -> {
//			if (filteringAttribute.isPresent()) {
//				
//				final Boolean attribute = ctx.channel().attr( filteringAttribute.get()).get();
//				
//				if (Objects.isNull(attribute) || attribute.equals(false)) {
//					System.out.println("Its hereee");
//					return clazz.isInstance(obj);
//				}
//			}
//	
//			return false;
//		};
		
		try {
			System.out.println("Received object of class: " + obj.getClass().getName());
			
			if (isAttributeEmptyAndOurClass.get() ||  isAttributeFalseOrNullAndOurClass(ctx, obj)) {
				typedChannelRead(ctx, (T) obj);
				System.out.println("Its here");
			} else {
				System.out.println("It`s here now");
			//	release = false;
				ctx.fireChannelRead(obj);
				ReferenceCountUtil.release(obj);
			}
		} finally {
			//if (release) {
				//TODO: Do we actually need to do this?
				//ReferenceCountUtil.release(obj);
			//}
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		log.atWarn().log("{}:{}", cause.getMessage(), ExceptionUtils.getStackTrace(cause));
		ctx.close();
	}
	
	private boolean isAttributeFalseOrNullAndOurClass(ChannelHandlerContext ctx, Object obj) {
		if (filteringAttribute.isPresent()) {
			final Boolean attribute = ctx.channel().attr(filteringAttribute.get()).get();
			if (Objects.isNull(attribute) || attribute.equals(false)) {
				return clazz.isInstance(obj);
			}
		}
		return false;
	}

	public abstract void typedChannelRead(ChannelHandlerContext ctx, T obj) throws Exception;
}
