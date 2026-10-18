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

package org.unigrid.hedgehog.command.option;

import lombok.Getter;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class S3Options {
	private static final String DEFAULT_PORT_STR = "52885";
	public static final int DEFAULT_PORT = Integer.parseInt(DEFAULT_PORT_STR);

	@Getter @Option(names = "--s3host", scope = CommandLine.ScopeType.INHERIT,
		description = "Hostname or IP the S3 endpoint binds to (defaults to '${DEFAULT-VALUE}').",
		defaultValue = "localhost"
	)
	private static String host;

	@Getter @Option(names = "--s3port", scope = CommandLine.ScopeType.INHERIT,
		description = "S3 endpoint port (defaults to ${DEFAULT-VALUE}).", defaultValue = DEFAULT_PORT_STR
	)
	private static int port;

	@Getter @Option(names = "--s3domain", scope = CommandLine.ScopeType.INHERIT,
		description = "Domain whose subdomains name buckets, as in 'bucket.s3.example.com' (only path-style "
			+ "addressing when unset)."
	)
	private static String domain;

	@Getter @Option(names = "--s3plain", scope = CommandLine.ScopeType.INHERIT,
		description = "Serve S3 over plain HTTP instead of TLS, for local tools that cannot trust a self-signed "
			+ "certificate."
	)
	private static boolean plain;
}
