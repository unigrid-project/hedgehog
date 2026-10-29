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
import java.util.Locale;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.jqwik.Expect;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class SocksOptionsTest {
	private static CommandLine hedgehog() {
		return new CommandLine(Hedgehog.class);
	}

	@Provide
	public Arbitrary<String> countries() {
		return Arbitraries.of(Locale.getISOCountries());
	}

	@Example
	public void shouldKeepTheProxyOffOnLoopbackByDefault() {
		hedgehog().parseArgs("daemon");
		assertThat(SocksOptions.getHost(), is("127.0.0.1"));
		assertThat(SocksOptions.getPort(), is(SocksOptions.OFF));
	}

	@Property(tries = 20)
	public void shouldTakeTheHostAndPortOfTheDaemon(@ForAll @IntRange(min = 1, max = 0xFFFF) int port) {
		hedgehog().parseArgs("daemon", "--socks-host", "0.0.0.0", "--socks-port", Integer.toString(port));
		assertThat(SocksOptions.getHost(), is("0.0.0.0"));
		assertThat(SocksOptions.getPort(), is(port));
	}

	@Example
	public void shouldTakeThePortsAtTheBounds() {
		hedgehog().parseArgs("daemon", "--socks-port", Integer.toString(SocksOptions.MAX_PORT));
		assertThat(SocksOptions.getPort(), is(SocksOptions.MAX_PORT));
		hedgehog().parseArgs("daemon", "--socks-port", Integer.toString(SocksOptions.OFF));
		assertThat(SocksOptions.getPort(), is(SocksOptions.OFF));
	}

	@Example
	public void shouldRefuseAPortNoSocketCanHave() {
		for (int port : new int[] { SocksOptions.OFF - 1, SocksOptions.MAX_PORT + 1, Integer.MIN_VALUE }) {
			final String message = Expect.assertThrows(ParameterException.class, () -> hedgehog().parseArgs("daemon",
				"--socks-port", Integer.toString(port))).getMessage();

			assertThat(message, containsString("--socks-port"));
			assertThat(message, containsString("Not between 0 and 65535"));
		}
	}

	@Example
	public void shouldNameNoCountryByDefault() {
		hedgehog().parseArgs("daemon");
		assertThat(SocksOptions.getCountry(), is(Optional.empty()));
	}

	@Property(tries = 20)
	public void shouldTakeTheDefaultCountryInUpperCase(@ForAll("countries") String code, @ForAll boolean lower) {
		hedgehog().parseArgs("daemon", "--socks-country", lower ? code.toLowerCase(Locale.ROOT) : code);
		assertThat(SocksOptions.getCountry(), is(Optional.of(code)));
	}

	@Example
	public void shouldTakeAnEmptyCountryForNone() {
		hedgehog().parseArgs("daemon", "--socks-country", "SE");
		hedgehog().parseArgs("daemon", "--socks-country", "");
		assertThat(SocksOptions.getCountry(), is(Optional.empty()));
	}

	@Example
	public void shouldNotKeepTheCountryOfAnEarlierParse() {
		hedgehog().parseArgs("daemon", "--socks-country", "SE");
		hedgehog().parseArgs("daemon");
		assertThat(SocksOptions.getCountry(), is(Optional.empty()));
	}

	@Example
	public void refusesAnUnknownDefaultCountry() {
		for (String code : new String[] { "XX", "SWE", "S", "S1", "\u00e5s" }) {
			final String message = Expect.assertThrows(ParameterException.class, () -> hedgehog().parseArgs("daemon",
				"--socks-country", code)).getMessage();

			assertThat(message, containsString("--socks-country"));
			assertThat(message, containsString("ISO 3166-1 alpha-2"));
		}
	}

	@Example
	public void shouldReadTheGeoipDatabaseOfTheDataDirectoryByDefault() {
		hedgehog().parseArgs("daemon");
		assertThat(SocksOptions.getGeoip(), is(ApplicationDirectory.create().getUserDataDir().resolve("geoip")
			.resolve("country.mmdb")));
	}

	@Example
	public void shouldReadTheGeoipDatabaseItIsGivenForOneParseOnly() {
		hedgehog().parseArgs("daemon", "--socks-geoip", "/srv/geoip/GeoLite2-Country.mmdb");
		assertThat(SocksOptions.getGeoip(), is(Path.of("/srv/geoip/GeoLite2-Country.mmdb")));

		hedgehog().parseArgs("daemon");
		assertThat(SocksOptions.getGeoip(), is(ApplicationDirectory.create().getUserDataDir().resolve("geoip")
			.resolve("country.mmdb")));
	}

	@Example
	public void shouldOfferLongNamesOnly() {
		final CommandSpec daemon = hedgehog().getSubcommands().get("daemon").getCommandSpec();

		assertThat(daemon.findOption("--socks-host").names(), arrayContaining("--socks-host"));
		assertThat(daemon.findOption("--socks-port").names(), arrayContaining("--socks-port"));
		assertThat(daemon.findOption("--socks-country").names(), arrayContaining("--socks-country"));
		assertThat(daemon.findOption("--socks-geoip").names(), arrayContaining("--socks-geoip"));
	}
}
