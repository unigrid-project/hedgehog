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

package org.unigrid.hedgehog.command.cli.spork;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.command.cli.GridSporkGet;
import org.unigrid.hedgehog.command.cli.GridSporkSet;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "mint-supply")
public class MintSupply implements Runnable {
	@Spec
	private CommandSpec spec;

	@Override
	@SneakyThrows
	public void run() {
		if (spec.parent().userObject() instanceof GridSporkGet) {
			final RestClientCommand cmd = new RestClientCommand(HttpMethod.GET, "/gridspork/mint-supply") {

				@Override
				protected void execute(Response response) {
					System.out.println(Json.parse(response.readEntity(String.class)));
				}
			};

			cmd.run();
		} else if (spec.parent().userObject() instanceof GridSporkSet) {
			final RestClientCommand cmd = new RestClientCommand(HttpMethod.PUT, "/gridspork/mint-supply") {

				@Override
				protected <T> Entity<T> getEntity() {
					return (Entity<T>) Entity.text(GridSporkSet.getData());
				}

				@Override
				protected void execute(Response response) {
					/* No need to do anything here */
				}
			};

			final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
			headers.add("privateKey", GridSporkSet.getKey());

			cmd.setHeaders(headers);
			cmd.run();
		} else {
			/* Will only happen if we forget to extend this method */
			throw new UnsupportedOperationException();
		}
	}
}
