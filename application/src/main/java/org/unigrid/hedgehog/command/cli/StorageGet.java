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

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.server.rest.StorageResource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "storage-get", description = "Read a stored file back using its fingerprint.")
public class StorageGet extends RestClientCommand {
	@Option(names = { "-f", "--fingerprint" }, required = true, description = "Fingerprint returned when storing.")
	private String fingerprint;

	@Option(names = { "-o", "--output" }, required = true, description = "Where to write the file.")
	private Path output;

	public StorageGet() {
		super(HttpMethod.GET, "/storage");
	}

	@Override
	public void run() {
		final MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();

		headers.add(StorageResource.FINGERPRINT_HEADER, fingerprint);
		setHeaders(headers);
		super.run();
	}

	/* A file that loses a stripe while streaming arrives short of its Content-Length, and a truncated file must
	   never be mistaken for the stored one */
	@Override
	@SneakyThrows
	protected void execute(final Response response) {
		if (response.getStatus() != Response.Status.OK.getStatusCode()) {
			System.err.println(response.getStatusInfo());
			return;
		}

		try (InputStream body = response.readEntity(InputStream.class)) {
			final long written = Files.copy(body, output, StandardCopyOption.REPLACE_EXISTING);

			if (response.getLength() >= 0 && written != response.getLength()) {
				Files.deleteIfExists(output);
				System.err.println("The file could not be read back completely");
			}
		}
	}
}
