/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.server.socks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.proxy.session.DestinationPolicy;

/* The permissive destination policy is for tests, and nothing a node runs may select it: no option and no spork
   flag does. The compiled classes are searched for the name of the method, which a call leaves in the constant
   pool of the caller, so that a use in any production class of `application` or `proxy` fails the build, whether
   the module is a directory of classes or a jar. The class that declares the method is left out. */
public class LoopbackPolicyIsTestOnlyTest {
	private static final String METHOD = "allowLoopback";

	private static boolean names(byte[] classFile) {
		return new String(classFile, StandardCharsets.ISO_8859_1).contains(METHOD);
	}

	private static byte[] read(Path file) {
		try {
			return Files.readAllBytes(file);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String classFile(Class<?> type) {
		return type.getName().replace('.', '/') + ".class";
	}

	/* A search that landed on an empty directory would find nothing, so the module must hold its own class */
	private static List<String> referencesIn(Path root, Class<?> moduleClass) throws IOException {
		final Path declaring = root.resolve(classFile(DestinationPolicy.class));

		assertThat("The module is where it was searched for", Files.exists(root.resolve(classFile(moduleClass))),
			is(true));

		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(file -> file.toString().endsWith(".class"))
				.filter(file -> !file.equals(declaring)).filter(file -> names(read(file)))
				.map(root::relativize).map(Path::toString).toList();
		}
	}

	private static List<String> referencesIn(Class<?> moduleClass) throws Exception {
		final Path location = Path.of(moduleClass.getProtectionDomain().getCodeSource().getLocation().toURI());

		if (Files.isDirectory(location)) {
			return referencesIn(location, moduleClass);
		}

		try (FileSystem jar = FileSystems.newFileSystem(location)) {
			return referencesIn(jar.getPath("/"), moduleClass);
		}
	}

	@Example
	public void shouldBeReferencedByNoProductionClassOfApplication() throws Exception {
		assertThat(referencesIn(Hedgehog.class), is(empty()));
	}

	@Example
	public void shouldBeReferencedByNoProductionClassOfProxy() throws Exception {
		assertThat(referencesIn(DestinationPolicy.class), is(empty()));
	}

	@Example
	public void shouldFindTheNameInAClassThatHasIt() throws Exception {
		try (var thisClass = getClass().getResourceAsStream(getClass().getSimpleName() + ".class")) {
			assertThat(names(thisClass.readAllBytes()), is(true));
		}
	}
}
