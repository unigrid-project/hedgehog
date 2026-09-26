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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
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
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;

public class RestTokenTest extends BaseMockedWeldTest {
	private static final int TOKENS = 100;
	private static final int MIN_TOKEN_LENGTH = 43;

	private void mockConfiguredToken(String token) {
		new MockUp<RestOptions>() {
			@Mock public /* static */ String getToken() {
				return token;
			}
		};
	}

	@Example
	public void shouldGenerateDistinctTokens() {
		assertThat(IntStream.range(0, TOKENS).mapToObj(i -> RestToken.generate()).collect(Collectors.toSet()),
			hasSize(TOKENS)
		);

		assertThat(RestToken.generate().length(), greaterThanOrEqualTo(MIN_TOKEN_LENGTH));
	}

	@Example
	@SneakyThrows
	public void shouldReadWhatWasWritten() {
		final String token = RestToken.generate();

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

	@Example
	@SneakyThrows
	public void shouldPreferConfiguredToken() {
		RestToken.write(RestToken.getFile(), RestToken.generate());
		mockConfiguredToken("configured");

		assertThat(RestToken.resolve(), equalTo("configured"));
	}

	@Example
	@SneakyThrows
	public void shouldFallBackToFileForBlankToken() {
		final String token = RestToken.generate();

		RestToken.write(RestToken.getFile(), token);
		mockConfiguredToken(" ");

		assertThat(RestToken.resolve(), equalTo(token));
	}
}
