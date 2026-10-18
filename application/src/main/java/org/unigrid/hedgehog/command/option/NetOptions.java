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
		defaultValue = "1caaaca6aab277ee35e87db1145d0122301cc68335cbe4c58748022971636a25e8bbe0b4d72c6f080682ebe0"
		+ "f949e7c880d6a8e1bddfd6caba29425e5c1279d17ff1145d1a950a584564a47d1b029a0d1e85b342b61de3aa"
		+ "ffdd1165748ab5ef206648535d9c29ef59b1dcc98614be7850070a002b6390d99611a4169492beee52d18f,"

		+ "1364b75a3cebc7f522e5e4ce6c1b63d54ac57842025b70c3c6ded0f25ce795c41e557835160b9191be4d90c2"
		+ "64d1f4cd3092ebccf972e46ce8a20300775424df4511b6795f757ca09d536721e40607d22d00b19fedf8d28d"
		+ "4e97e56482debf2fe2d59a9074549a5587185c721deac6694934defa599712b7f278ad496dee103bfbd7f2,"

		+ "1df86219c280a21c40b95446790434f84305e3bdbf6372e2204b2e9a87aac3b7fdc156194e902233618757cf"
		+ "9a82a15868301340646a257f611d59920dc230d927f1189af42babb37e11cd60242b1a04dd1aba1fa2fbe4d9"
		+ "9b8e3bc4044ed56caa11a982cf69fe646eb57c8b454ada5149f4eb6c8d5df4e1da90d0396382faa56c297b,"

		+ "1b48ec767d1102d8381011f694cb455bf6a033dc8de7c1d794ff08d52ab6401f3b4dd73ccdf8f2325f01d791"
		+ "5dde262d8e49b23d8094cbc74c001cd7ac45a78dbe117ba0fc975a3d2aabcd340bdde7204f2664762926dd57"
		+ "4f5fc9b136853166d329b732f45dd9d3a28868370f98cd53cf5d44192a7ac403ebc56d070e260d7fa9f424"
	)
	private static String[] networkKeys;

	@Getter @Option(names = "--retired-network-keys", scope = CommandLine.ScopeType.INHERIT,
		description = "Override the retired network keys, trusted only for signatures in a signature log",
		split = ",",
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
	private static String[] retiredNetworkKeys;
}
