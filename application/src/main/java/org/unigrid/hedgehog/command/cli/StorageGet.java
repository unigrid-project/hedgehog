/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.server.rest.StorageResource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "storage-get", description = "Read a stored file back using its fingerprint.")
public class StorageGet extends FingerprintCommand {
	private static final String INCOMPLETE = "The file could not be read back completely";

	@Option(names = { "-o", "--output" }, required = true, description = "Where to write the file.")
	private Path output;

	public StorageGet() {
		super(HttpMethod.GET);
	}

	/* A stripe lost while streaming ends the body short of its announced size. The body therefore lands beside the
	   output first and only replaces it once complete, so a failed read never destroys a file that was there. */
	@Override
	@SneakyThrows
	protected void execute(final Response response) {
		if (response.getStatus() != Response.Status.OK.getStatusCode()) {
			System.err.println(response.getStatusInfo());
			return;
		}

		final Path partial = Files.createTempFile(output.toAbsolutePath().getParent(), ".storage-get-", ".part");

		try {
			if (isComplete(response, partial)) {
				save(partial);
			} else {
				System.err.println(INCOMPLETE);
			}
		} finally {
			Files.deleteIfExists(partial);
		}
	}

	private static boolean isComplete(final Response response, final Path partial) {
		try (InputStream body = response.readEntity(InputStream.class)) {
			final long received = Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);

			return announcedSize(response).equals(Optional.of(received));
		} catch (IOException | ProcessingException ex) {
			return false;
		}
	}

	private void save(final Path partial) {
		try {
			Files.move(partial, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ex) {
			System.err.println("The file was read back but could not be saved to " + output);
		}
	}

	private static Optional<Long> announcedSize(final Response response) {
		return Optional.ofNullable(response.getHeaderString(StorageResource.FILE_SIZE_HEADER)).map(Long::parseLong);
	}
}
