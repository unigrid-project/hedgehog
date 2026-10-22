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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Digests;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.LedgerGenesis;
import org.unigrid.hedgehog.ledger.ValidatorSet;

public class GenesisFileTest {
	private static final String FILE = "/genesis/ledger-genesis.json";
	private static final String RESOURCE = "/ledger-genesis-test.json";
	private static final String MALFORMED_RESOURCE = "/ledger-genesis-malformed-test.json";
	private static final int MEBIBYTE = 1 << 20;
	private static final HexFormat HEX = HexFormat.of();
	/* The public keys of the seeds 1 and 2, as `ledger-genesis-test.json` holds them */
	private static final String FIRST_KEY = "b1f804dabc68274c1233995c5a9119b56935bcdd83b7de07ec726dcedc4e9ce7";
	private static final String SECOND_KEY = "6a0f6a283a8e4e86d2a3d60ef9e37ec33f2ab6071a30e0a477735128e7571eb0";
	private static final String NO_POINT = "02" + "00".repeat(AccountKey.SIZE - 1);
	private static final String MIXED_ORDER_POINT = "03" + "00".repeat(AccountKey.SIZE - 1);
	private static final String HASH = "ab".repeat(Digests.HASH_SIZE);

	private record Damage(String label, String field, String value) {
		Damage(String field, String value) {
			this(field, field, value);
		}
	}

	@Provide
	public Arbitrary<LedgerGenesis> provideGenesis() {
		final Arbitrary<List<AccountKey>> keys = Arbitraries.integers().map(GenesisFileTest::keyOf).list()
			.ofMinSize(1).ofMaxSize(20).uniqueElements();

		return Combinators.combine(Arbitraries.longs().greaterOrEqual(0), Arbitraries.integers().greaterOrEqual(1),
			keys, Arbitraries.bytes().array(byte[].class).ofSize(Digests.HASH_SIZE),
			Arbitraries.longs().greaterOrEqual(0)
		).as((time, round, validators, hash, total) -> new LedgerGenesis(time, round, new ValidatorSet(validators),
			hash, total)
		);
	}

	@Provide
	public Arbitrary<String> provideField() {
		return Arbitraries.of(validFields().keySet());
	}

	@Provide
	public Arbitrary<Damage> provideDamage() {
		return Arbitraries.of(damages());
	}

	@Provide
	public Arbitrary<String> provideUnknownField() {
		return Arbitraries.of("tiem", "Time", "validator", "snapshot_hash", "comment", "round", "snapshotTotal2");
	}

	@Provide
	public Arbitrary<String> provideNotAnObject() {
		return Arbitraries.of("", " ", "\n", "[]", "5", "null", "true", "\"text\"", "[" + document(validFields()) + "]");
	}

	@Provide
	public Arbitrary<String> provideNotJson() {
		final String valid = document(validFields());

		return Arbitraries.of("{", "{\"time\":", "nonsense", "{'time': 1}", "{\"time\": 1,}", valid + " {}",
			valid + " x", valid + valid, valid.substring(0, valid.length() - 1), "\u0000" + valid
		);
	}

