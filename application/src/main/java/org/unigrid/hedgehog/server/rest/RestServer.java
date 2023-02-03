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
import jakarta.ws.rs.core.Application;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLException;
import lombok.Getter;
import lombok.SneakyThrows;
import org.glassfish.jersey.jackson.internal.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.spi.Container;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.model.util.Reflection;
import org.unigrid.hedgehog.server.AbstractServer;

@Eager @ApplicationScoped
public class RestServer extends AbstractServer {
	private static final int COMMUNICATION_THREADS = 4;

	private Container container;
	private final NioEventLoopGroup group = new NioEventLoopGroup(COMMUNICATION_THREADS);
	private ResourceConfig resourceConfig = getResourceConfig();
	@Getter private Channel channel;

	private ResourceConfig getResourceConfig() {
		final ResourceConfig config = new ResourceConfig(GridSporkResource.class,
			MintStorageResource.class,
			MintSupplyResource.class,
			NodeResource.class,
			VestingStorageResource.class,
			StorageBucket.class,
			StorageObject.class
		);

		config.register(JacksonJaxbJsonProvider.class);
		config.register(new JsonConfiguration());
		config.register(JsonExceptionMapper.class);
		config.register(InternalServerErrorMapper.class);

		return config;
	}

	private Container getContainerInstance() throws ClassNotFoundException,
		IllegalAccessException, InstantiationException, InvocationTargetException, NoSuchMethodException {

		final Constructor<?> constructor = Reflection.getConstructor(
			"org.glassfish.jersey.netty.httpserver.NettyHttpContainer", Application.class
		);

		return (Container) constructor.newInstance(resourceConfig);
	}

	private ChannelHandler getJerseyInitializerInstance(Container container) throws CertificateException,
		ClassNotFoundException, IllegalAccessException, InstantiationException, InvocationTargetException,
		MalformedURLException, NoSuchMethodException, SSLException, UnknownHostException, URISyntaxException {

		final SelfSignedCertificate certificate = new SelfSignedCertificate();
		final String url = String.format("https://%s:%d", RestOptions.getHost(), RestOptions.getPort());

		final SslContext context = SslContextBuilder.forServer(
			certificate.certificate(), certificate.privateKey()
		).build();

		final Constructor<?> constructor = Reflection.getConstructor(
			"org.glassfish.jersey.netty.httpserver.JerseyServerInitializer", URI.class,
			SslContext.class, container.getClass(), ResourceConfig.class
		);

		return (ChannelHandler) constructor.newInstance(allocate(url).toURI(), context,
			container, resourceConfig
		);
	}

	@SneakyThrows
	@PostConstruct
	public void init() {
		container = getContainerInstance();
		final ChannelHandler initializer = getJerseyInitializerInstance(container);

		channel = new ServerBootstrap().group(group)
			.channel(NioServerSocketChannel.class)
			.childHandler(initializer)
			.bind(new InetSocketAddress(RestOptions.getHost(), RestOptions.getPort())).sync().channel();
	}

	@PreDestroy
	public void destroy() {
		channel.close();
		container.getApplicationHandler().onShutdown(container);
		group.shutdownGracefully();
	}
}
