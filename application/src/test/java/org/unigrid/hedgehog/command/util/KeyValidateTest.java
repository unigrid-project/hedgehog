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

package org.unigrid.hedgehog.command.util;

import static java.util.function.Predicate.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import java.util.Arrays;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.commons.codec.binary.Hex;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.model.crypto.Signature;

public class KeyValidateTest {
	/* Every DER encoded signature starts with the tag of a sequence, which this is not */
	private static final String NOT_A_SEQUENCE = "00";

	@Provide
	public Arbitrary<Signature> provideKey() {
		return KeyArbitraries.keys();
	}

	@Provide
	public Arbitrary<String> provideMalformedHex() {
		return KeyArbitraries.malformedHex();
	}

	@Provide
	public Arbitrary<String> provideRequiredOption() {
		return Arbitraries.of("--data", "--key", "--signature");
	}

	private static HedgehogCli.Result validate(String data, String key, String signature) {
		return HedgehogCli.run("util", "key-validate", "--data=" + data, "--key=" + key, "--signature=" + signature);
	}

	private static HedgehogCli.Result validate(byte[] data, Signature key, String signature) {
		return validate(Hex.encodeHexString(data), key.getPublicKey(), signature);
	}

	@SneakyThrows
	private static String signed(Signature key, byte[] data) {
		return Hex.encodeHexString(key.sign(data));
	}

	private static void assertAnswer(HedgehogCli.Result result, boolean valid) {
		assertThat(result.exitCode(), equalTo(0));
		assertThat(result.out(), equalTo(valid + System.lineSeparator()));
	}

	private static void assertRefused(HedgehogCli.Result result) {
		assertThat(result.out(), equalTo(""));
		assertThat(result.err(), containsString("Failed to verify signature"));
		result.assertNoStackTrace();
	}

	@Property(tries = 20)
	public void shouldConfirmWhatKeySignSigned(@ForAll("provideKey") Signature key, @ForAll byte[] data) {
		final HedgehogCli.Result signing = HedgehogCli.run("util", "key-sign", "--data=" + Hex.encodeHexString(data),
			"--key=" + key.getPrivateKey()
		);

		assertAnswer(validate(data, key, signing.out().strip()), true);
	}

	@Property(tries = 20)
	public void shouldDenyASignatureOverOtherData(@ForAll("provideKey") Signature key, @ForAll byte[] data,
		@ForAll byte[] other) {

		Assume.that(!Arrays.equals(data, other));
		assertAnswer(validate(other, key, signed(key, data)), false);
	}

	@Property(tries = 20)
	public void shouldDenyASignatureByAnotherKey(@ForAll("provideKey") Signature key,
		@ForAll("provideKey") Signature other, @ForAll byte[] data) {

		assertAnswer(validate(data, other, signed(key, data)), false);
	}

	@Property(tries = 20)
	public void shouldRefuseHexThatIsMalformed(@ForAll("provideKey") Signature key, @ForAll byte[] data,
		@ForAll("provideMalformedHex") String malformed, @ForAll boolean inData) {

		assertRefused(inData ? validate(malformed, key.getPublicKey(), signed(key, data))
			: validate(Hex.encodeHexString(data), key.getPublicKey(), malformed)
		);
	}

	@Property(tries = 20)
	public void shouldRefuseASignatureThatIsNotOne(@ForAll("provideKey") Signature key, @ForAll byte[] data,
		@ForAll byte[] garbage) {

		assertRefused(validate(data, key, NOT_A_SEQUENCE + Hex.encodeHexString(garbage)));
	}

	@Property
	public void shouldRequireEveryOption(@ForAll("provideRequiredOption") String missing) {
		final HedgehogCli.Result result = HedgehogCli.run(Stream.concat(Stream.of("util", "key-validate"),
			Stream.of("--data", "--key", "--signature").filter(not(missing::equals)).map(option -> option + "=00")
		).toArray(String[]::new));

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("Missing required option: '" + missing));
	}
}
