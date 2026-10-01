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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.crypto.SigningException;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class KeyGenerateTest {
	private static final Pattern KEY_PAIR = Pattern.compile("Private Key: (\\p{XDigit}+)\\RPublic Key: (\\p{XDigit}+)\\R");

	private static volatile GeneralSecurityException unavailable;

	/* The fake outlives every property, so it is installed once and only fails when a try asks it to */
	@BeforeContainer
	private static void installFakes() {
		new MockUp<Signature>() {
			@Mock public void $init(Invocation invocation) throws GeneralSecurityException {
				if (unavailable != null) {
					throw unavailable;
				}

				invocation.proceed();
			}
		};
	}

	@BeforeTry
	public void beforeTry() {
		unavailable = null;
	}

	@Provide
	public Arbitrary<GeneralSecurityException> provideRefusal() {
		return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(40).flatMap(reason -> Arbitraries.of(
			new NoSuchAlgorithmException(reason), new InvalidAlgorithmParameterException(reason))
		);
	}

	@Property(tries = 20)
	public void shouldPrintAPairWhosePrivateKeySignsForItsPublicKey(@ForAll byte[] data)
		throws GeneralSecurityException, SigningException {

		final HedgehogCli.Result result = HedgehogCli.run("util", "key-generate");
		final Matcher pair = KEY_PAIR.matcher(result.out());

		assertThat(result.exitCode(), equalTo(0));
		assertThat(pair.matches(), equalTo(true));
		assertThat(pair.group(1).length(), equalTo(Signature.PRIVATE_KEY_HEX_SIZE * 2));
		assertThat(pair.group(2).length(), equalTo(Signature.PUBLIC_KEY_HEX_SIZE * 2));

		final byte[] signature = new Signature(Optional.of(pair.group(1)), Optional.empty()).sign(data);

		assertThat(Signature.isSignedBy(pair.group(2), data, signature), equalTo(true));
	}

	@Property(tries = 20)
	public void shouldReportAPairItCannotGenerate(@ForAll("provideRefusal") GeneralSecurityException refusal) {
		unavailable = refusal;

		final HedgehogCli.Result result = HedgehogCli.run("util", "key-generate");

		assertThat(result.out(), equalTo(""));
		assertThat(result.err(), containsString("Failed to generate signature: " + refusal));
		result.assertNoStackTrace();
	}
}
