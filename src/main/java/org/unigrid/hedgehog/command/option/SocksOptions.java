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

package org.unigrid.hedgehog.command.option;

import lombok.Getter;
import picocli.CommandLine;

public class SocksOptions {
	private static final String DEFAULT_PORT_STR = "52883";
	public static final int DEFAULT_PORT = Integer.parseInt(DEFAULT_PORT_STR);

	@Getter @CommandLine.Option(names = { "-S", "--sockshost" }, scope = CommandLine.ScopeType.INHERIT,
		description = "Hostname or IP to bind to (defaults to '${DEFAULT-VALUE}').", defaultValue = "localhost"
	)
	private static String host;

	@Getter @CommandLine.Option(names = { "-s", "--socksport" }, scope = CommandLine.ScopeType.INHERIT,
		description = "Socks communcation port (defaults to ${DEFAULT-VALUE}).", defaultValue = DEFAULT_PORT_STR
	)
	private static int port;
	
	@Getter @CommandLine.Option(names = { "-A", "--socksusername" }, scope = CommandLine.ScopeType.INHERIT,
		description = "Username to bind to (defaults to '${DEFAULT-VALUE}').", defaultValue = "username"
	)
	private static String username;
	
	@Getter @CommandLine.Option(names = { "-a", "--sockspassword" }, scope = CommandLine.ScopeType.INHERIT,
		description = "Password to bind to (defaults to '${DEFAULT-VALUE}').", defaultValue = "password"
	)
	private static String password;
}
