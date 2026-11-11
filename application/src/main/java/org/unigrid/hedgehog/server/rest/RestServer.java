/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.server.rest;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.NetUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Application;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLException;
import lombok.Getter;
import lombok.SneakyThrows;
import org.glassfish.jersey.jackson.internal.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.spi.Container;
import org.glassfish.jersey.server.validation.ValidationFeature;
import org.unigrid.hedgehog.model.option.RestOptions;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.model.util.Reflection;
import org.unigrid.hedgehog.server.AbstractServer;

@Eager @ApplicationScoped
public class RestServer extends AbstractServer {
	private static final int COMMUNICATION_THREADS = 4;

	private Container container;
	private final NioEventLoopGroup group = new NioEventLoopGroup(COMMUNICATION_THREADS);
	private ResourceConfig resourceConfig;
	@Getter private Channel channel;
	@Getter private String token;

	@SneakyThrows
	private String generateToken() {
		final String generated = RestToken.generate();

		RestToken.write(RestToken.getFile(), generated);
		return generated;
	}

	private ResourceConfig getResourceConfig(BearerTokenFilter authentication) {
		final ResourceConfig config = new ResourceConfig(GridSporkResource.class,
			GridSporkWireResource.class,
			BootstrapResource.class,
			GridnodeResource.class,
			LedgerResource.class,
			MintStorageResource.class,
			NodeResource.class,
			SporkResource.class,
			VestingStorageResource.class,
			StorageResource.class,
			UtilResource.class
		);

		config.register(JacksonJaxbJsonProvider.class);
		config.register(new JsonConfiguration());
		config.register(JsonExceptionMapper.class);
		config.register(ValidationFeature.class);
		config.register(authentication);
		config.register(CutBodyFilter.class);
		config.register(UnreadBodyFilter.class);
		config.register(PreviewPartFilter.class);

		return config;
	}

	private Container getContainerInstance() throws ClassNotFoundException,
		IllegalAccessException, InstantiationException, InvocationTargetException, NoSuchMethodException {

		final Constructor<?> constructor = Reflection.getConstructor(
			"org.glassfish.jersey.netty.httpserver.NettyHttpContainer", Application.class
		);

		return (Container) constructor.newInstance(resourceConfig);
	}

	/* The certificate is written to a key file and a certificate file in the temporary directory, which the context
	   has read once it is built, so they are deleted then rather than left there with the key in them */
	private static SslContext serverContext() throws CertificateException, SSLException {
		final SelfSignedCertificate certificate = new SelfSignedCertificate();

		try {
			return SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).build();
		} finally {
			certificate.delete();
		}
	}

	/* The address that the resources answer under, which is the host and the port that the server binds: a location
	   that a resource hands out is resolved against it. An IPv6 address goes in brackets, as its colons would end the
	   host otherwise, and the path ends in a slash, as the container puts the path of a request straight after it. */
	static URI baseUri() throws URISyntaxException {
		final String address = NetUtil.toSocketAddressString(RestOptions.getHost(), RestOptions.getPort());

		return new URI("https://" + address + "/");
	}

	private ChannelHandler getJerseyInitializerInstance(Container container) throws CertificateException,
		ClassNotFoundException, IllegalAccessException, InstantiationException, InvocationTargetException,
		NoSuchMethodException, SSLException, URISyntaxException {

		final SslContext context = serverContext();

		final Constructor<?> constructor = Reflection.getConstructor(
			"org.glassfish.jersey.netty.httpserver.JerseyServerInitializer", URI.class,
			SslContext.class, container.getClass(), ResourceConfig.class
		);

		return (ChannelHandler) constructor.newInstance(baseUri(), context, container, resourceConfig);
	}

	/* The initializer of the container builds the pipeline of a connection, so the limit is set in after its codec,
	   where a request has been read from the wire and nothing has yet queued it. The codec hands nothing on until it
	   has the head of a request whole, so what tells the limit that something has arrived is set in before it. */
	private static ChannelHandler limited(final ChannelHandler jersey, final BearerTokenFilter authentication) {
		return new ChannelInitializer<SocketChannel>() {
			@Override
			protected void initChannel(final SocketChannel connection) {
				final ChannelPipeline pipeline = connection.pipeline();
				final RequestBodyLimit limit = new RequestBodyLimit(authentication,
					RequestBodyLimit.MAX_REQUEST_BYTES, StorageResource.MAX_UPLOAD_BYTES,
					RequestBodyLimit.BODY_TIME, RequestBodyLimit.LINGER);

				pipeline.addLast(jersey);

				final String codec = pipeline.context(HttpServerCodec.class).name();

				pipeline.addBefore(codec, null, limit.arrivals());
				pipeline.addAfter(codec, null, limit);
			}
		};
	}

	@SneakyThrows
	@PostConstruct
	public void init() {
		token = RestToken.getConfigured().orElseGet(this::generateToken);

		final BearerTokenFilter authentication = new BearerTokenFilter(token);

		resourceConfig = getResourceConfig(authentication);
		container = getContainerInstance();
		final ChannelHandler initializer = limited(getJerseyInitializerInstance(container), authentication);

		final ServerBootstrap bootstrap = new ServerBootstrap().group(group)
			.channel(NioServerSocketChannel.class)
			.childHandler(initializer);

		channel = listen(bootstrap, RestOptions.getHost(), RestOptions.getPort(), "REST", "--resthost, --restport");
	}

	@PreDestroy
	@SneakyThrows
	public void destroy() {
		channel.close();
		container.getApplicationHandler().onShutdown(container);
		group.shutdownGracefully();

		if (RestToken.getConfigured().isEmpty()) {
			Files.deleteIfExists(RestToken.getFile());
		}
	}
}
