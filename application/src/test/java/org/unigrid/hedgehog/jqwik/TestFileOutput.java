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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;

public class TestFileOutput {
	@SneakyThrows
	public static <T> void output(String data) {
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
}
