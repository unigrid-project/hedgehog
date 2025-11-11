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

package org.unigrid.hedgehog.command.cli;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import mockit.internal.state.SavePoint;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.statistics.Statistics;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class StorageGetTest {
	private SavePoint mocks;

	@BeforeTry
	public void saveMocks() {
		mocks = new SavePoint();
	}

	@AfterTry
	public void restoreMocks() {
		mocks.rollback();
	}

	/* Picocli would resolve the option on the default file system, and the package is not opened to reflection
	   helpers, so the field is set from inside the module */
	@SneakyThrows
	private static void writeTo(final StorageGet command, final Path output) {
		final Field field = StorageGet.class.getDeclaredField("output");

		field.setAccessible(true);
		field.set(command, output);
	}

	/* An outbound response carries the declared length but refuses to be read, so only reading it is faked */
	private static Response responseOf(final byte[] body, final int declaredLength) {
		final Response response = Response.ok().header(HttpHeaders.CONTENT_LENGTH, declaredLength).build();

		new MockUp<Response>(response.getClass()) {
			@Mock public Object readEntity(final Class<?> type) {
				return new ByteArrayInputStream(body);
			}
		};

		return response;
	}

	@SneakyThrows
	@Property(tries = 50)
	public void keepsOnlyCompleteFiles(@ForAll @Size(max = 4096) final byte[] body,
		@ForAll @IntRange(max = 64) final int missing) {

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path output = fs.getPath("/file");
			final StorageGet command = new StorageGet();

			Files.write(output, new byte[] { 1 });
			writeTo(command, output);
			command.execute(responseOf(body, body.length + missing));

			if (missing == 0) {
				assertThat(Files.readAllBytes(output), equalTo(body));
			} else {
				assertThat(Files.exists(output), equalTo(false));
			}
		}

		Statistics.collect(missing == 0);
		Statistics.coverage(coverage -> {
			coverage.check(true).count(count -> count > 0);
			coverage.check(false).count(count -> count > 0);
		});
	}
}
