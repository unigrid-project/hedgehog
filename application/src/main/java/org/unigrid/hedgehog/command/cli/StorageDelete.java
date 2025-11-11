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
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.server.rest.StorageResource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "storage-delete", description = "Delete a stored file using its fingerprint.")
public class StorageDelete extends RestClientCommand {
	@Option(names = { "-f", "--fingerprint" }, required = true, description = "Fingerprint returned when storing.")
	private String fingerprint;

	public StorageDelete() {
		super(HttpMethod.DELETE, "/storage");
	}

	@Override
	public void run() {
		final MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();

		headers.add(StorageResource.FINGERPRINT_HEADER, fingerprint);
		setHeaders(headers);
		super.run();
	}

	@Override
	protected void execute(final Response response) {
		System.out.println(response.getStatusInfo());
	}
}
