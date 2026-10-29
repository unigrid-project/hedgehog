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

import com.maxmind.db.Reader;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/* A country database in the MaxMind DB format, as GeoIP2 and GeoLite2 Country are, read into memory whole. A record is
   decoded into maps and strings, and only the code of the country the network is in counts, not the one it is
   registered in. */
@Slf4j
public final class MmdbCountryLookup implements CountryLookup {
	private final Reader reader;

	public MmdbCountryLookup(InputStream database) throws IOException {
		reader = new Reader(database);
	}

	/* A record the database cannot decode is no country, nor is an IPv6 address in a database of IPv4 alone. Only the
	   class of the failure is logged, as its message may name the address. */
	@Override
	public Optional<String> countryOf(InetAddress address) {
		try {
			return Optional.ofNullable(reader.get(address, Map.class)).map(record -> record.get("country"))
				.filter(Map.class::isInstance).map(country -> ((Map<?, ?>) country).get("iso_code"))
				.filter(String.class::isInstance).map(String.class::cast);
		} catch (IOException | RuntimeException ex) {
			log.warn("Looking up a country failed with {}", ex.getClass().getName());
			return Optional.empty();
		}
	}
}
