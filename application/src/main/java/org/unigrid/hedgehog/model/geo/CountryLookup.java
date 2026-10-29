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

import java.net.InetAddress;
import java.util.Optional;

/* The country an address is in, as the upper-case ISO 3166-1 alpha-2 code a GeoIP database on this node gives it */
@FunctionalInterface
public interface CountryLookup {
	/* What a node without a database knows */
	CountryLookup NONE = address -> Optional.empty();

	Optional<String> countryOf(InetAddress address);

	/* The country of the host part of a host name with or without its port, and only when that part is an IP
	   literal: a name is never resolved, as its lookup would tell a resolver which gridnodes this node weighs */
	default Optional<String> countryOfHost(String hostName) {
		return hostOf(hostName).flatMap(CountryLookup::literal).flatMap(this::countryOf);
	}

	/* An IPv6 literal with a port is in brackets, which are no part of it */
	private static Optional<String> hostOf(String hostName) {
		if (hostName == null) {
			return Optional.empty();
		}

		if (hostName.startsWith("[")) {
			final int end = hostName.indexOf(']');

			return end < 0 ? Optional.empty() : Optional.of(hostName.substring(1, end));
		}

		final int port = hostName.lastIndexOf(':');

		return Optional.of(port < 0 ? hostName : hostName.substring(0, port));
	}

	private static Optional<InetAddress> literal(String host) {
		try {
			return Optional.of(InetAddress.ofLiteral(host));
		} catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}
}
