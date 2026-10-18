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

package org.unigrid.hedgehog.server.s3;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.command.option.S3Options;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.s3.server.S3Server;
import org.unigrid.hedgehog.s3.server.S3ServerConfig;
import org.unigrid.hedgehog.service.storage.StorageBlobStore;
import org.unigrid.hedgehog.service.storage.StorageService;

@Eager @ApplicationScoped
public class S3Gateway {
	@Inject
	private StorageService storage;

	private S3Server server;

	static S3ServerConfig config() {
		return S3ServerConfig.builder().host(S3Options.getHost()).port(S3Options.getPort())
			.domain(S3Options.getDomain()).build();
	}

	/* Placeholder credentials until access keys can be created */
	@SneakyThrows
	@PostConstruct
	public void init() {
		server = S3Server.start(config(), new StorageBlobStore(storage), accessKeyId -> Optional.empty());
	}

	@PreDestroy
	public void destroy() {
		server.close();
	}
}
