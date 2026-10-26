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

package org.unigrid.hedgehog.s3.request;

import java.util.List;
import java.util.regex.Pattern;

public final class BucketNames {
	private static final Pattern ALLOWED = Pattern.compile("[a-z0-9.-]{3,63}");
	private static final Pattern IPV4 = Pattern.compile("(\\d{1,3}\\.){3}\\d{1,3}");
	private static final List<String> RESERVED_PREFIXES = List.of("xn--", "sthree-", "amzn-s3-demo-");
	private static final List<String> RESERVED_SUFFIXES = List.of("-s3alias", "--ol-s3", ".mrap", "--x-s3",
		"--table-s3");

	private BucketNames() {
		/* Static helpers only */
	}

	public static boolean valid(final String name) {
		return name != null && hasValidCharacters(name) && hasValidEdges(name) && !name.contains("..")
			&& !isIpv4(name) && !isReserved(name);
	}

	private static boolean hasValidCharacters(final String name) {
		return ALLOWED.matcher(name).matches();
	}

	private static boolean hasValidEdges(final String name) {
		return isAlphanumeric(name.charAt(0)) && isAlphanumeric(name.charAt(name.length() - 1));
	}

	private static boolean isAlphanumeric(final char character) {
		return (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9');
	}

	private static boolean isIpv4(final String name) {
		return IPV4.matcher(name).matches();
	}

	private static boolean isReserved(final String name) {
		return RESERVED_PREFIXES.stream().anyMatch(name::startsWith)
			|| RESERVED_SUFFIXES.stream().anyMatch(name::endsWith);
	}
}