	private static List<Damage> damages() {
		return List.of(
			new Damage("time", quoted("1700000000000")), new Damage("time", "1.5"), new Damage("time", "1e3"),
			new Damage("time", "true"), new Damage("time", "null"), new Damage("time", "[]"),
			new Damage("time", "{}"), new Damage("time", "-1"), new Damage("time", "9223372036854775808"),
			new Damage("roundLength", quoted("100")), new Damage("roundLength", "100.0"),
			new Damage("roundLength", "null"), new Damage("roundLength", "[]"), new Damage("roundLength", "0"),
			new Damage("roundLength", "-1"), new Damage("roundLength", "2147483648"),
			new Damage("validators", quoted(FIRST_KEY)), new Damage("validators", "{}"),
			new Damage("validators", "5"), new Damage("validators", "null"), new Damage("validators", "[]"),
			new Damage("validators[0]", "validators", "[5]"), new Damage("validators[0]", "validators", "[null]"),
			new Damage("validators[0]", "validators", "[[]]"),
			new Damage("validators[1]", "validators", list(FIRST_KEY, "22".repeat(AccountKey.SIZE - 1))),
			new Damage("validators[1]", "validators", list(FIRST_KEY, SECOND_KEY + "2")),
			new Damage("validators[1]", "validators", list(FIRST_KEY, SECOND_KEY + "22")),
			new Damage("validators[0]", "validators", list("g" + FIRST_KEY.substring(1))),
			new Damage("validators[0]", "validators", list("0x" + FIRST_KEY.substring(2))),
			new Damage("validators[0]", "validators", list("")),
			new Damage("validators[2]", "validators", list(FIRST_KEY, SECOND_KEY, FIRST_KEY)),
			new Damage("validators[1]", "validators", list(FIRST_KEY, FIRST_KEY.toUpperCase())),
			new Damage("validators[1]", "validators", list(FIRST_KEY, NO_POINT)),
			new Damage("validators[0]", "validators", list(MIXED_ORDER_POINT, SECOND_KEY)),
			new Damage("snapshotHash", "null"), new Damage("snapshotHash", "5"), new Damage("snapshotHash", "[]"),
			new Damage("snapshotHash", quoted("")), new Damage("snapshotHash", quoted(HASH.substring(1))),
			new Damage("snapshotHash", quoted(HASH + "a")), new Damage("snapshotHash", quoted(HASH + "ab")),
			new Damage("snapshotHash", quoted("g" + HASH.substring(1))),
			new Damage("snapshotHash", quoted("0x" + HASH.substring(2))),
			new Damage("snapshotTotal", "-1"), new Damage("snapshotTotal", "9223372036854775808"),
			new Damage("snapshotTotal", quoted("1")), new Damage("snapshotTotal", "1.5"),
			new Damage("snapshotTotal", "null"), new Damage("snapshotTotal", "false")
		);
	}

	private static Map<String, String> validFields() {
		final Map<String, String> fields = new LinkedHashMap<>();

		fields.put("time", "1700000000000");
		fields.put("roundLength", "100");
		fields.put("validators", list(FIRST_KEY, SECOND_KEY));
		fields.put("snapshotHash", quoted(HASH));
		fields.put("snapshotTotal", "12345678900000000");
		return fields;
	}

	private static String quoted(String text) {
		return "\"" + text + "\"";
	}

	private static String list(String... keys) {
		return Stream.of(keys).map(GenesisFileTest::quoted).collect(Collectors.joining(",", "[", "]"));
	}

	private static String document(Map<String, String> fields) {
		return fields.entrySet().stream().map(field -> quoted(field.getKey()) + ":" + field.getValue())
			.collect(Collectors.joining(",", "{", "}"));
	}

	private static String documentWith(String field, String value) {
		final Map<String, String> fields = validFields();

		fields.put(field, value);
		return document(fields);
	}

	private static String documentWithout(String field) {
		final Map<String, String> fields = validFields();

		fields.remove(field);
		return document(fields);
	}

	private static String documentOfKeys(int count) {
		return documentWith("validators",
			list(IntStream.range(0, count).mapToObj(n -> keyOf(n).toString()).toArray(String[]::new))
		);
	}

	private static AccountKey keyOf(int n) {
		return Ed25519.publicKey(ByteBuffer.allocate(Ed25519.SEED_SIZE).putInt(n).array());
	}

	private static LedgerGenesis bundled() {
		return new LedgerGenesis(1700000000000L, 100, new ValidatorSet(List.of(AccountKey.fromHex(FIRST_KEY),
			AccountKey.fromHex(SECOND_KEY))), HEX.parseHex(HASH), 12345678900000000L
		);
	}

	@SneakyThrows
	private static LedgerGenesis loaded(String content) {
		try (FileSystem fs = LedgerKeyFixtures.plainFileSystem()) {
			final Path file = LedgerKeyFixtures.write(fs, FILE, content, null);

			return GenesisFile.load(Optional.of(file)).orElseThrow();
		}
	}

	@SneakyThrows
	private static String messageOfLoading(String content) {
		try (FileSystem fs = LedgerKeyFixtures.plainFileSystem()) {
			return messageOfLoading(LedgerKeyFixtures.write(fs, FILE, content, null));
		}
	}

	private static String messageOfLoading(Path file) {
		final String message = assertThrows(IllegalArgumentException.class,
			() -> GenesisFile.load(Optional.of(file))
		).getMessage();

		assertThat(message, containsString(file.toString()));
		return message;
	}

	/* The refusal is the check's own: opening a directory fails on its own with another message */
	private static String notRegular(Path file) {
		return "The ledger genesis " + file + ": is not a regular file";
	}

