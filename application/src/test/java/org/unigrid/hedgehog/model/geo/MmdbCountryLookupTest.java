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

package org.unigrid.hedgehog.model.geo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class MmdbCountryLookupTest {
	/* The fake country database of the MaxMind DB repository, whose networks are listed next to it there */
	public static final String FIXTURE = "/geoip/GeoIP2-Country-Test.mmdb";

	@SneakyThrows
	public static CountryLookup fixture() {
		try (InputStream database = MmdbCountryLookupTest.class.getResourceAsStream(FIXTURE)) {
			return new MmdbCountryLookup(database);
		}
	}

	@Example
	public void readsTheIsoCode() {
		final CountryLookup lookup = fixture();

		/* The country the network is in, not the one it is registered in, which is DE */
		assertThat(lookup.countryOf(InetAddress.ofLiteral("89.160.20.112")), equalTo(Optional.of("SE")));
		assertThat(lookup.countryOf(InetAddress.ofLiteral("2001:218::1")), equalTo(Optional.of("JP")));

		/* A network the database knows only traits of, and an address it does not hold */
		assertThat(lookup.countryOf(InetAddress.ofLiteral("214.1.1.1")), equalTo(Optional.empty()));
		assertThat(lookup.countryOf(InetAddress.ofLiteral("192.0.2.1")), equalTo(Optional.empty()));
	}

	@Example
	public void readsTheCountryOfTheAddressAHostNameNames() {
		final CountryLookup lookup = fixture();

		assertThat(lookup.countryOfHost("89.160.20.112:52883"), equalTo(Optional.of("SE")));
		assertThat(lookup.countryOfHost("[2001:218::1]:52883"), equalTo(Optional.of("JP")));
		assertThat(lookup.countryOfHost("81.2.69.160"), equalTo(Optional.of("GB")));
	}

	/* A name is never resolved, so the lookup is not even asked */
	@Example
	public void hostNamesHaveNoCountry() {
		final List<InetAddress> asked = new CopyOnWriteArrayList<>();
		final CountryLookup spy = address -> {
			asked.add(address);
			return Optional.of("SE");
		};

		assertThat(spy.countryOfHost("gn.example.org:52883"), equalTo(Optional.empty()));
		assertThat(spy.countryOfHost("localhost:52883"), equalTo(Optional.empty()));
		assertThat(spy.countryOfHost("[gn.example.org]:52883"), equalTo(Optional.empty()));
		assertThat(spy.countryOfHost(""), equalTo(Optional.empty()));
		assertThat(spy.countryOfHost(null), equalTo(Optional.empty()));
		assertThat(asked, empty());
	}

	@Example
	public void noneKnowsNoCountry() {
		assertThat(CountryLookup.NONE.countryOf(InetAddress.ofLiteral("89.160.20.112")), equalTo(Optional.empty()));
		assertThat(CountryLookup.NONE.countryOfHost("89.160.20.112:52883"), equalTo(Optional.empty()));
	}

	/* A stream that never ends, as a device can be, is refused once it runs past the bound */
	@Example
	public void refusesADatabaseThatRunsPastTheBound() {
		final InputStream endless = new InputStream() {
			@Override
			public int read() {
				return 0;
			}
		};

		final MmdbCountryLookup.TooLargeException refused = assertThrows(MmdbCountryLookup.TooLargeException.class,
			() -> new MmdbCountryLookup(endless, 1024));

		assertThat(refused.size(), equalTo(1025L));
		assertThat(MmdbCountryLookup.MAX_BYTES, equalTo(64L * 1024 * 1024));
	}

	@Example
	public void refusesWhatIsNoDatabase() {
		assertThrows(IOException.class, () -> new MmdbCountryLookup(new ByteArrayInputStream(new byte[64])));
	}
}
