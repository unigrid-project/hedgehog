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
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.commons.lang3.ObjectUtils;
import org.unigrid.hedgehog.command.cli.GridSporkGet;
import org.unigrid.hedgehog.command.cli.GridSporkGrow;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.Json;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "mint-storage")
public class MintStorage implements Runnable {
	@Spec
	private CommandSpec spec;

	@Getter @CommandLine.Option(names = "--address", scope = CommandLine.ScopeType.INHERIT,
		description = "Unigrid address for the mint."
	)
	private String address;

	@Getter @CommandLine.Option(names = "--height", scope = CommandLine.ScopeType.INHERIT,
		description = "Block height on the consensus chain for the mint."
	)
	private int height;

	@Override
	@SneakyThrows
	public void run() {
		if (spec.parent().userObject() instanceof GridSporkGet) {
			final RestClientCommand cmd = new RestClientCommand(HttpMethod.GET, "/gridspork/mint-storage") {
				@Override
				protected void execute(Response response) {
					System.out.println(Json.parse(response.readEntity(String.class)));
				}
			};

			cmd.run();
		} else if (spec.parent().userObject() instanceof GridSporkGrow) {
			if (ObjectUtils.anyNull(address, height)) {
				System.out.println("Both block height and address have to be specified");
			} else {
				final String url = "/gridspork/mint-storage/%s/%s".formatted(address, height);
				final RestClientCommand cmd = new RestClientCommand(HttpMethod.PUT, url) {
					@Override
					protected <T> T getEntity() {
						return (T) GridSporkGrow.getData();
					}

					@Override
					protected void execute(Response response) {
						/* No need to do anything here */
					}
				};

				final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
				headers.add("privateKey", GridSporkGrow.getKey());

				cmd.setHeaders(headers);
				cmd.run();
			}
		} else {
			/* Will only happen if we forget to extend this method */
			throw new UnsupportedOperationException();
		}
	}
}
