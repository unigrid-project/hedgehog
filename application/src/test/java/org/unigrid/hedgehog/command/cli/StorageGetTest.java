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

package org.unigrid.hedgehog.command.cli;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.PropagationMode;
import net.jqwik.api.statistics.Statistics;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.server.rest.StorageResource;
import picocli.CommandLine;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class StorageGetTest {
	private static final String OUTPUT = "/files/output";

	private static volatile InputStream body;

	/* An outbound response carries headers but refuses to be read, so reading it is faked. The fake outlives every
	   property anyway, so it is installed once and hands out whatever body the current try prepared. */
	@BeforeContainer
	private static void installFakes() {
		new MockUp<Response>(Response.ok().build().getClass()) {
			@Mock public Object readEntity(final Class<?> type) {
				return body;
			}
		};
	}

	private static StorageGet storageGet(final FileSystem fs) {
		final StorageGet command = new StorageGet();

		new CommandLine(command).registerConverter(Path.class, fs::getPath).parseArgs("-f", "fingerprint", "-o", OUTPUT);
		return command;
	}

	private static Response announcing(final long size) {
		return Response.ok().header(StorageResource.FILE_SIZE_HEADER, size).build();
	}

	private static InputStream breakingAfter(final byte[] prefix) {
		return new SequenceInputStream(new ByteArrayInputStream(prefix), new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("Connection reset");
			}
		});
	}

	@SneakyThrows
	private static Path prepare(final FileSystem fs, final Optional<byte[]> existing) {
		final Path output = fs.getPath(OUTPUT);

		Files.createDirectories(output.getParent());

		if (existing.isPresent()) {
			Files.write(output, existing.get());
		}

		return output;
	}

	/* Nothing but the output may be left beside it, and it must hold exactly what is expected */
	@SneakyThrows
	private static void assertHolds(final Path output, final Optional<byte[]> expected) {
		try (Stream<Path> files = Files.list(output.getParent())) {
			assertThat(files.collect(Collectors.toList()), equalTo(expected.map(content -> List.of(output))
				.orElse(List.of())));
		}

		if (expected.isPresent()) {
			assertThat(Files.readAllBytes(output), equalTo(expected.get()));
		}
	}

	@SneakyThrows
	@Property(tries = 50)
	public void replacesTheOutputOnlyWithACompleteFile(@ForAll @Size(max = 4096) final byte[] file,
		@ForAll @IntRange(max = 64) final int missing, @ForAll final Optional<@Size(max = 64) byte[]> existing) {

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path output = prepare(fs, existing);

			body = new ByteArrayInputStream(file, 0, file.length - Math.min(missing, file.length));
			storageGet(fs).execute(announcing(file.length));
			assertHolds(output, missing == 0 || file.length == 0 ? Optional.of(file) : existing);
		}

		Statistics.collect(missing == 0);
		Statistics.coverage(coverage -> {
			coverage.check(true).count(count -> count > 0);
			coverage.check(false).count(count -> count > 0);
		});
	}

	@SneakyThrows
	@Property(tries = 50)
	public void keepsTheOutputWhenTheStreamBreaks(@ForAll @Size(max = 4096) final byte[] prefix,
		@ForAll @IntRange(min = 1, max = 64) final int missing, @ForAll final Optional<@Size(max = 64) byte[]> existing) {

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path output = prepare(fs, existing);

			body = breakingAfter(prefix);
			storageGet(fs).execute(announcing(prefix.length + missing));
			assertHolds(output, existing);
		}
	}

	@SneakyThrows
	@Example
	public void reportsAnOutputThatCannotBeReplaced() {
		final PrintStream console = System.err;
		final ByteArrayOutputStream errors = new ByteArrayOutputStream();

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path output = fs.getPath(OUTPUT);

			Files.createDirectories(output.resolve("occupied"));
			body = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
			System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
			storageGet(fs).execute(announcing(3));

			try (Stream<Path> files = Files.list(output.getParent())) {
				assertThat(files.collect(Collectors.toList()), equalTo(List.of(output)));
			}

			assertThat(Files.isDirectory(output.resolve("occupied")), equalTo(true));
		} finally {
			System.setErr(console);
		}

		assertThat(errors.toString(StandardCharsets.UTF_8), containsString("could not be saved"));
	}

	@Property(tries = 20)
	public void takesTheFingerprintFromTheOptionOrAPrompt(
		@ForAll @AlphaChars @StringLength(min = 1, max = 60) final String fingerprint,
		@ForAll final boolean prompted, @ForAll final boolean delete) {

		final InputStream console = System.in;
		final CommandLine line = new CommandLine(delete ? new StorageDelete() : new StorageGet());
		final List<String> arguments = Stream.of(Stream.of("-f"), prompted ? Stream.<String>empty() : Stream.of(fingerprint),
			delete ? Stream.<String>empty() : Stream.of("-o", OUTPUT)).flatMap(s -> s).collect(Collectors.toList());

		try {
			System.setIn(new ByteArrayInputStream((fingerprint + "\n").getBytes(StandardCharsets.UTF_8)));
			line.parseArgs(arguments.toArray(String[]::new));
		} finally {
			System.setIn(console);
		}

		assertThat(line.getCommandSpec().findOption("-f").getValue(), equalTo(fingerprint));
	}
}
