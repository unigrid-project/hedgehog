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

package org.unigrid.hedgehog.model.gridnode;

import java.util.Optional;
import java.util.Set;
import org.unigrid.hedgehog.command.option.GridnodeOptions;
import org.unigrid.hedgehog.command.option.NetOptions;

public final class GridnodeSetup {
	private static final Set<String> WILDCARDS = Set.of("", "0.0.0.0", "::", "[::]");
	private static final int MAX_PORT = 65535;

	private GridnodeSetup() { }

	public static GridnodeIdentity identity() {
		return Optional.ofNullable(GridnodeOptions.getGridnodeKeyFile()).map(GridnodeIdentity::load)
			.orElseGet(GridnodeIdentity::none);
	}

	public static String announcedAddress() {
		return announcedAddress(NetOptions.getHost(), NetOptions.getPort(),
			Optional.ofNullable(GridnodeOptions.getAnnounceAddress())
		);
	}

	/* Runs before the container starts, so a bad setup stops the daemon with a readable message */
	public static void validate() {
		if (GridnodeOptions.getGridnodeKeyFile() != null) {
			identity();
			announcedAddress();
		}
	}

	static String announcedAddress(String bindHost, int bindPort, Optional<String> announce) {
		return announce.map(GridnodeSetup::checked).orElseGet(() -> bound(bindHost, bindPort));
	}

	private static String bound(String host, int port) {
		if (WILDCARDS.contains(host.strip())) {
			throw new IllegalArgumentException("A gridnode bound to '" + host.strip()
				+ "' must tell others where to reach it: add --announce-address <host>:<port>");
		}

		return host.contains(":") && !host.startsWith("[") ? "[" + host + "]:" + port : host + ":" + port;
	}

	private static String checked(String announce) {
		final String address = announce.strip();
		final int colon = address.lastIndexOf(':');

		if (colon < 1 || !isReachable(address.substring(0, colon), address.substring(colon + 1))) {
			throw new IllegalArgumentException("--announce-address must be <host>:<port> with a host others can "
				+ "reach, not '" + address + "'");
		}

		return address;
	}

	private static boolean isReachable(String host, String portText) {
		return isPort(portText) && !WILDCARDS.contains(host);
	}

	private static boolean isPort(String text) {
		return text.matches("[0-9]{1,5}") && Integer.parseInt(text) >= 1 && Integer.parseInt(text) <= MAX_PORT;
	}
}
