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

package org.unigrid.hedgehog.command.option;

import lombok.Getter;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class NetOptions {
	private static final String DEFAULT_PORT_STR = "52883";
	public static final int DEFAULT_PORT = Integer.parseInt(DEFAULT_PORT_STR);

	@Getter @Option(names = { "-H", "--nethost" }, scope = CommandLine.ScopeType.INHERIT,
		description = "Hostname or IP to bind to (defaults to '${DEFAULT-VALUE}').", defaultValue = "0.0.0.0"
	)
	private static String host;

	@Getter @Option(names = { "-p", "--netport" }, scope = CommandLine.ScopeType.INHERIT,
		description = "Network port (defaults to ${DEFAULT-VALUE}).", defaultValue = DEFAULT_PORT_STR
	)
	private static int port;

	@Getter @Option(names = "--no-seeds", scope = CommandLine.ScopeType.INHERIT,
		description = "Enable/disable seed nodes (enabled by default).", negatable = true, defaultValue = "true"
	)
	private static boolean seeds;
}
