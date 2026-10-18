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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;
import picocli.CommandLine;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class StoragePutTest {
	private static final String FOLDER = "/files";

	private static StoragePut storagePut(FileSystem fs, Path file) {
		final StoragePut command = new StoragePut();

		new CommandLine(command).registerConverter(Path.class, fs::getPath).parseArgs(file.toString());
		return command;
	}

	@SneakyThrows
	private static Path folder(FileSystem fs) {
		return Files.createDirectories(fs.getPath(FOLDER));
	}

	private static Response stored(String fingerprint) {
		return Response.status(Status.CREATED).entity(new ObjectMapper().createObjectNode()
			.put("fingerprint", fingerprint).toString()).build();
	}

	@SneakyThrows
	@Property(tries = 30)
	public void streamsTheFileWithItsLengthAndPrintsTheFingerprint(@ForAll @Size(max = 4096) byte[] content,
		@ForAll @AlphaChars @NumericChars @StringLength(min = 1, max = 60) String fingerprint) {

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = Files.write(folder(fs).resolve("upload"), content);
			final Result result = RestCommandFixture.run(storagePut(fs, file), stored(fingerprint));
			final Request request = result.request().orElseThrow();

			assertThat(request.method(), equalTo(HttpMethod.POST));
			assertThat(request.location(), equalTo("/storage"));
			assertThat((byte[]) request.entity().orElseThrow().getEntity(), equalTo(content));
			assertThat(request.entity().orElseThrow().getMediaType(), equalTo(MediaType.APPLICATION_OCTET_STREAM_TYPE));

			assertThat(request.headers(), equalTo(Optional.of(new MultivaluedHashMap<>(Map.of(HttpHeaders.CONTENT_LENGTH,
				(long) content.length)))));

			assertThat(result.out().lines().toList(), equalTo(List.of(fingerprint)));
			assertThat(result.err(), equalTo(""));
		}
	}

	@SneakyThrows
	@Property(tries = 10)
	public void namesAMissingFileWithoutSendingAnything(@ForAll @AlphaChars @StringLength(min = 1, max = 20) String name) {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path missing = folder(fs).resolve(name);
			final Result result = RestCommandFixture.run(storagePut(fs, missing), stored("unused"));

			assertThat(result.request(), equalTo(Optional.empty()));
			assertThat(result.out(), equalTo(""));
			assertThat(result.err(), containsString(missing.toString()));
		}
	}

	@SneakyThrows
	@Example
	public void reportsAnUploadTheDaemonRefuses() {
		final ResponseOddityException tooLarge = new ResponseOddityException(Status.REQUEST_ENTITY_TOO_LARGE);

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = Files.write(folder(fs).resolve("upload"), new byte[] { 1, 2, 3 });
			final Result result = RestCommandFixture.runWithOddity(storagePut(fs, file), tooLarge);

			assertThat(result.out(), equalTo(""));
			assertThat(result.err().lines().toList(), equalTo(List.of(tooLarge.getMessage())));
		}
	}
}
