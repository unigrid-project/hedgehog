/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

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

import lombok.SneakyThrows;
import org.unigrid.hedgehog.client.RestClient;
import org.unigrid.hedgehog.command.cli.GridSporkGet;
import org.unigrid.hedgehog.command.cli.GridSporkGrow;
import org.unigrid.hedgehog.command.cli.GridSporkSet;
import org.unigrid.hedgehog.command.option.RestOptions;
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
		final RestClient client = new RestClient(RestOptions.getHost(), RestOptions.getPort(), true);

		if (spec.parent().userObject() instanceof GridSporkGet) {
			System.out.println("A");
		} else if (spec.parent().userObject() instanceof GridSporkGrow) {
			System.out.println("B");
		} else if (spec.parent().userObject() instanceof GridSporkSet) {
			System.out.println("C");
		}

		client.post(String.format("/gridspork/set/mint-supply/%s",
			GridSporkSet.getKey()), GridSporkSet.getData()
		);

		client.close();
	}
}
