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
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.model.crypto.Signature;

public class KeySignTest {
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
		return Arbitraries.of("--data", "--key");
	}

	private static HedgehogCli.Result sign(String data, String key) {
		return HedgehogCli.run("util", "key-sign", "--data=" + data, "--key=" + key);
	}

	@Property(tries = 20)
	public void shouldSignSoThePublicKeyVerifies(@ForAll("provideKey") Signature key, @ForAll byte[] data)
		throws DecoderException {

		final HedgehogCli.Result result = sign(Hex.encodeHexString(data), key.getPrivateKey());

		assertThat(result.exitCode(), equalTo(0));
		assertThat(Signature.isSignedBy(key.getPublicKey(), data, Hex.decodeHex(result.out().strip())), equalTo(true));
	}

	@Property(tries = 20)
	public void shouldRefuseDataThatIsNotHex(@ForAll("provideKey") Signature key,
		@ForAll("provideMalformedHex") String data) {

		final HedgehogCli.Result result = sign(data, key.getPrivateKey());

		assertThat(result.out(), equalTo(""));
		assertThat(result.err(), containsString("Failed to sign"));
		result.assertNoStackTrace();
	}

	@Property
	public void shouldRequireEveryOption(@ForAll("provideRequiredOption") String missing) {
		final HedgehogCli.Result result = HedgehogCli.run(Stream.concat(Stream.of("util", "key-sign"),
			Stream.of("--data", "--key").filter(not(missing::equals)).map(option -> option + "=00")
		).toArray(String[]::new));

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("Missing required option: '" + missing));
	}
}
