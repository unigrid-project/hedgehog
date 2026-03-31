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

    package org.unigrid.hedgehog.command.cli;

    import io.netty.bootstrap.Bootstrap;
    import io.netty.buffer.ByteBuf;
    import io.netty.buffer.Unpooled;
    import io.netty.channel.*;
    import io.netty.channel.nio.NioEventLoopGroup;
    import io.netty.channel.socket.nio.NioSocketChannel;
    import io.netty.handler.codec.http.*;
    import java.nio.charset.StandardCharsets;
    
    import org.unigrid.hedgehog.model.Json;
    import org.unigrid.hedgehog.model.spork.SporkDatabaseInfo;
    import picocli.CommandLine.Command;
    
    /**
     * CLI command: gridspork-list
     *
     * Netty 4–baserad implementation som ersätter Jakarta REST (JAX-RS).
     * Kör ett HTTP GET-anrop mot /gridspork och skriver ut svaret som JSON.
     */
    @Command(name = "gridspork-list")
    public class GridSporkList implements Runnable {
    
        private static final String HOST = "localhost"; // justera vid behov
        private static final int PORT = 8080;
    
        @Override
        public void run() {
            EventLoopGroup group = new NioEventLoopGroup();
    
            try {
                Bootstrap bootstrap = new Bootstrap();
                bootstrap
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new HttpClientCodec());
                            p.addLast(new HttpObjectAggregator(1_048_576));
                            p.addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
                                    ByteBuf content = response.content();
                                    String json = content.toString(StandardCharsets.UTF_8);
    
                                    SporkDatabaseInfo info =
                                        Json.parse(json, SporkDatabaseInfo.class);
    
                                    System.out.println(Json.parse(info));
                                }
    
                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    cause.printStackTrace();
                                    ctx.close();
                                }
                            });
                        }
                    });
    
                Channel channel = bootstrap.connect(HOST, PORT).sync().channel();
    
                FullHttpRequest request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.GET,
                    "/gridspork",
                    Unpooled.EMPTY_BUFFER
                );
    
                request.headers().set(HttpHeaderNames.HOST, HOST);
                request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    
                channel.writeAndFlush(request).sync();
                channel.closeFuture().sync();
    
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                group.shutdownGracefully();
            }
        }
    }
    