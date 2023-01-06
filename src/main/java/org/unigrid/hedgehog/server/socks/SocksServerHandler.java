/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.unigrid.hedgehog.server.socks;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.SocksMessage;
import io.netty.handler.codec.socksx.v4.Socks4CommandRequest;
import io.netty.handler.codec.socksx.v4.Socks4CommandType;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import io.netty.util.AttributeKey;
import org.unigrid.hedgehog.model.network.handler.AbstractInboundHandler;
import java.util.Optional;

@ChannelHandler.Sharable
public class SocksServerHandler extends AbstractInboundHandler<SocksServer> {

	public static final SocksServerHandler INSTANCE = new SocksServerHandler();
	public static final AttributeKey<Boolean> IS_AUTHENTICATED_KEY = AttributeKey.valueOf("IS_AUTHENTICATED");

	private SocksServerHandler() {
		super(Optional.of(IS_AUTHENTICATED_KEY),SocksServer.class);
	}

//	public void channelRead0(ChannelHandlerContext ctx, SocksMessage socksRequest) throws Exception {
//		switch (socksRequest.version()) {
//			case SOCKS5:
//				if (socksRequest instanceof Socks5InitialRequest) {
//					// auth support example
//					ctx.pipeline().addFirst(new Socks5PasswordAuthRequestDecoder());
//					ctx.write(new DefaultSocks5AuthMethodResponse(Socks5AuthMethod.PASSWORD));
//					//ctx.pipeline().addFirst(new Socks5CommandRequestDecoder());
//					// ctx.write(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
//				} else if (socksRequest instanceof Socks5PasswordAuthRequest) {
//
//					System.out.println("Password: " + ((Socks5PasswordAuthRequest) socksRequest).password());
//					if (((Socks5PasswordAuthRequest) socksRequest).password().equals("SHIT")) {
//						ctx.pipeline().addFirst(new Socks5CommandRequestDecoder());
//						ctx.channel().attr(IS_AUTHENTICATED_KEY).set(true);
//						ctx.write(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
//					}
//				} else if (socksRequest instanceof Socks5CommandRequest) {
//					Socks5CommandRequest socks5CmdRequest = (Socks5CommandRequest) socksRequest;
//					if (socks5CmdRequest.type() == Socks5CommandType.CONNECT) {
//						ctx.pipeline().addLast(new SocksServerConnectHandler());
//						ctx.pipeline().remove(this);
//						ctx.fireChannelRead(socksRequest);
//					} else {
//						ctx.close();
//					}
//				} else {
//					ctx.close();
//				}
//				break;
//			case UNKNOWN:
//				ctx.close();
//				break;
//		}
//	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) {
		ctx.flush();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable throwable) {
		throwable.printStackTrace();
		SocksServerUtils.closeOnFlush(ctx.channel());
	}

	@Override
	public void typedChannelRead(ChannelHandlerContext ctx, SocksServer obj) throws Exception {
				if (obj instanceof Socks5InitialRequest) {
					// auth support example
					ctx.pipeline().addFirst(new Socks5PasswordAuthRequestDecoder());
					ctx.write(new DefaultSocks5AuthMethodResponse(Socks5AuthMethod.PASSWORD));
					//ctx.pipeline().addFirst(new Socks5CommandRequestDecoder());
					// ctx.write(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
				} else if (obj instanceof Socks5PasswordAuthRequest) {

					System.out.println("Password: " + ((Socks5PasswordAuthRequest) obj).password());
					if (((Socks5PasswordAuthRequest) obj).password().equals("true")) {
						ctx.pipeline().addFirst(new Socks5CommandRequestDecoder());
						ctx.channel().attr(IS_AUTHENTICATED_KEY).set(true);
						ctx.write(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
					}
				} else if (obj instanceof Socks5CommandRequest) {
					Socks5CommandRequest socks5CmdRequest = (Socks5CommandRequest) obj;
					if (socks5CmdRequest.type() == Socks5CommandType.CONNECT) {
						ctx.pipeline().addLast(new SocksServerConnectHandler());
						ctx.pipeline().remove(this);
						ctx.fireChannelRead(obj);
					} else {
						ctx.close();
					}
				} else {
					ctx.close();
				}
				
			
		}
	}
