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

package org.unigrid.hedgehog.common.model;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

public class VersionTest {
	private static final String DEFAULTS = "Unigrid Hedgehog 0.0.0-BASTARD";

	/* The version is read from the class loader of the thread, which here hands out the given text or nothing */
	static <T> T withProperties(String text, Supplier<T> reading) {
		return withStreams(() -> text == null ? null
			: new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)), reading);
	}

	/* Every look for the file gets a stream of its own from the supplier */
	static <T> T withStreams(Supplier<InputStream> streams, Supplier<T> reading) {
		final Thread thread = Thread.currentThread();
		final ClassLoader previous = thread.getContextClassLoader();
		final String version = System.getProperty(Version.VERSION_PROPERTY_NAME);
		final String pad = System.getProperty(Version.VERSION_PAD_PROPERTY_NAME);

		thread.setContextClassLoader(new ClassLoader(previous) {
			@Override
			public InputStream getResourceAsStream(String name) {
				return streams.get();
			}
		});

		try {
			return reading.get();
		} finally {
			thread.setContextClassLoader(previous);
			restore(Version.VERSION_PROPERTY_NAME, version);
			restore(Version.VERSION_PAD_PROPERTY_NAME, pad);
		}
	}

	private static void restore(String name, String value) {
		if (value == null) {
			System.clearProperty(name);
		} else {
			System.setProperty(name, value);
		}
	}

	@SneakyThrows
	private static String version() {
		return new Version().getVersion()[0];
	}

	@Example
	public void shouldReadTheNameAndVersionOfTheProject() {
		final String properties = "project.name=Unigrid Hedgehog\nproject.version=1.2.3\n";

		assertThat(withProperties(properties, VersionTest::version), equalTo("Unigrid Hedgehog 1.2.3"));
		assertThat(withProperties(properties, Version::getAuthor), equalTo("Unigrid"));
		assertThat(withProperties(properties, Version::getName), equalTo("Hedgehog"));
		assertThat(withProperties(properties, Version::getVersionNumber), equalTo("1.2.3"));
	}

	/* The pad right-aligns the version in the header of the command line */
	@Example
	public void shouldPadTheVersionToTheWidthOfTheHeader() {
		final String pad = withProperties("project.name=Unigrid Hedgehog\nproject.version=1.2.3\n", () -> {
			version();
			return System.getProperty(Version.VERSION_PAD_PROPERTY_NAME);
		});

		assertThat(pad, equalTo(" ".repeat(42 - "Unigrid Hedgehog 1.2.3".length())));
	}

	@Example
	public void shouldFallBackToTheDefaultsWithoutAPropertyFile() {
		assertThat(withProperties(null, VersionTest::version), equalTo(DEFAULTS));
	}

	/* The name is taken before the version is found missing */
	@Example
	public void shouldFallBackToTheDefaultVersionWhenItIsMissing() {
		assertThat(withProperties("project.name=Other Name\n", VersionTest::version),
			equalTo("Other Name 0.0.0-BASTARD"));
	}

	/* A stream that notes whether it was closed */
	private static final class Watched extends InputStream {
		private final InputStream content;
		private boolean closed;

		private Watched(InputStream content) {
			this.content = content;
		}

		@Override
		public int read() throws IOException {
			return content.read();
		}

		@Override
		public void close() throws IOException {
			closed = true;
			content.close();
		}
	}

	private static List<Watched> opened(InputStream content) {
		final List<Watched> opened = new ArrayList<>();

		withStreams(() -> {
			final Watched stream = new Watched(content);

			opened.add(stream);
			return stream;
		}, VersionTest::version);

		return opened;
	}

	/* The file is found again at every call, so a stream left open at each one piles up until it is collected */
	@Example
	public void shouldCloseThePropertyFileOnceItIsRead() {
		final List<Watched> opened = opened(new ByteArrayInputStream(
			"project.name=Unigrid Hedgehog\nproject.version=1.2.3\n".getBytes(StandardCharsets.ISO_8859_1)));

		assertThat(opened, hasSize(1));
		assertThat(opened.get(0).closed, is(true));
	}

	@Example
	public void shouldCloseThePropertyFileThatCannotBeRead() {
		final List<Watched> opened = opened(new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("unreadable");
			}
		});

		assertThat(opened, hasSize(1));
		assertThat(opened.get(0).closed, is(true));
	}

	@Example
	public void shouldFallBackToTheDefaultVersionNumberForANameOfOneWord() {
		final String properties = "project.name=Solo\nproject.version=1.2.3\n";

		assertThat(withProperties(properties, Version::getAuthor), equalTo("Solo"));
		assertThat(withProperties(properties, Version::getVersionNumber), equalTo("0.0.0-BASTARD"));
	}
}
