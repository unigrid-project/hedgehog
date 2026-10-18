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

import java.nio.file.Path;
import java.time.Duration;
import lombok.Getter;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class S3Options {
	private static final String DEFAULT_PORT_STR = "52885";
	public static final int DEFAULT_PORT = Integer.parseInt(DEFAULT_PORT_STR);
	private static final String DEFAULT_CACHE_SIZE_STR = "1073741824";
	public static final long DEFAULT_CACHE_SIZE = Long.parseLong(DEFAULT_CACHE_SIZE_STR);

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

	@Option(names = "--s3cachedir", scope = CommandLine.ScopeType.INHERIT,
		description = "Directory of the local cache of S3 objects (defaults to s3/cache in the data directory)."
	)
	private static Path cacheDirectory;

	@Getter @Option(names = "--s3cachesize", scope = CommandLine.ScopeType.INHERIT,
		description = "Bytes the local cache of S3 objects may hold (defaults to ${DEFAULT-VALUE}).",
		defaultValue = DEFAULT_CACHE_SIZE_STR
	)
	private static long cacheSize;

	@Getter @Option(names = "--s3cachehalflife", scope = CommandLine.ScopeType.INHERIT,
		description = "Time after which a read counts half as much toward keeping an object cached, as an ISO-8601 "
			+ "duration (defaults to ${DEFAULT-VALUE}).", defaultValue = "P1D"
	)
	private static Duration cacheHalfLife;

	public static Path getCacheDirectory() {
		return cacheDirectory != null ? cacheDirectory
			: ApplicationDirectory.create().getUserDataDir().resolve("s3").resolve("cache");
	}
}
