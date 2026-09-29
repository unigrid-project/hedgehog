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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.client.ResponseOddityException;
import org.unigrid.hedgehog.client.RestClient;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "storage-put", description = "Store a file on the network and print its fingerprint.")
public class StoragePut extends RestClientCommand {
	@Parameters(index = "0", description = "The file to store.")
	private Path file;

	public StoragePut() {
		super(HttpMethod.POST, "/storage");
	}

	/* The daemon refuses an upload that does not announce its length, and announcing it lets the file stream
	   rather than wait to be buffered whole */
	@Override
	@SneakyThrows
	protected Response post(final RestClient rest) throws ResponseOddityException {
		try (InputStream content = Files.newInputStream(file)) {
			return rest.postStream(getLocation(), content, Files.size(file));
		}
	}

	@Override
	@SneakyThrows
	protected void execute(final Response response) {
		final String body = response.readEntity(String.class);

		System.out.println(new ObjectMapper().readTree(body).get("fingerprint").asText());
	}
}
