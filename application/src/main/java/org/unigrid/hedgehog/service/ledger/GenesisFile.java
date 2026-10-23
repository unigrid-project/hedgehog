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

package org.unigrid.hedgehog.service.ledger;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Digests;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.Ledger;
import org.unigrid.hedgehog.ledger.LedgerGenesis;
import org.unigrid.hedgehog.ledger.ValidatorSet;

/* The genesis of the chain as the foundation publishes it, one JSON object with exactly these fields:

   {"time": 1700000000000, "roundMillis": 3600000, "validators": ["<64 hex>", ...], "snapshotHash": "<128 hex>",
    "snapshotTotal": 12345678900000000}

   time is Unix milliseconds and starts the first validator round, roundMillis the length of a round in milliseconds
   and at least a minute, validators are the Ed25519 public keys in the order that decides who proposes, snapshotHash
   is the SHA-512 of the bootstrap snapshot and snapshotTotal the sum of its balances in units of 1e-8 coins. A field
   that is missing, repeated or not in the list is refused, so that a typo cannot pass. It is read from the file
   given with --ledger-genesis, else from the bundled resource /ledger-genesis.json when the build holds one.

   Messages name the file and the field and never copy the content: the file is public, but an operator can point
   the option at the wrong file, such as a key file. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class GenesisFile {
	/* A validator takes a block dated up to MAX_CLOCK_SKEW_MILLIS ahead of its clock, so in a shorter round a vote
	   would race the end of its round against the clocks of the others */
	public static final long MIN_ROUND_MILLIS = 2 * Ledger.MAX_CLOCK_SKEW_MILLIS;

	private static final String BUNDLED = "/ledger-genesis.json";
	/* A genesis of 1000 validators is about 70 KB, so this leaves room and still stops a file that is no genesis */
	private static final int MAX_BYTES = 1 << 20;
	private static final int MAX_SHOWN = 40;
	private static final String TIME = "time";
	private static final String ROUND_MILLIS = "roundMillis";
	private static final String VALIDATORS = "validators";
	private static final String SNAPSHOT_HASH = "snapshotHash";
	private static final String SNAPSHOT_TOTAL = "snapshotTotal";
	private static final Set<String> FIELDS = Set.of(TIME, ROUND_MILLIS, VALIDATORS, SNAPSHOT_HASH, SNAPSHOT_TOTAL);
	private static final HexFormat HEX = HexFormat.of();
	private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
	private static final ObjectMapper JSON = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();
	private static final DefaultIndenter INDENT = new DefaultIndenter("  ", "\n");
	private static final DefaultPrettyPrinter PRINTER = new DefaultPrettyPrinter()
		.withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))
		.withObjectIndenter(INDENT).withArrayIndenter(INDENT);

	public static Optional<LedgerGenesis> load(Optional<Path> override) {
		return load(override, BUNDLED);
	}

	static Optional<LedgerGenesis> load(Optional<Path> override, String resource) {
		return override.map(GenesisFile::fromFile).or(() -> fromResource(resource));
	}

	public static void write(Path file, LedgerGenesis genesis) throws IOException {
		final ObjectNode root = JSON.createObjectNode().put(TIME, genesis.getTime())
			.put(ROUND_MILLIS, genesis.getRoundMillis());
		final ArrayNode validators = root.putArray(VALIDATORS);

		genesis.getValidators().keys().forEach(key -> validators.add(HEX.formatHex(key.bytes())));
		root.put(SNAPSHOT_HASH, HEX.formatHex(genesis.getSnapshotHash())).put(SNAPSHOT_TOTAL,
			genesis.getSnapshotTotal()
		);
		Files.writeString(file, JSON.writer(PRINTER).writeValueAsString(root) + "\n");
	}

	/* What operators compare to know they are on the same chain */
	public static String chainIdHex(LedgerGenesis genesis) {
		return HEX.formatHex(genesis.chainId());
	}

	/* The attributes are read through links, so a link to a directory or a device is refused like the target is */
	private static LedgerGenesis fromFile(Path file) {
		try {
			if (!Files.readAttributes(file, BasicFileAttributes.class).isRegularFile()) {
				throw refused(file.toString(), "is not a regular file");
			}

			try (InputStream in = Files.newInputStream(file)) {
				return parse(file.toString(), in);
			}
		} catch (IOException ex) {
			throw new IllegalArgumentException("Cannot read the ledger genesis " + file + ": " + ex, ex);
		}
	}

	private static Optional<LedgerGenesis> fromResource(String resource) {
		try (InputStream in = GenesisFile.class.getResourceAsStream(resource)) {
			return Objects.isNull(in) ? Optional.empty() : Optional.of(parse("resource " + resource, in));
		} catch (IOException ex) {
			throw new IllegalArgumentException("Cannot read the ledger genesis resource " + resource + ": " + ex,
				ex
			);
		}
	}

	/* The size a file claims cannot be trusted (a device says 0), so what is read is bounded instead */
	private static LedgerGenesis parse(String origin, InputStream in) throws IOException {
		final byte[] json = in.readNBytes(MAX_BYTES + 1);

		if (json.length > MAX_BYTES) {
			throw refused(origin, "is over " + MAX_BYTES + " bytes");
		}

		return new Source(origin, tree(origin, json)).genesis();
	}

	/* The parser quotes the text it chokes on, so its message is neither shown nor chained */
	private static JsonNode tree(String origin, byte[] json) {
		try {
			return JSON.readTree(json);
		} catch (JsonProcessingException ex) {
			throw refused(origin, "is not valid JSON, repeats a field or has text after the object"
				+ at(ex.getLocation()));
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String at(JsonLocation location) {
		return Objects.isNull(location) ? "" : " (line " + location.getLineNr() + ", column "
			+ location.getColumnNr() + ")";
	}

	private static IllegalArgumentException refused(String origin, String problem) {
		return new IllegalArgumentException("The ledger genesis " + origin + ": " + problem);
	}

	private static String shown(String name) {
		return StringUtils.abbreviate(CONTROL.matcher(name).replaceAll("?"), MAX_SHOWN);
	}

	private record Source(String origin, JsonNode root) {
		LedgerGenesis genesis() {
			requireKnownFields();

			return new LedgerGenesis(whole(TIME, 0, Long.MAX_VALUE),
				whole(ROUND_MILLIS, MIN_ROUND_MILLIS, Long.MAX_VALUE), validators(),
				bytes(SNAPSHOT_HASH, field(SNAPSHOT_HASH), Digests.HASH_SIZE),
				whole(SNAPSHOT_TOTAL, 0, Long.MAX_VALUE)
			);
		}

		private void requireKnownFields() {
			if (!root.isObject()) {
				throw refused("must hold one JSON object");
			}

			root.fieldNames().forEachRemaining(name -> {
				if (!FIELDS.contains(name)) {
					throw refused("unknown field '" + shown(name) + "'");
				}
			});
		}

		private JsonNode field(String name) {
			return Optional.ofNullable(root.get(name)).orElseThrow(() -> refused("'" + name + "' is missing"));
		}

		private long whole(String name, long min, long max) {
			final JsonNode node = field(name);

			if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < min
				|| node.longValue() > max) {
				throw refused("'" + name + "' must be a whole number from " + min + " to " + max);
			}

			return node.longValue();
		}

		private ValidatorSet validators() {
			final JsonNode list = field(VALIDATORS);

			if (!list.isArray() || list.isEmpty() || list.size() > ValidatorSet.MAX_SIZE) {
				throw refused("'" + VALIDATORS + "' must be a list of 1 to " + ValidatorSet.MAX_SIZE
					+ " keys");
			}

			final Set<AccountKey> keys = new LinkedHashSet<>();

			for (int index = 0; index < list.size(); index++) {
				final String label = VALIDATORS + "[" + index + "]";

				if (!keys.add(validator(label, list.get(index)))) {
					throw refused("'" + label + "' repeats an earlier key");
				}
			}

			return new ValidatorSet(List.copyOf(keys));
		}

		/* A validator stays for good, and a key that is no public key would have every node check signatures against
		   it for as long as the chain runs */
		private AccountKey validator(String label, JsonNode node) {
			final AccountKey key = new AccountKey(bytes(label, node, AccountKey.SIZE));

			if (!Ed25519.isPublicKey(key)) {
				throw refused("'" + label + "' is not an Ed25519 public key");
			}

			return key;
		}

		private byte[] bytes(String label, JsonNode node, int size) {
			if (!node.isTextual() || !Pattern.matches("\\p{XDigit}{" + size * 2 + "}", node.textValue())) {
				throw refused("'" + label + "' must be " + size * 2 + " hexadecimal characters");
			}

			return HEX.parseHex(node.textValue());
		}

		private IllegalArgumentException refused(String problem) {
			return GenesisFile.refused(origin, problem);
		}
	}
}
