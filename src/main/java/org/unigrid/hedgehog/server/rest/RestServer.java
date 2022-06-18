/*
    Unigrid Hedgehog 
    Copyright © 2021-2022 The Unigrid Foundation

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

import io.netty.channel.Channel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.SneakyThrows;
import org.glassfish.jersey.jackson.internal.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import org.glassfish.jersey.netty.httpserver.NettyHttpContainerProvider;
import org.glassfish.jersey.server.ResourceConfig;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.server.Server;

@Eager @ApplicationScoped
public class RestServer extends Server {
	private Channel rest;

	/* Does not need to be close()'d manually - in fact, doing so messes up Weld */

	private ResourceConfig getResourceConfig() {
		final ResourceConfig config = new ResourceConfig();

		config.register(JacksonJaxbJsonProvider.class);
		config.register(new JsonConfiguration());
		config.register(JsonExceptionMapper.class);
		config.packages(RestServer.class.getPackageName());

		return config;
	}

	@PostConstruct @SneakyThrows
	private void init() {
		final SelfSignedCertificate certificate = new SelfSignedCertificate();
		final String url = String.format("https://%s:%d", RestOptions.getHost(), RestOptions.getPort());

		final SslContext context = SslContextBuilder.forServer(
			certificate.certificate(), certificate.privateKey()
		).build();

		rest = NettyHttpContainerProvider.createServer(alllocate(url).toURI(),
			getResourceConfig(), context, false
		);
	}
}
