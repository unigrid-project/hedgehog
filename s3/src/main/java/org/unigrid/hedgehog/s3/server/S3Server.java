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

package org.unigrid.hedgehog.s3.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import jakarta.ws.rs.core.Application;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.spi.Container;
import org.unigrid.hedgehog.s3.auth.SignatureFilter;
import org.unigrid.hedgehog.s3.error.S3ExceptionMapper;
import org.unigrid.hedgehog.s3.http.RequestIdFilter;
import org.unigrid.hedgehog.s3.http.VirtualHostFilter;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.resource.BucketResource;
import org.unigrid.hedgehog.s3.resource.ObjectResource;
import org.unigrid.hedgehog.s3.resource.ServiceResource;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.CredentialStore;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class S3Server implements AutoCloseable {
	private static final int COMMUNICATION_THREADS = 4;
	private static final String JERSEY_NETTY = "org.glassfish.jersey.netty.httpserver.";

	private final Container container;
	private final EventLoopGroup group;
	private final Channel channel;

	public static S3Server start(final S3ServerConfig config, final BlobStore blobs, final Namespace namespace,
		final CredentialStore credentials) throws GeneralSecurityException, InterruptedException, IOException,
		ReflectiveOperationException, URISyntaxException {

		return start(config, resources(config, blobs, namespace, credentials, Clock.systemUTC()));
	}

	static S3Server start(final S3ServerConfig config, final ResourceConfig resources) throws GeneralSecurityException,
		InterruptedException, IOException, ReflectiveOperationException, URISyntaxException {

		final Container container = (Container) constructor("NettyHttpContainer", Application.class)
			.newInstance(resources);
		final EventLoopGroup group = new NioEventLoopGroup(COMMUNICATION_THREADS);

		try {
			return new S3Server(container, group, new ServerBootstrap().group(group)
				.channel(NioServerSocketChannel.class)
				.childHandler(initializer(config, container, resources))
				.bind(new InetSocketAddress(config.getHost(), config.getPort())).sync().channel());
		} catch (final Exception failure) {
			shutdown(container, group);
			throw failure;
		}
	}

	static ResourceConfig resources(final S3ServerConfig config, final BlobStore blobs, final Namespace namespace,
		final CredentialStore credentials, final Clock clock) {

		return resources(config, blobs, credentials, clock).registerInstances(new ServiceResource(namespace),
			new BucketResource(namespace, clock), new ObjectResource(namespace, blobs, clock)
		);
	}

	/* The protocol without the operations, which leaves the routes to whoever registers resources */
	static ResourceConfig resources(final S3ServerConfig config, final BlobStore blobs,
		final CredentialStore credentials, final Clock clock) {

		final AbstractBinder bindings = new AbstractBinder() {
			@Override
			protected void configure() {
				bind(config).to(S3ServerConfig.class);
				bind(blobs).to(BlobStore.class);
				bind(credentials).to(CredentialStore.class);
			}
		};

		return new ResourceConfig(S3ExceptionMapper.class)
			.register(new RequestIdFilter(clock)).register(new SignatureFilter(credentials, clock))
			.register(new VirtualHostFilter(config.getDomain().orElse(null))).register(bindings);
	}

	/* Jersey's public Netty factory owns its event loops and hides the container that has to be shut down, so
	   its package-private container and request handler are built directly instead */
	private static Constructor<?> constructor(final String name, final Class<?>... parameters)
		throws ReflectiveOperationException {

		final Constructor<?> constructor = Class.forName(JERSEY_NETTY + name).getDeclaredConstructor(parameters);

		constructor.setAccessible(true);
		return constructor;
	}

	private static ChannelHandler initializer(final S3ServerConfig config, final Container container,
		final ResourceConfig resources) throws GeneralSecurityException, IOException, ReflectiveOperationException,
		URISyntaxException {

		final String scheme = config.isTls() ? "https" : "http";
		final URI base = new URI(scheme, null, config.getHost(), config.getPort(), "/", null, null);
		final Constructor<?> handler = constructor("JerseyServerHandler", URI.class, container.getClass(),
			ResourceConfig.class);

		return new S3ChannelInitializer(config.isTls() ? Optional.of(ssl()) : Optional.empty(),
			() -> (ChannelHandler) handler.newInstance(base, container, resources)
		);
	}

	private static SslContext ssl() throws GeneralSecurityException, IOException {
		final SelfSignedCertificate certificate = new SelfSignedCertificate();

		return SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).build();
	}

	private static void shutdown(final Container container, final EventLoopGroup group) {
		container.getApplicationHandler().onShutdown(container);
		group.shutdownGracefully();
	}

	@Override
	public void close() {
		channel.close().syncUninterruptibly();
		shutdown(container, group);
	}
}
