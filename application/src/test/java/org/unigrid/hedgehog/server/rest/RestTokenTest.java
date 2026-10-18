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

package org.unigrid.hedgehog.server.rest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.constraints.Whitespace;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;

public class RestTokenTest extends BaseMockedWeldTest {
	private static final int TOKENS = 100;
	private static final int TOKEN_LENGTH = 43;

	private void mockConfiguredToken(String token) {
		new MockUp<RestOptions>() {
			@Mock public /* static */ String getToken() {
				return token;
			}
		};
	}

	@Property(tries = 50)
	public void shouldGenerateUrlSafeTokens() {
		assertThat(RestToken.generate(), matchesPattern("[A-Za-z0-9_-]{" + TOKEN_LENGTH + "}"));
	}

	@Example
	public void shouldGenerateDistinctTokens() {
		assertThat(IntStream.range(0, TOKENS).mapToObj(i -> RestToken.generate()).collect(Collectors.toSet()),
			hasSize(TOKENS)
		);
	}

	@SneakyThrows
	@Property(tries = 50)
	public void shouldReadWhatWasWritten(@ForAll @AlphaChars @NumericChars @StringLength(min = 1, max = 128)
		String token) {

		RestToken.write(RestToken.getFile(), token);
		assertThat(RestToken.read(RestToken.getFile()), equalTo(token));
	}

	@Example
	@SneakyThrows
	public void shouldOnlyBeReadableByOwner() {
		final Path file = RestToken.getFile();

		Assume.that(Files.getFileStore(file.getParent()).supportsFileAttributeView("posix"));
		RestToken.write(file, RestToken.generate());

		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), equalTo("rw-------"));
	}

	@SneakyThrows
	@Property(tries = 50)
	public void shouldPreferConfiguredToken(@ForAll @AlphaChars @StringLength(min = 1, max = 64) String configured) {
		RestToken.write(RestToken.getFile(), RestToken.generate());
		mockConfiguredToken(configured);

		assertThat(RestToken.resolve(), equalTo(configured));
	}

	@SneakyThrows
	@Property(tries = 20)
	public void shouldFallBackToFileForBlankToken(@ForAll @Whitespace @StringLength(max = 8) String blank) {
		final String token = RestToken.generate();

		RestToken.write(RestToken.getFile(), token);
		mockConfiguredToken(blank);

		assertThat(RestToken.resolve(), equalTo(token));
	}
}
