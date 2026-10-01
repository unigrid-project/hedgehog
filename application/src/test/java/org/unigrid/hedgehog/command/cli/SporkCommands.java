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

package org.unigrid.hedgehog.command.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.util.Map;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

/* The arbitraries are plain factories for each test to provide, since jqwik creates a supplier reflectively and a
   test run only opens the packages of the tests it selects */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SporkCommands {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String KEY_HEADER = "privateKey";
	private static final int HEX_KEY_LENGTH = 64;

	public static Optional<MultivaluedMap<String, Object>> signedBy(String key) {
		return Optional.of(new MultivaluedHashMap<>(Map.of(KEY_HEADER, key)));
	}

	@SneakyThrows
	public static JsonNode tree(String json) {
		return MAPPER.readTree(json);
	}

	@SneakyThrows
	public static String json(Object value) {
		return MAPPER.writeValueAsString(value);
	}

	/* The key goes out verbatim for the daemon to judge, so a key that is no hex at all must go out just the same */
	public static Arbitrary<String> keys() {
		return Arbitraries.oneOf(Arbitraries.strings().withChars("0123456789abcdef").ofLength(HEX_KEY_LENGTH),
			Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(HEX_KEY_LENGTH * 2));
	}

	public static Arbitrary<String> reports() {
		return Arbitraries.maps(Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12), Arbitraries.integers())
			.ofMaxSize(5).map(SporkCommands::json);
	}
}
