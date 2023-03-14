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
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import net.jqwik.api.lifecycle.Lifespan;
import org.apache.commons.io.FileUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import net.jqwik.api.lifecycle.Store;

public class TestFileOutput {
	private static final String BUILD_DIRECTORY = System.getProperty("testoutput.target");
	private static final String REPORT_DIRECTORY = "surefire-output";

	@SneakyThrows
	public static void output(String data) {
		final StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

		final Optional<StackTraceElement> element = walker.walk(frames ->
			frames.map(StackWalker.StackFrame::toStackTraceElement).filter(e -> {
				return !e.getClassName().endsWith(TestFileOutput.class.getSimpleName());
			}).findFirst()
		);

		if (Objects.nonNull(BUILD_DIRECTORY) && element.isPresent()) {
			final Path path = Path.of(BUILD_DIRECTORY, REPORT_DIRECTORY,
				String.format("%s.%s.txt", element.get().getClassName(),
				element.get().getMethodName())
			);

			/* Uses thje jqwik lifecycle system to not append to file on first run */

			final Store<AtomicInteger> invocations = Store.getOrCreate(path, Lifespan.RUN, () -> {
				return new AtomicInteger(0);
			});

			FileUtils.writeStringToFile(path.toFile(), data.concat("\n"), StandardCharsets.UTF_8,
				invocations.get().intValue() > 0
			);

			invocations.get().incrementAndGet();
		}
	}

	public static <T> void outputJson(T object) throws JsonProcessingException {
		final ObjectMapper mapper = new ObjectMapper();
		String json;

		if (object instanceof String s) {
			json = mapper.readTree(s).toPrettyString();
		} else {
			json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(object);
		}

		/* An assertion failure down here should not really be able to happen. When JSON processing fails, it will
		   usually output a JsonProcessingException rather than returning null (at least it should). */

		assertThat(json, notNullValue());
		TestFileOutput.output(json);
	}
}