	@SneakyThrows
	private static String resourceText(String resource) {
		try (InputStream in = GenesisFileTest.class.getResourceAsStream(resource)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Property(tries = 40)
	@SneakyThrows
	public void shouldReadBackWhatItWrites(@ForAll("provideGenesis") LedgerGenesis genesis) {
		try (FileSystem fs = LedgerKeyFixtures.plainFileSystem()) {
			final Path file = Files.createDirectories(fs.getPath(FILE).getParent()).resolve("written.json");

			GenesisFile.write(file, genesis);

			final LedgerGenesis read = GenesisFile.load(Optional.of(file)).orElseThrow();

			assertThat(read, equalTo(genesis));
			assertThat(GenesisFile.chainIdHex(read), equalTo(GenesisFile.chainIdHex(genesis)));
		}
	}

	@Example
	@SneakyThrows
	public void shouldWriteTheFieldsInTheDocumentedOrderWithLowerCaseHex() {
		try (FileSystem fs = LedgerKeyFixtures.plainFileSystem()) {
			final Path file = Files.createDirectories(fs.getPath(FILE).getParent()).resolve("written.json");

			GenesisFile.write(file, bundled());
			assertThat(Files.readString(file), equalTo(resourceText(RESOURCE)));
		}
	}

	@Property(tries = 20)
	public void shouldPrintTheChainIdInLowerHex(@ForAll("provideGenesis") LedgerGenesis genesis) {
		assertThat(GenesisFile.chainIdHex(genesis), equalTo(HEX.formatHex(genesis.chainId())));
		assertThat(GenesisFile.chainIdHex(genesis), matchesPattern("[0-9a-f]{" + Digests.HASH_SIZE * 2 + "}"));
	}

	@Example
	public void shouldReadTheDocumentedFormat() {
		assertThat(loaded(document(validFields())), equalTo(bundled()));
	}

	@Example
	public void shouldReadHexInEitherCase() {
		final String upper = documentWith("validators", list(FIRST_KEY.toUpperCase(), SECOND_KEY.toUpperCase()));

		assertThat(loaded(documentWith("snapshotHash", quoted(HASH.toUpperCase()))), equalTo(bundled()));
		assertThat(loaded(upper), equalTo(bundled()));
	}

	@Example
	public void shouldAcceptTheLargestValidatorSet() {
		assertThat(loaded(documentOfKeys(ValidatorSet.MAX_SIZE)).getValidators().size(),
			equalTo(ValidatorSet.MAX_SIZE)
		);
	}

	@Example
	public void shouldRefuseOneValidatorMoreThanASetCanHold() {
		assertThat(messageOfLoading(documentOfKeys(ValidatorSet.MAX_SIZE + 1)), containsString("'validators'"));
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void shouldRefuseAMissingFieldAndNameIt(@ForAll("provideField") String field) {
		assertThat(messageOfLoading(documentWithout(field)), containsString("'" + field + "' is missing"));
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void shouldRefuseAFieldThatIsWrongAndNameIt(@ForAll("provideDamage") Damage damage) {
		assertThat(damage.toString(), messageOfLoading(documentWith(damage.field(), damage.value())),
			containsString("'" + damage.label() + "'")
		);
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void shouldRefuseAFieldThatIsNotInTheFormat(@ForAll("provideUnknownField") String field) {
		final String message = messageOfLoading(documentWith(field, "1"));

		assertThat(message, containsString("unknown field '" + field + "'"));
	}

	@Example
	public void shouldNeverRepeatAnUnknownFieldNameInFull() {
		final String name = "x\ny" + "z".repeat(10000);
		final String message = messageOfLoading(documentWith(name.replace("\n", "\\n"), "1"));

		assertThat(message, containsString("unknown field 'x?yzz"));
		assertThat(message.length(), lessThan(200));
	}

	@Example
	public void shouldRefuseAFieldGivenTwice() {
		final String twice = "{\"time\":5," + document(validFields()).substring(1);

		assertThat(messageOfLoading(twice), containsString("JSON"));
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void shouldRefuseAnythingButOneObject(@ForAll("provideNotAnObject") String content) {
		assertThat(messageOfLoading(content), containsString("JSON object"));
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void shouldRefuseWhatIsNotJson(@ForAll("provideNotJson") String content) {
		assertThat(messageOfLoading(content), containsString("JSON"));
	}

	@Example
	public void shouldNeverRepeatWhatItCannotParseSoAKeyFileGivenByMistakeStaysSecret() {
		final String seed = "ab".repeat(32);
		final String message = messageOfLoading(seed + "\n");

		assertThat(message, not(containsString(seed.substring(2, 10))));
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAFileThatIsNotThere() {
		try (FileSystem fs = LedgerKeyFixtures.plainFileSystem()) {
			assertThat(messageOfLoading(fs.getPath("/nowhere/genesis.json")), containsString("Cannot read"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldNeverGoIntoADirectory() {
		try (FileSystem fs = LedgerKeyFixtures.plainFileSystem()) {
			final Path directory = Files.createDirectories(fs.getPath(FILE));

			assertThat(messageOfLoading(directory), equalTo(notRegular(directory)));
		}
	}

	@Example
	@SneakyThrows
	public void shouldNeverGoIntoADirectoryThroughALink() {
		try (FileSystem fs = LedgerKeyFixtures.plainFileSystem()) {
			final Path link = Files.createSymbolicLink(Files.createDirectories(fs.getPath("/links")).resolve("g"),
				Files.createDirectories(fs.getPath(FILE))
			);

			assertThat(messageOfLoading(link), equalTo(notRegular(link)));
		}
	}

	@Example
	@SneakyThrows
	public void shouldFollowALinkToAFile() {
		try (FileSystem fs = LedgerKeyFixtures.plainFileSystem()) {
			final Path target = LedgerKeyFixtures.write(fs, FILE, document(validFields()), null);
			final Path link = Files.createSymbolicLink(Files.createDirectories(fs.getPath("/links")).resolve("g"),
				target
			);

			assertThat(GenesisFile.load(Optional.of(link)).orElseThrow(), equalTo(bundled()));
		}
	}

	@Example
	public void shouldTakeAFileOfOneMebibyte() {
		final String document = document(validFields());

		assertThat(loaded(document + " ".repeat(MEBIBYTE - document.length())), equalTo(bundled()));
	}

	@Example
	public void shouldRefuseAFileOfOneByteMoreThanThatBeforeParsingIt() {
		final String document = document(validFields());
		final String message = messageOfLoading(document + " ".repeat(MEBIBYTE - document.length() + 1));

		assertThat(message, containsString(String.valueOf(MEBIBYTE)));
	}

	@Example
	@SneakyThrows
	public void shouldPreferTheGivenFileToTheBundledOne() {
		try (FileSystem fs = LedgerKeyFixtures.plainFileSystem()) {
			final String time = "1700000000001";
			final Path file = LedgerKeyFixtures.write(fs, FILE, documentWith("time", time), null);
			final LedgerGenesis given = GenesisFile.load(Optional.of(file), RESOURCE).orElseThrow();

			assertThat(given.getTime(), equalTo(Long.parseLong(time)));
			assertThat(given, not(equalTo(bundled())));
		}
	}

	@Example
	public void shouldFallBackToTheBundledGenesisWhenNoFileIsGiven() {
		assertThat(GenesisFile.load(Optional.empty(), RESOURCE), equalTo(Optional.of(bundled())));
	}

	@Example
	public void shouldGiveNothingWhenThereIsNeitherAFileNorABundledGenesis() {
		assertThat(GenesisFile.load(Optional.empty(), "/no-such-ledger-genesis.json"), equalTo(Optional.empty()));
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAGivenFileThatIsNotThereInsteadOfTakingTheBundledOne() {
		try (FileSystem fs = LedgerKeyFixtures.plainFileSystem()) {
			final Path missing = fs.getPath("/nowhere/genesis.json");

			assertThrows(IllegalArgumentException.class, () -> GenesisFile.load(Optional.of(missing), RESOURCE));
		}
	}

	@Example
	public void shouldNameTheBundledGenesisWhenItIsMalformed() {
		final String message = assertThrows(IllegalArgumentException.class,
			() -> GenesisFile.load(Optional.empty(), MALFORMED_RESOURCE)
		).getMessage();

		assertThat(message, containsString(MALFORMED_RESOURCE));
		assertThat(message, containsString("'validators' is missing"));
	}

	@Example
	@SneakyThrows
	public void shouldTakeAGivenFileThroughTheOneArgumentLoader() {
		try (FileSystem fs = LedgerKeyFixtures.plainFileSystem()) {
			final Path file = LedgerKeyFixtures.write(fs, FILE, document(validFields()), null);

			assertThat(GenesisFile.load(Optional.of(file)), equalTo(Optional.of(bundled())));
		}
	}
}
