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
import jakarta.ws.rs.core.Response;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.Json;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.GridSpork;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/* A spork that has one place on the daemon to show it, and one way in which the data of the command changes it. Under
   `gridspork-get` the command prints it; under `gridspork-set` the data of the command is read, and the next version
   is built with it, signed on this machine with the board key and handed to the node. What differs from one spork to
   the next is where it is shown, how its data is read and what the data changes; the rest is here. */
@Command
abstract class SporkCommand<D> implements Runnable {
	@Spec
	private CommandSpec spec;

	protected abstract String location();

	protected abstract GridSpork.Type type();

	/* What the data of the command says, or a ParameterException that says what is wrong with it */
	protected abstract D read(String data);

	/* What the data of the command changes in the next version of the spork */
	protected abstract void apply(D data, GridSpork spork);

	protected CommandSpec spec() {
		return spec;
	}

	/* The data is read before the key, as neither needs the node and a mistake in the data is the likelier one */
	@Override
	public final void run() {
		if (spec.parent().userObject() instanceof GridSporkGet) {
			get();
		} else if (spec.parent().userObject() instanceof GridSporkSet set) {
			final D data = read(GridSporkSet.getData());

			SporkProposal.propose(spec.commandLine(), type(), spork -> apply(data, spork),
				set.getBoardKey().resolve(NetworkKey::isTrusted), set.isFirstVersion());
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
}
