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

package org.unigrid.hedgehog.command.cli.spork;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/* A spork that has one place on the daemon to read it from and to propose a new version to. Under `gridspork-get` the
   command prints it; under `gridspork-set` the data of the command is read into what the daemon takes, and sent with
   the key of the foundation. What differs from one spork to the next is where it is, how its data is read, how it
   is sent and what is printed of the answer; the rest is here. */
@Command
abstract class SporkCommand<D> implements Runnable {
	@Spec
	private CommandSpec spec;

	protected abstract String location();

	/* What the data of the command says, or a ParameterException that says what is wrong with it */
	protected abstract D read(String data);

	protected Entity<?> entityOf(final D data) {
		return Entity.json(data);
	}

	/* What the proposal is answered with: the status, unless the daemon answers with a report of the spork */
	protected void printProposalAnswer(final Response response) {
		System.out.println(response.getStatusInfo());
	}

	protected CommandSpec spec() {
		return spec;
	}

	@Override
	public final void run() {
		if (spec.parent().userObject() instanceof GridSporkGet) {
			get();
		} else if (spec.parent().userObject() instanceof GridSporkSet) {
			propose(read(GridSporkSet.getData()));
		} else {
			/* Will only happen if we forget to extend this method */
			throw new UnsupportedOperationException();
		}
	}

	private void get() {
		new RestClientCommand(HttpMethod.GET, location()) {
			@Override
			protected void execute(final Response response) {
				System.out.println(Json.parse(response.readEntity(String.class)));
			}
		}.run();
	}

	private void propose(final D data) {
		final RestClientCommand command = new RestClientCommand(HttpMethod.PUT, location()) {
			@Override
			@SuppressWarnings("unchecked")
			protected <T> Entity<T> getEntity() {
				return (Entity<T>) entityOf(data);
			}

			@Override
			protected void execute(final Response response) {
				printProposalAnswer(response);
			}
		};

		final MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();

		headers.add("privateKey", GridSporkSet.getKey());
		command.setHeaders(headers);
		command.run();
	}
}
