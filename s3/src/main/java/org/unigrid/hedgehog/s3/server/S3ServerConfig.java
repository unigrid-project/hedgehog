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

import java.time.Duration;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

@Value
@Builder
public class S3ServerConfig {
	@NonNull private final String host;
	private final int port;
	private final String domain;
	@Builder.Default private final boolean tls = true;

	/* A request body stops being read from its socket once this much of it waits for the resource. Large enough that
	   a resource reading in big blocks seldom waits for the network, small enough for many uploads at once. */
	@Builder.Default private final int bodyHighWater = 1024 * 1024;

	/* Reading resumes below this mark, far enough under the high one that reading is not toggled on every packet */
	@Builder.Default private final int bodyLowWater = 256 * 1024;

	/* A request or response that makes no progress for this long is cut, along with its connection */
	@Builder.Default @NonNull private final Duration stallTimeout = Duration.ofMinutes(1);

	/* Requests to a subdomain of it name their bucket in the host, as virtual-host style clients send them */
	public Optional<String> getDomain() {
		return Optional.ofNullable(domain);
	}
}
