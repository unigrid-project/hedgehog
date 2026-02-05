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

import jakarta.ws.rs.core.MultivaluedHashMap;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.server.rest.StorageResource;
import picocli.CommandLine.Option;

/* A bare -f prompts for the fingerprint with echo off, keeping the only key to a file out of shell history and the
   process list */
abstract class FingerprintCommand extends RestClientCommand {
	@Option(names = { "-f", "--fingerprint" }, required = true, interactive = true, arity = "0..1",
		description = "Fingerprint returned when storing, prompted for when given without a value.")
	private String fingerprint;

	FingerprintCommand(final String method) {
		super(method, "/storage");
	}

	@Override
	public void run() {
		final MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();

		headers.add(StorageResource.FINGERPRINT_HEADER, fingerprint);
		setHeaders(headers);
		super.run();
	}
}
