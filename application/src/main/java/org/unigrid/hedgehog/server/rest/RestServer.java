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
	package org.unigrid.hedgehog.server.rest;

	import io.netty.bootstrap.ServerBootstrap;
	import io.netty.channel.Channel;
	import io.netty.channel.ChannelHandler;
	import io.netty.channel.nio.NioEventLoopGroup;
	import io.netty.channel.socket.nio.NioServerSocketChannel;
	import io.netty.handler.ssl.SslContext;
	import io.netty.handler.ssl.SslContextBuilder;
	import io.netty.handler.ssl.util.SelfSignedCertificate;
	import jakarta.annotation.PostConstruct;
	import jakarta.annotation.PreDestroy;
	import jakarta.enterprise.context.ApplicationScoped;
	import jakarta.inject.Inject;
	import java.net.InetSocketAddress;
	import java.net.URI;
	import org.glassfish.jersey.jackson.JacksonFeature;
	import org.glassfish.jersey.server.ResourceConfig;
	import org.glassfish.jersey.server.spi.Container;
	import org.glassfish.jersey.server.validation.ValidationFeature;
	import org.unigrid.hedgehog.command.option.RestOptions;
	import org.unigrid.hedgehog.model.cdi.Eager;
	import org.unigrid.hedgehog.model.util.Reflection;
	import org.unigrid.hedgehog.server.AbstractServer;
	
	@Eager
	@ApplicationScoped
	public class RestServer extends AbstractServer {
	
		private static final int COMMUNICATION_THREADS = 4;
	
		private final NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup(COMMUNICATION_THREADS);
	
		@Inject
		private RestOptions restOptions;
	
		private Container container;
		private Channel channel;
	
		/* ================= RESOURCE CONFIG ================= */
		private ResourceConfig buildResourceConfig() {
			ResourceConfig config = new ResourceConfig(
				GridSporkResource.class,
				MintStorageResource.class,
				MintSupplyResource.class,
				NodeResource.class,
				VestingStorageResource.class,
				StorageBucket.class,
				StorageObject.class,
				UtilResource.class
			);
	
			config.register(JacksonFeature.class);        // JSON support
			config.register(JsonExceptionMapper.class);   // Om du har denna klass
			config.register(ValidationFeature.class);     // Validering
	
			return config;
		}
	
		/* ================= JERSEY CONTAINER ================= */
		private Container createContainer(ResourceConfig config) throws Exception {
			return (Container) Reflection.getConstructor(
				"org.glassfish.jersey.netty.httpserver.NettyHttpContainer",
				jakarta.ws.rs.core.Application.class
			).newInstance(config);
		}
	
		private ChannelHandler createInitializer(Container container, ResourceConfig config) throws Exception {
			SelfSignedCertificate cert = new SelfSignedCertificate();
			SslContext sslContext = SslContextBuilder.forServer(cert.certificate(), cert.privateKey()).build();
	
			URI baseUri = URI.create("https://" + restOptions.getHost() + ":" + restOptions.getPort());
	
			return (ChannelHandler) Reflection.getConstructor(
				"org.glassfish.jersey.netty.httpserver.JerseyServerInitializer",
				URI.class,
				SslContext.class,
				container.getClass(),
				ResourceConfig.class
			).newInstance(baseUri, sslContext, container, config);
		}
	
		/* ================= LIFECYCLE ================= */
		@PostConstruct
		public void init() {
			try {
				ResourceConfig config = buildResourceConfig();
				container = createContainer(config);
				ChannelHandler initializer = createInitializer(container, config);
	
				channel = new ServerBootstrap()
					.group(eventLoopGroup)
					.channel(NioServerSocketChannel.class)
					.childHandler(initializer)
					.bind(new InetSocketAddress(restOptions.getHost(), restOptions.getPort()))
					.sync()
					.channel();
	
				System.out.printf("REST server started at https://%s:%d%n",
					restOptions.getHost(),
					restOptions.getPort()
				);
	
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				System.err.println("Server startup interrupted: " + ie.getMessage());
			} catch (Exception e) {
				System.err.println("Failed to start REST server: " + e.getMessage());
				e.printStackTrace();
			}
		}
	
		@PreDestroy
		public void destroy() {
			try {
				if (channel != null) {
					channel.close().sync();
				}
				if (container != null) {
					container.getApplicationHandler().onShutdown(container);
				}
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			} finally {
				eventLoopGroup.shutdownGracefully();
			}
		}
	
		public Channel getChannel() {
			return channel;
		}
	}
	