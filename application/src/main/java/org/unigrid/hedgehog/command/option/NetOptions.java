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

	@Getter @Option(names = "--network-keys", scope = CommandLine.ScopeType.INHERIT,
		description = "Override the default network keys", split = ",",
		defaultValue = "1c133daf1f85987771847f8ddfce77ff16a52abc48569a2e83fd85704c4fdfb4686def3c0b0b51dc57dd9885"
                + "1579b90f62e817d8a8cd448cf5e852ad065b834059018b26142ac78815758eb4c0f8641b9bae185291a1b7e4"
		+ "25d467f0d2ff6cd5580684afdd347737b2e94ef7a86dd1885aa07222e8a2ecd633dabbf3c94725699fabf1,"

		+ "15380913ff0cfd116546f6d5ab6235f7775b111a33447981135ecb26971a53b31220e928e44230e3ff21f571"
		+ "d8ba346d33b72930fe1497698df15d82ab261a887e818dc7b4b8dd90451f6dbdc1e446c27e5645ae483a2ada"
		+ "9a4ef7ed14446c0889e402e993a2d46d78bb43988e3b7853feb676ef29cee894a1a6c10d16cd8bc5b25955,"

		+ "10a5eee29a57d1162446b824e14cbd29e732e3ab9e4f24ed72882bf1626edcadce310c8342cc621f37f59a62"
		+ "94d02cf079ba3aa260526b4f01d680bb3d73bb01846185f49b7f5c6e91f3491a36dd5148be5403d294c078cb"
		+ "88a78d624b970bece17b1e23a049337e1859d72b260595b66ab73d5f8c76626be13c89e4f0c893c42171d3"
	)
	private static String[] networkKeys;
}
