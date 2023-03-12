/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.jqwik;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

public class TestFileOutput {
	@SneakyThrows
	public static void output(String data) {
		final StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
		final String buildDirectory = System.getProperty("testoutput.target");

		final Optional<StackTraceElement> element = walker
			.walk(frames -> frames.map(StackWalker.StackFrame::toStackTraceElement).skip(1)
			.findFirst()
		);

		if (Objects.nonNull(buildDirectory) && element.isPresent()) {
			final Path path = Path.of(buildDirectory, "surefire-output",
				String.format("%s.%s.txt", element.get().getClassName(), element.get().getMethodName())
			);

			FileUtils.writeStringToFile(path.toFile(), data, StandardCharsets.UTF_8, false);
		}
	}

	public static <T> void outputJson(T object) throws JsonProcessingException {
		final ObjectMapper mapper = new ObjectMapper();
		final String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(object);

		/* An assertion failure down here should not really be able to happen. When JSON processing fails, it will
		   usually output a JsonProcessingException rather than returning null (at least it should). */

		assertThat(json, notNullValue());
		TestFileOutput.output(json);
	}
}
