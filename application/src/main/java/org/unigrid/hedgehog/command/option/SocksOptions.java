/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

/* Long names only: the short letters belong to the other options of the daemon and its subcommands */
public class SocksOptions {
	public static final int OFF = 0;
	static final int MAX_PORT = 0xFFFF;

	@Getter @Option(names = "--socks-host", scope = CommandLine.ScopeType.INHERIT,
		description = "Hostname or IP the SOCKS5 proxy binds to (defaults to the loopback address "
		+ "'${DEFAULT-VALUE}'). The proxy has no authentication, so any other address offers it to the network.",
		defaultValue = "127.0.0.1"
	)
	private static String host;

	@Getter @Option(names = "--socks-port", scope = CommandLine.ScopeType.INHERIT, converter = PortConverter.class,
		description = "SOCKS5 proxy port from 0 to 65535, 0 for no proxy (defaults to ${DEFAULT-VALUE}).",
		defaultValue = "0"
	)
	private static int port;

	/* Refused while parsing, so a port no socket can have never reaches the bind of a running daemon */
	private static final class PortConverter implements CommandLine.ITypeConverter<Integer> {
		@Override
		public Integer convert(String value) {
			final int port = Integer.parseInt(value);

			if (port < OFF || port > MAX_PORT) {
				throw new CommandLine.TypeConversionException("Not between " + OFF + " and " + MAX_PORT);
			}

			return port;
		}
	}
}
