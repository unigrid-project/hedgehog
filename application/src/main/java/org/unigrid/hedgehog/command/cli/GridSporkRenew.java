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

package org.unigrid.hedgehog.command.cli;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.client.rest.SporkClient;
import org.unigrid.hedgehog.command.cli.spork.SporkProposal;
import org.unigrid.hedgehog.command.option.BoardKeyOptions;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.spork.GridSpork;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "gridspork-renew",
	description = "Propose every spork the node holds again, signed on this machine with a board key and with the "
		+ "spork data unchanged. A second board key accepts the proposals with gridspork-cosign."
)
public class GridSporkRenew implements Runnable {
	@Mixin
	private BoardKeyOptions boardKey;

	@Override
	public void run() {
		final String key = boardKey.resolve();

		try (RestClient rest = RestClientCommand.connect()) {
			final SporkClient sporks = new SporkClient(rest);
			final List<GridSpork> renewed = renewed(sporks, key);

			if (renewed.isEmpty()) {
				System.out.println("No sporks to renew");
			}

			for (GridSpork spork : renewed) {
				SporkProposal.print(sporks.submit(spork));
			}
		} catch (ResponseOddityException | IOException ex) {
			System.err.println(RestClientCommand.describe(ex));
		} catch (SigningException | IllegalArgumentException ex) {
			System.err.println("Renewing refused, nothing was proposed: " + ex.getMessage());
		}
	}

	/* Everything is signed before anything is proposed, so a signing failure proposes nothing */
	private static List<GridSpork> renewed(SporkClient sporks, String key) throws ResponseOddityException,
		SigningException {

		final List<GridSpork> stored = new ArrayList<>();

		for (GridSpork.Type type : GridSpork.Type.values()) {
			if (type != GridSpork.Type.UNDEFINED) {
				sporks.stored(type).ifPresent(stored::add);
			}
		}

		for (GridSpork spork : stored) {
			spork.renew();
			spork.sign(key);
		}

		return stored;
	}
}
