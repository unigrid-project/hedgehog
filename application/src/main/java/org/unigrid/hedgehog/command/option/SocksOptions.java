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
import java.util.Optional;
import lombok.Getter;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.proxy.socks.Country;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/* Long names only: the short letters belong to the other options of the daemon and its subcommands */
public class SocksOptions {
	public static final int OFF = 0;
	static final int MAX_PORT = 0xFFFF;

	@Getter @Option(names = "--socks-host", scope = CommandLine.ScopeType.INHERIT,
		description = "Hostname or IP the SOCKS5 proxy binds to (defaults to the loopback address "
		+ "'${DEFAULT-VALUE}'). The proxy has no authentication, so any other address offers it to the network. "
		+ "Ignored while --socks-port is 0.",
		defaultValue = "127.0.0.1"
	)
	private static String host;

	@Getter @Option(names = "--socks-port", scope = CommandLine.ScopeType.INHERIT, converter = PortConverter.class,
		description = "SOCKS5 proxy port from 0 to 65535, 0 for no proxy (defaults to ${DEFAULT-VALUE}).",
		defaultValue = "0"
	)
	private static int port;

	@Option(names = "--socks-country", scope = CommandLine.ScopeType.INHERIT, converter = CountryConverter.class,
		description = "ISO 3166-1 alpha-2 code of the country to choose the exit in for a SOCKS5 connection that "
		+ "names none as its username (defaults to any country).",
		defaultValue = ""
	)
	private static String country;

	/* An empty default, so that a database one parse names is not taken as the default of the next */
	@Option(names = "--socks-geoip", scope = CommandLine.ScopeType.INHERIT,
		description = "Country database in the MMDB format to find the country of each gridnode in (defaults to "
		+ "geoip/country.mmdb in the data directory), such as IP to Country Lite of DB-IP, licensed CC BY 4.0 with "
		+ "attribution to DB-IP.com required. Any MMDB file whose records hold country.iso_code works. Without it, "
		+ "a SOCKS5 connection that names a country fails.",
		defaultValue = ""
	)
	private static Path geoip;

	/* None unless the option names a country, which the converter has made upper case */
	public static Optional<String> getCountry() {
		return Optional.ofNullable(country).filter(code -> !code.isEmpty());
	}

	public static Path getGeoip() {
		return geoip == null || geoip.toString().isEmpty()
			? ApplicationDirectory.create().getUserDataDir().resolve("geoip").resolve("country.mmdb") : geoip;
	}

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

	/* An unknown code is refused while parsing, so that a typo never silently means any country */
	private static final class CountryConverter implements CommandLine.ITypeConverter<String> {
		@Override
		public String convert(String value) {
			try {
				return Country.of(value).orElse("");
			} catch (IllegalArgumentException ex) {
				throw new CommandLine.TypeConversionException(ex.getMessage());
			}
		}
	}
}
